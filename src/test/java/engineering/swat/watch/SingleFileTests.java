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

import static engineering.swat.watch.WatchEvent.Kind.CREATED;
import static engineering.swat.watch.WatchEvent.Kind.DELETED;
import static engineering.swat.watch.WatchEvent.Kind.MODIFIED;
import static engineering.swat.watch.WatchEvent.Kind.OVERFLOW;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.impl.EventHandlingWatch;

class SingleFileTests {
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
    void singleFileShouldNotTriggerOnOtherFilesInSameDir() throws IOException, InterruptedException {
        var target = testDir.getTestFiles().get(0);
        var seen = new AtomicBoolean(false);
        var others = new AtomicBoolean(false);
        var watchConfig = Watch.build(target, WatchScope.PATH_ONLY)
            .on(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    seen.set(true);
                }
                else {
                    others.set(true);
                }
            });
        try (var watch = watchConfig.start()) {
            for (var f : testDir.getTestFiles()) {
                if (!f.equals(target)) {
                    Files.writeString(f, "Hello");
                }
            }
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());
            Files.writeString(target, "Hello world");
            await("Single file does trigger")
                .pollDelay(TestHelper.NORMAL_WAIT.minusMillis(10))
                .failFast("No others should be notified", others::get)
                .untilTrue(seen);
        }
    }

    @Test
    void singleFileThatMonitorsOnlyADirectory() throws IOException, InterruptedException {
        var target = testDir.getTestDirectory();
        var seen = new AtomicBoolean(false);
        var others = new AtomicBoolean(false);
        var watchConfig = Watch.build(target, WatchScope.PATH_ONLY)
            .on(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    seen.set(true);
                }
                else {
                    others.set(true);
                }
            });
        try (var watch = watchConfig.start()) {
            for (var f : testDir.getTestFiles()) {
                if (!f.equals(target)) {
                    Files.writeString(f, "Hello");
                }
            }
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());
            Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
            await("Single directory does trigger")
                .pollDelay(TestHelper.NORMAL_WAIT.minusMillis(10))
                .failFast("No others should be notified", others::get)
                .untilTrue(seen);
        }
    }

    @Test
    void noRescanOnOverflow() throws IOException, InterruptedException {
        var bookkeeper = new TestHelper.Bookkeeper();
        try (var watch = startWatchAndTriggerOverflow(Approximation.NONE, bookkeeper)) {
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());

            await("Overflow shouldn't trigger created, modified, or deleted events")
                .until(() -> bookkeeper.events().kind(CREATED, MODIFIED, DELETED).none());
            await("Overflow should be visible to user-defined event handler")
                .until(() -> bookkeeper.events().kind(OVERFLOW).any());
        }
    }

    @Test
    void memorylessRescanOnOverflow() throws IOException, InterruptedException {
        var bookkeeper = new TestHelper.Bookkeeper();
        try (var watch = startWatchAndTriggerOverflow(Approximation.ALL, bookkeeper)) {
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());

            var path = watch.getPath();
            await("Overflow should trigger created event for `" + path + "`")
                .until(() -> bookkeeper.events().kind(CREATED).rootPath(path).any());
            await("Overflow shouldn't trigger created events for other files")
                .until(() -> bookkeeper.events().kind(CREATED).rootPathNot(path).none());
            await("Overflow shouldn't trigger modified or deleted events")
                .until(() -> bookkeeper.events().kind(MODIFIED, DELETED).none());
            await("Overflow should be visible to user-defined event handler")
                .until(() -> bookkeeper.events().kind(OVERFLOW).any());
        }
    }

    private ActiveWatch startWatchAndTriggerOverflow(Approximation whichFiles, TestHelper.Bookkeeper bookkeeper) throws IOException {
        var parent = testDir.getTestDirectory();
        var file = parent.resolve("a.txt");

        var watch = Watch
            .build(file, WatchScope.PATH_ONLY)
            .onOverflow(whichFiles)
            .on(bookkeeper)
            .start();

        var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, parent);
        ((EventHandlingWatch) watch).handleEvent(overflow);
        return watch;
    }
}
