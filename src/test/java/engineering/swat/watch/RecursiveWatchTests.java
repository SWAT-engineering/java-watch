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
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.WatchEvent.Kind;

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
        var watchConfig = Watcher.watch(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
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
        var watcher = Watcher.watch(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
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
        var watchConfig = Watcher.watch(target.getParent(), WatchScope.PATH_AND_CHILDREN)
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

}
