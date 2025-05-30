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
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class SmokeTests {
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
    void watchDirectory() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().get(0);
        var watchConfig = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
            .on(ev -> {if (ev.getKind() == MODIFIED && ev.calculateFullPath().equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await("Target file change").untilTrue(changed);
        }
    }

    @Test
    void watchRecursiveDirectory() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> !p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();
        var watchConfig = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(ev -> { if (ev.getKind() == MODIFIED && ev.calculateFullPath().equals(target)) { changed.set(true);}})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await("Nested file change").untilTrue(changed);
        }
    }

    @Test
    void watchSingleFile() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();

        var watchConfig = Watch.build(target, WatchScope.PATH_ONLY)
            .on(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    changed.set(true);
                }
            });

        try (var watch = watchConfig.start()) {
            Files.writeString(target, "Hello world");
            await("Single file change").untilTrue(changed);
        }
    }

    @Test
    void moveRegularFile() throws IOException {
        var parent = testDir.getTestDirectory();
        var child1 = Files.createDirectories(parent.resolve("from"));
        var child2 = Files.createDirectories(parent.resolve("to"));
        var regularFile = Files.createFile(child1.resolve("file.txt"));

        var parentWatchBookkeeper = new TestHelper.Bookkeeper();
        var parentWatchConfig = Watch
            .build(parent, WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(parentWatchBookkeeper);

        var child1WatchBookkeeper = new TestHelper.Bookkeeper();
        var child1WatchConfig = Watch
            .build(child1, WatchScope.PATH_AND_CHILDREN)
            .on(child1WatchBookkeeper);

        var child2WatchBookkeeper = new TestHelper.Bookkeeper();
        var child2WatchConfig = Watch
            .build(child2, WatchScope.PATH_AND_CHILDREN)
            .on(child2WatchBookkeeper);

        var fileWatchBookkeeper = new TestHelper.Bookkeeper();
        var fileWatchConfig = Watch
            .build(regularFile, WatchScope.PATH_ONLY)
            .on(fileWatchBookkeeper);

        try (var parentWatch = parentWatchConfig.start();
             var child1Watch = child1WatchConfig.start();
             var child2Watch = child2WatchConfig.start();
             var fileWatch = fileWatchConfig.start()) {

            var source = child1.resolve(regularFile.getFileName());
            var target = child2.resolve(regularFile.getFileName());
            Files.move(source, target);

            for (var e : new WatchEvent[] {
                new WatchEvent(DELETED, parent, parent.relativize(source)),
                new WatchEvent(CREATED, parent, parent.relativize(target))
            }) {
                await("Move should be observed as delete/create by `parent` watch (file tree): " + e)
                    .until(() -> parentWatchBookkeeper.events().any(e));
            }

            await("Move should be observed as delete by `child1` watch (single directory)")
                .until(() -> child1WatchBookkeeper
                    .events().kind(DELETED).rootPath(child1).relativePath(child1.relativize(source)).any());

            await("Move should be observed as create by `child2` watch (single directory)")
                .until(() -> child2WatchBookkeeper
                    .events().kind(CREATED).rootPath(child2).relativePath(child2.relativize(target)).any());

            await("Move should be observed as delete by `file` watch (regular file)")
                .until(() -> fileWatchBookkeeper
                    .events().kind(DELETED).rootPath(source).any());
        }
    }

    @Test
    void moveDirectory() throws IOException {
        var parent = testDir.getTestDirectory();
        var child1 = Files.createDirectories(parent.resolve("from"));
        var child2 = Files.createDirectories(parent.resolve("to"));

        var directory = Files.createDirectory(child1.resolve("directory"));
        var regularFile1 = Files.createFile(directory.resolve("file1.txt"));
        var regularFile2 = Files.createFile(directory.resolve("file2.txt"));

        var parentWatchBookkeeper = new TestHelper.Bookkeeper();
        var parentWatchConfig = Watch
            .build(parent, WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(parentWatchBookkeeper);

        var child1WatchBookkeeper = new TestHelper.Bookkeeper();
        var child1WatchConfig = Watch
            .build(child1, WatchScope.PATH_AND_CHILDREN)
            .on(child1WatchBookkeeper);

        var child2WatchBookkeeper = new TestHelper.Bookkeeper();
        var child2WatchConfig = Watch
            .build(child2, WatchScope.PATH_AND_CHILDREN)
            .on(child2WatchBookkeeper);

        var directoryWatchBookkeeper = new TestHelper.Bookkeeper();
        var directoryWatchConfig = Watch
            .build(directory, WatchScope.PATH_ONLY)
            .on(directoryWatchBookkeeper);

        try (var parentWatch = parentWatchConfig.start();
             var child1Watch = child1WatchConfig.start();
             var child2Watch = child2WatchConfig.start();
             var fileWatch = directoryWatchConfig.start()) {

            var sourceDirectory = child1.resolve(directory.getFileName());
            var sourceRegularFile1 = sourceDirectory.resolve(regularFile1.getFileName());
            var sourceRegularFile2 = sourceDirectory.resolve(regularFile2.getFileName());

            var targetDirectory = child2.resolve(directory.getFileName());
            var targetRegularFile1 = targetDirectory.resolve(regularFile1.getFileName());
            var targetRegularFile2 = targetDirectory.resolve(regularFile2.getFileName());

            Files.move(sourceDirectory, targetDirectory);

            for (var e : new WatchEvent[] {
                new WatchEvent(DELETED, parent, parent.relativize(sourceDirectory)),
                new WatchEvent(CREATED, parent, parent.relativize(targetDirectory)),
                // The following events currently *aren't* observed by the
                // `parent` watch for the whole file tree: moving a directory
                // doesn't trigger events for the deletion/creation of the files
                // contained in it (neither using the general default/JDK
                // implementation of Watch Service, nor using our special macOS
                // implementation).
                //
                // new WatchEvent(DELETED, parent, parent.relativize(sourceRegularFile1)),
                // new WatchEvent(DELETED, parent, parent.relativize(sourceRegularFile2)),
                // new WatchEvent(CREATED, parent, parent.relativize(targetRegularFile1)),
                // new WatchEvent(CREATED, parent, parent.relativize(targetRegularFile2))
            }) {
                await("Move should be observed as delete/create by `parent` watch (file tree): " + e)
                    .until(() -> parentWatchBookkeeper.events().any(e));
            }

            await("Move should be observed as delete by `child1` watch (single directory)")
                .until(() -> child1WatchBookkeeper
                    .events().kind(DELETED).rootPath(child1).relativePath(child1.relativize(sourceDirectory)).any());

            await("Move should be observed as create by `child2` watch (single directory)")
                .until(() -> child2WatchBookkeeper
                    .events().kind(CREATED).rootPath(child2).relativePath(child2.relativize(targetDirectory)).any());

            await("Move should be observed as delete by `directory` watch")
                .until(() -> directoryWatchBookkeeper
                    .events().kind(DELETED).rootPath(sourceDirectory).any());
        }
    }
}
