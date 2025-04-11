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

import static engineering.swat.watch.WatchEvent.Kind.*;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
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

        var bookkeeper = new TestHelper.Bookkeeper();
        var watchConfig = Watcher.watch(directory, WatchScope.PATH_AND_CHILDREN)
            .onOverflow(Approximation.ALL)
            .on(bookkeeper);

        try (var watch = watchConfig.start()) {
            var overflow = new WatchEvent(OVERFLOW, directory);
            ((EventHandlingWatch) watch).handleEvent(overflow);

            await("Overflow should be visible to user-defined event handler")
                .until(() -> bookkeeper.contains(overflow));

            for (var event : new WatchEvent[] {
                new WatchEvent(CREATED, directory, Path.of("d1")),
                new WatchEvent(CREATED, directory, Path.of("d2")),
                new WatchEvent(CREATED, directory, Path.of("d3")),
                new WatchEvent(CREATED, directory, Path.of("a.txt")),
                new WatchEvent(CREATED, directory, Path.of("b.txt")),
                new WatchEvent(CREATED, directory, Path.of("c.txt")),
                new WatchEvent(MODIFIED, directory, Path.of("a.txt")),
                new WatchEvent(MODIFIED, directory, Path.of("b.txt"))
            }) {
                await("Overflow should trigger event: " + event)
                    .until(() -> bookkeeper.contains(event));
            }

            var event = new WatchEvent(MODIFIED, directory, Path.of("c.txt"));
            await("Overflow shouldn't trigger event: " + event)
                .until(() -> !bookkeeper.contains(event));
        }
    }

    @Test
    void indexingRescanOnOverflow() throws IOException, InterruptedException {
        // Preface: This test looks a bit hacky because there's no API to
        // directly manipulate, or prevent the auto-manipulation of, the index
        // inside a watch. I've added some comments below to make it make sense.

        var directory = testDir.getTestDirectory();
        var semaphore = new Semaphore(0);

        var nCreated = new AtomicInteger();
        var nModified = new AtomicInteger();
        var nDeleted = new AtomicInteger();

        var watchConfig = Watcher.watch(directory, WatchScope.PATH_AND_CHILDREN)
            .onOverflow(Approximation.DIFF)
            .on(e -> {
                var kind = e.getKind();
                if (kind != OVERFLOW) {
                    // Threads can handle non-`OVERFLOW` events *only after*
                    // everything is "ready" for that (in which case a token is
                    // released to the semaphore, which is initially empty). See
                    // below for an explanation of "readiness".
                    semaphore.acquireUninterruptibly();
                    switch (e.getKind()) {
                        case CREATED:
                            nCreated.incrementAndGet();
                            break;
                        case MODIFIED:
                            nModified.incrementAndGet();
                            break;
                        case DELETED:
                            nDeleted.incrementAndGet();
                            break;
                        default:
                            break;
                    }
                    semaphore.release();
                }
            });

        try (var watch = watchConfig.start()) {
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            // At this point, the index of last-modified-times inside `watch` is
            // populated with initial values.

            Files.writeString(directory.resolve("a.txt"), "foo");
            Files.writeString(directory.resolve("b.txt"), "bar");
            Files.delete(directory.resolve("c.txt"));
            Files.createFile(directory.resolve("d.txt"));
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            // At this point, regular events have been generated for a.txt,
            // b.txt, c.txt, and d.txt by the file system. These events won't be
            // handled by `watch` just yet, though, because the semaphore is
            // still empty (i.e., event-handling threads are blocked from making
            // progress). Thus, the index inside `watch` still contains the
            // initial last-modified-times. (Warning: The blockade works only
            // when the rescanner runs after the user-defined event-handler.
            // Currently, this is the case, but changing their order probably
            // breaks this test.)

            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, directory);
            ((EventHandlingWatch) watch).handleEvent(overflow);
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            // At this point, the current thread has presumably slept long
            // enough for the `OVERFLOW` event to have been handled by the
            // rescanner. This means that synthetic events must have been issued
            // (because the index still contained the initial last-modified
            // times).

            // Readiness achieved: Threads can now start handling non-`OVERFLOW`
            // events.
            semaphore.release();

            await("Overflow should trigger created events")
                .until(nCreated::get, n -> n >= 2); // 1 synthetic event + >=1 regular event
            await("Overflow should trigger modified events")
                .until(nModified::get, n -> n >= 4); // 2 synthetic events + >=2 regular events
            await("Overflow should trigger deleted events")
                .until(nDeleted::get, n -> n >= 2); // 1 synthetic event + >=1 regular event

            // Reset counters for next phase of the test
            nCreated.set(0);
            nModified.set(0);
            nDeleted.set(0);

            // Let's do some more file operations, trigger another `OVERFLOW`
            // event, and observe that synthetic events *aren't* issued this
            // time (because the index was already updated when the regular
            // events were handled).
            Files.writeString(directory.resolve("b.txt"), "baz");
            Files.createFile(directory.resolve("c.txt"));
            Files.delete(directory.resolve("d.txt"));

            await("File create should trigger regular created event")
                .until(nCreated::get, n -> n >= 1);
            await("File write should trigger regular modified event")
                .until(nModified::get, n -> n >= 1);
            await("File delete should trigger regular deleted event")
                .until(nDeleted::get, n -> n >= 1);

            var nCreatedBeforeOverflow = nCreated.get();
            var nModifiedBeforeOverflow = nModified.get();
            var nDeletedBeforeOverflow = nDeleted.get();

            ((EventHandlingWatch) watch).handleEvent(overflow);
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());

            await("Overflow shouldn't trigger synthetic created event after file create (and index updated)")
                .until(nCreated::get, Predicate.isEqual(nCreatedBeforeOverflow));
            await("Overflow shouldn't trigger synthetic modified event after file write (and index updated)")
                .until(nModified::get, Predicate.isEqual(nModifiedBeforeOverflow));
            await("Overflow shouldn't trigger synthetic deleted event after file delete (and index updated)")
                .until(nDeleted::get, Predicate.isEqual(nDeletedBeforeOverflow));
        }
    }
}
