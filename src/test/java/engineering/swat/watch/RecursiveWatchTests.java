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
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import engineering.swat.watch.WatchEvent.Kind;
import engineering.swat.watch.impl.EventHandlingWatch;

class RecursiveWatchTests {
    private final Logger logger = LogManager.getLogger();

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
    void newDirectoryWithFilesChangesDetected() throws IOException {
        var target = new AtomicReference<Path>();
        var created = new AtomicBoolean(false);
        var changed = new AtomicBoolean(false);
        var watchConfig = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(ev -> {
                    logger.debug("Event received: {}", ev);
                    if (ev.calculateFullPath().equals(target.get())) {
                        switch (ev.getKind()) {
                            case CREATED:
                                created.set(true);
                                break;
                            case MODIFIED:
                                changed.set(true);
                                break;
                            default:
                                break;
                        }
                    }
            });

        try (var activeWatch = watchConfig.start() ) {
            var freshFile = Files.createTempDirectory(testDir.getTestDirectory(), "new-dir").resolve("test-file.txt");
            target.set(freshFile);
            logger.debug("Interested in: {}", freshFile);
            Files.writeString(freshFile, "Hello world");
            await("New files should have been seen").untilTrue(created);
            Files.writeString(freshFile, "Hello world 2");
            await("Fresh file change have been detected").untilTrue(changed);
        }
    }

    @Test
    void correctRelativePathIsReported() throws IOException {
        Path relative = Path.of("a","b", "c", "d.txt");
        var seen = new AtomicBoolean(false);
        var watcher = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(ev -> {
                logger.debug("Seen event: {}", ev);
                if (ev.getRelativePath().equals(relative)) {
                    seen.set(true);
                }
            });

        try (var w = watcher.start()) {
            var targetFile = testDir.getTestDirectory().resolve(relative);
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, "Hello World");
            await("Nested path is seen").untilTrue(seen);
        }

    }

    @Test
    void deleteOfFileInDirectoryShouldBeVisible() throws IOException {
        var target = testDir.getTestFiles()
            .stream()
            .filter(p -> !p.getParent().equals(testDir.getTestDirectory()))
            .findAny()
            .orElseThrow();
        var seen = new AtomicBoolean(false);
        var watchConfig = Watch.build(target.getParent(), WatchScope.PATH_AND_CHILDREN)
            .on(ev -> {
                if (ev.getKind() == Kind.DELETED && ev.calculateFullPath().equals(target)) {
                    seen.set(true);
                }
            });
        try (var watch = watchConfig.start()) {
            Files.delete(target);
            await("File deletion should generate delete event")
                .untilTrue(seen);
        }
    }

    @ParameterizedTest
    @EnumSource // Repeat test for each `Approximation` value
    void overflowsAreRecoveredFrom(Approximation whichFiles) throws IOException, InterruptedException {
        var parent = testDir.getTestDirectory();
        var descendants = new Path[] {
            Path.of("foo"),
            Path.of("bar"),
            Path.of("bar", "x", "y", "z")
        };

        // Configure and start watch
        var dropEvents = new AtomicBoolean(false); // Toggles overflow simulation
        var bookkeeper = new TestHelper.Bookkeeper();
        var watchConfig = Watch.build(parent, WatchScope.PATH_AND_ALL_DESCENDANTS)
            .withExecutor(ForkJoinPool.commonPool())
            .filter(e -> !dropEvents.get())
            .onOverflow(whichFiles)
            .on(bookkeeper);

        try (var watch = (EventHandlingWatch) watchConfig.start()) {

            // Define helper functions to test which events have happened
            Consumer<Path> awaitCreation = p ->
                await("Creation of `" + p + "` should be observed")
                    .until(() -> bookkeeper.events().kind(CREATED).rootPath(parent).relativePath(p).any());

            Consumer<Path> awaitNotCreation = p ->
                await("Creation of `" + p + "` shouldn't be observed: " + bookkeeper)
                    .pollDelay(TestHelper.TINY_WAIT)
                    .until(() -> bookkeeper.events().kind(CREATED).rootPath(parent).relativePath(p).none());

            // Begin overflow simulation
            dropEvents.set(true);

            // Create descendants and files. They *shouldn't* be observed yet.
            var file1 = Path.of("file1.txt");
            for (var descendant : descendants) {
                Files.createDirectories(parent.resolve(descendant));
                Files.createFile(parent.resolve(descendant).resolve(file1));
            }
            for (var descendant : descendants) {
                awaitNotCreation.accept(descendant);
                awaitNotCreation.accept(descendant.resolve(file1));
            }

            // End overflow simulation, and generate the `OVERFLOW` event. The
            // previous creation of descendants and files *should* now be
            // observed, unless no auto-handler for `OVERFLOW` events is
            // configured.
            dropEvents.set(false);
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, parent);
            watch.handleEvent(overflow);

            if (whichFiles != Approximation.NONE) { // Auto-handler is configured
                for (var descendant : descendants) {
                    awaitCreation.accept(descendant);
                    awaitCreation.accept(descendant.resolve(file1));
                }
            } else {
                // Give the watch some time to process the `OVERFLOW` event and
                // do internal bookkeeping
                Thread.sleep(TestHelper.TINY_WAIT.toMillis());
            }

            // Create more files. They *should* be observed (regardless of
            // whether an auto-handler for `OVERFLOW` events is configured).
            var file2 = Path.of("file2.txt");
            for (var descendant : descendants) {
                Files.createFile(parent.resolve(descendant).resolve(file2));
            }
            for (var descendant : descendants) {
                awaitCreation.accept(descendant.resolve(file2));
            }
        }
    }
}
