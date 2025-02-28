/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2023, Swat.engineering
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package engineering.swat.watch;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.WatchEvent.Kind;
import engineering.swat.watch.impl.EventHandlingWatch;

class SingleDirectoryTests {
    private TestDirectory testDir;

    @BeforeEach
    void setup() throws IOException {
        testDir = new TestDirectory();
    }

    @AfterEach
    void cleanup() {
        if (testDir != null) {
            testDir.close();
        }
    }

    @BeforeAll
    static void setupEverything() {
        Awaitility.setDefaultTimeout(TestHelper.NORMAL_WAIT);
    }

    @Test
    void deleteOfFileInDirectoryShouldBeVisible() throws IOException {
        var target = testDir.getTestFiles().get(0);
        var seenDelete = new AtomicBoolean(false);
        var seenCreate = new AtomicBoolean(false);
        var watchConfig = Watcher.watch(target.getParent(), WatchScope.PATH_AND_CHILDREN)
            .on(ev -> {
                if (ev.getKind() == Kind.DELETED && ev.calculateFullPath().equals(target)) {
                    seenDelete.set(true);
                }
                if (ev.getKind() == Kind.CREATED && ev.calculateFullPath().equals(target)) {
                    seenCreate.set(true);
                }
            });
        try (var watch = watchConfig.start()) {

            // Delete the file
            Files.delete(target);
            await("File deletion should generate delete event")
                .untilTrue(seenDelete);

            // Re-create it again
            Files.writeString(target, "Hello World");
            await("File creation should generate create event")
                .untilTrue(seenCreate);
        }
    }

    @Test
    void alternativeAPITest() throws IOException {
        var target = testDir.getTestFiles().get(0);
        var seenDelete = new AtomicBoolean(false);
        var seenCreate = new AtomicBoolean(false);
        var watchConfig = Watcher.watch(target.getParent(), WatchScope.PATH_AND_CHILDREN)
            .on(new WatchEventListener() {
                @Override
                public void onCreated(WatchEvent ev) {
                    seenCreate.set(true);
                }

                @Override
                public void onDeleted(WatchEvent ev) {
                    seenDelete.set(true);
                }
            });
        try (var watch = watchConfig.start()) {

            // Delete the file
            Files.delete(target);
            await("File deletion should generate delete event")
                .untilTrue(seenDelete);

            // Re-create it again
            Files.writeString(target, "Hello World");
            await("File creation should generate create event")
                .untilTrue(seenCreate);
        }
    }

    @Test
    void memorylessRescanOnOverflow() throws IOException, InterruptedException {
        var directory = testDir.getTestDirectory();
        Files.writeString(directory.resolve("a.txt"), "foo");
        Files.writeString(directory.resolve("b.txt"), "bar");

        var nCreated = new AtomicInteger();
        var nModified = new AtomicInteger();
        var nOverflow = new AtomicInteger();
        var watchConfig = Watcher.watch(directory, WatchScope.PATH_AND_CHILDREN)
            .withOverflowPolicy(OverflowPolicy.MEMORYLESS_RESCANS)
            .on(e -> {
                switch (e.getKind()) {
                    case CREATED:
                        nCreated.incrementAndGet();
                        break;
                    case MODIFIED:
                        nModified.incrementAndGet();
                        break;
                    case OVERFLOW:
                        nOverflow.incrementAndGet();
                        break;
                    default:
                        break;
                }
            });

        try (var watch = watchConfig.start()) {
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, directory);
            ((EventHandlingWatch) watch).handleEvent(overflow);
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());

            await("Overflow should trigger created events")
                .until(nCreated::get, Predicate.isEqual(6)); // 3 directories + 3 files
            await("Overflow should trigger modified events")
                .until(nModified::get, Predicate.isEqual(2)); // 2 files (c.txt is still empty)
            await("Overflow should be visible to user-defined event handler")
                .until(nOverflow::get, Predicate.isEqual(1));
        }
    }
}
