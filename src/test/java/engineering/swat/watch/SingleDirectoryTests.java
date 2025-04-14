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
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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

        var bookkeeper = new TestHelper.Bookkeeper();
        var watchConfig = Watcher.watch(directory, WatchScope.PATH_AND_CHILDREN)
            .onOverflow(Approximation.ALL)
            .on(bookkeeper);

        try (var watch = watchConfig.start()) {
            var overflow = new WatchEvent(OVERFLOW, directory);
            ((EventHandlingWatch) watch).handleEvent(overflow);

            await("Overflow should be visible to user-defined event handler")
                .until(() -> bookkeeper.events().kind(OVERFLOW).any());

            for (var e : new WatchEvent[] {
                new WatchEvent(CREATED, directory, Path.of("d1")),
                new WatchEvent(CREATED, directory, Path.of("d2")),
                new WatchEvent(CREATED, directory, Path.of("d3")),
                new WatchEvent(CREATED, directory, Path.of("a.txt")),
                new WatchEvent(CREATED, directory, Path.of("b.txt")),
                new WatchEvent(CREATED, directory, Path.of("c.txt")),
                new WatchEvent(MODIFIED, directory, Path.of("a.txt")),
                new WatchEvent(MODIFIED, directory, Path.of("b.txt"))
            }) {
                await("Overflow should trigger event: " + e)
                    .until(() -> bookkeeper.events().any(e));
            }

            var event = new WatchEvent(MODIFIED, directory, Path.of("c.txt"));
            await("Overflow shouldn't trigger event: " + event)
                .until(() -> bookkeeper.events().none(event));
        }
    }

    @Test
    void indexingRescanOnOverflow() throws IOException, InterruptedException {
        var directory = testDir.getTestDirectory();

        var bookkeeper = new TestHelper.Bookkeeper();
        var dropEvents = new AtomicBoolean(false); // Toggles overflow simulation
        var watchConfig = Watcher.watch(directory, WatchScope.PATH_AND_CHILDREN)
            .filter(e -> !dropEvents.get())
            .onOverflow(Approximation.DIFF)
            .on(bookkeeper);

        try (var watch = watchConfig.start()) {

            // Begin overflow simulation
            dropEvents.set(true);

            // Do some file operations. No events should be observed (because
            // the overflow simulation is running).
            Files.writeString(directory.resolve("a.txt"), "foo");
            Files.writeString(directory.resolve("b.txt"), "bar");
            Files.delete(directory.resolve("c.txt"));
            Files.createFile(directory.resolve("d.txt"));

            await("No events should have been triggered")
                .pollDelay(TestHelper.SHORT_WAIT)
                .until(() -> bookkeeper.events().none());

            // End overflow simulation, and generate an `OVERFLOW` event.
            // Synthetic events should now be issued and observed.
            dropEvents.set(false);
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, directory);
            ((EventHandlingWatch) watch).handleEvent(overflow);

            for (var e : new WatchEvent[] {
                new WatchEvent(MODIFIED, directory, Path.of("a.txt")),
                new WatchEvent(MODIFIED, directory, Path.of("b.txt")),
                new WatchEvent(DELETED, directory, Path.of("c.txt")),
                new WatchEvent(CREATED, directory, Path.of("d.txt"))
            }) {
                await("Overflow should trigger event: " + e)
                    .until(() -> bookkeeper.events().any(e));
            }

            // Do some more file operations. All events should be observed
            // (because the overflow simulation is no longer running).
            bookkeeper.reset();
            Files.writeString(directory.resolve("b.txt"), "baz");
            Files.createFile(directory.resolve("c.txt"));
            Files.delete(directory.resolve("d.txt"));

            for (var e : new WatchEvent[] {
                new WatchEvent(MODIFIED, directory, Path.of("b.txt")),
                new WatchEvent(CREATED, directory, Path.of("c.txt")),
                new WatchEvent(DELETED, directory, Path.of("d.txt"))
            }) {
                await("File operation should trigger event: " + e)
                    .until(() -> bookkeeper.events().any(e));
            }

            // Generate another `OVERFLOW` event. Synthetic events shouldn't be
            // issued and observed (because the index should have been updated).
            bookkeeper.reset();
            ((EventHandlingWatch) watch).handleEvent(overflow);

            await("No events should have been triggered")
                .pollDelay(TestHelper.SHORT_WAIT)
                .until(() -> bookkeeper.events().kindNot(OVERFLOW).none());
        }
    }
}
