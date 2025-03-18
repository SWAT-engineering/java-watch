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
package engineering.swat.watch.impl;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.TestDirectory;
import engineering.swat.watch.TestHelper;
import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchEvent.Kind;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.jdk.JDKFileTreeWatch;
import engineering.swat.watch.impl.overflows.IndexingRescanner;
import engineering.swat.watch.impl.overflows.MemorylessRescanner;

class JDKFileTreeWatchTests {
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
    void noRescannerPreservesIntegrityOfChildWatches() throws IOException {
        var checkCreatedFiles = false;
        // By setting `checkCreatedFiles` to `false`, this test *does* check
        // that the integrity of the internal tree of watches is preserved, but
        // it *doesn't* check if `CREATED` events for files are missed. Such
        // events could happen between the creation of a directory and the start
        // of the watch for that directory. Without an (auto-)handler for
        // `OVERFLOW` events, these aren't observed by the watch. The other
        // tests in this class, which do use auto-handling for `OVERFLOW`
        // events, set `checkCreatedFiles` to `true`.
        rescannerPreservesIntegrity(
            (path, exec) -> (w, e) -> {},
            checkCreatedFiles);
    }

    @Test
    void memorylessRescannerPreservesIntegrity() throws IOException {
        rescannerPreservesIntegrity((path, exec) ->
            new MemorylessRescanner(exec));
    }

    @Test
    void indexingRescannerPreservesIntegrity() throws IOException {
        rescannerPreservesIntegrity((path, exec) ->
            new IndexingRescanner(exec, path, WatchScope.PATH_AND_ALL_DESCENDANTS));
    }

    private void rescannerPreservesIntegrity(BiFunction<Path, Executor, BiConsumer<EventHandlingWatch, WatchEvent>> newRescanner) throws IOException {
        rescannerPreservesIntegrity(newRescanner, true);
    }

    private void rescannerPreservesIntegrity(BiFunction<Path, Executor, BiConsumer<EventHandlingWatch, WatchEvent>> newRescanner, boolean checkCreatedFiles) throws IOException {
        var root = testDir.getTestDirectory();
        var exec = ForkJoinPool.commonPool();

        var events = ConcurrentHashMap.<WatchEvent> newKeySet(); // Stores all incoming events
        var rescanner = newRescanner.apply(root, exec);
        var watch = new JDKFileTreeWatch(root, exec, (w, e) -> {
            events.add(e);
            rescanner.accept(w, e);
        });

        watch.open();

        try {
            var parent = Path.of("");
            var child1 = Path.of("foo");
            var child2 = Path.of("bar");
            var grandGrandGrandChild = Path.of("bar", "x", "y", "z");

            var family = new Path[] {
                parent, child1, child2, grandGrandGrandChild };

            // Define helper function
            Function<String, List<Path>> createFiles = fileName -> {
                try {
                    var files = Stream.of(family)
                        .map(p -> p.resolve(fileName))
                        .collect(Collectors.toList());
                    for (var f : files) {
                        Files.createFile(root.resolve(f));
                    }
                    return files; // Paths relative to `parent`
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };

            // Define helper predicate
            BiPredicate<WatchEvent.Kind, Path> eventsContains = (kind, relative) ->
                    events.stream().anyMatch(e ->
                        e.getKind().equals(kind) &&
                        e.getRootPath().equals(root) &&
                        e.getRelativePath().equals(relative));

            // Part 1: Create subdirectories. Changes in both the root and in
            // the descendants should be observed by the watch.
            Files.createDirectories(root.resolve(child1));
            Files.createDirectories(root.resolve(child2));
            Files.createDirectories(root.resolve(grandGrandGrandChild));

            for (var p : family) {
                await("Watch should exist for `" + p + "`")
                    .until(() -> p == parent || getDescendantWatch(watch, p) != null);
            }
            for (var file : createFiles.apply("file1.txt")) {
                await("Creation of `" + file + "` should be observed (events: " + events + ")")
                    .until(() -> !checkCreatedFiles || eventsContains.test(Kind.CREATED, file));
            }

            // Part 2: Artificially remove child watches. Changes in the root
            // should still be observed by the watch, but changes in the
            // descendants shouldn't.
            getChildWatches(watch).remove(child1).close();
            getChildWatches(watch).remove(child2).close(); // Should also remove and close the watch for `grandGrandGrandChild`

            for (var p : family) {
                await("Watch shouldn't exist for `" + p + "`")
                    .until(() -> p == parent || getDescendantWatch(watch, p) == null);
            }
            for (var file : createFiles.apply("file2.txt")) {
                await("Creation of `" + file + "` shouldn't be observed")
                    .until(() ->
                        !checkCreatedFiles ||
                        file.equals(Path.of("file2.txt")) ||
                        !eventsContains.test(Kind.CREATED, file));
            }

            // Part 3: Trigger overflow to restore child watches. Changes in
            // both the root and in the descendants should be observed by the
            // watch.
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, root);
            watch.handleEvent(overflow);

            for (var p : family) {
                await("Watch should exist for `" + p + "`")
                    .until(() -> p == parent || getDescendantWatch(watch, p) != null);
            }
            for (var file : createFiles.apply("file3.txt")) {
                await("Creation of `" + file + "` should be observed")
                    .until(() -> !checkCreatedFiles || eventsContains.test(Kind.CREATED, file));
            }
        }
        finally {
            watch.close();
        }
    }

    private static @Nullable JDKFileTreeWatch getDescendantWatch(JDKFileTreeWatch rootWatch, Path descendant) {
        assert !descendant.equals(Path.of(""));
        var child = descendant.getFileName();
        var parent = descendant.getParent();
        if (parent == null) {
            return getChildWatches(rootWatch).get(child);
        } else {
            var parentWatch = getDescendantWatch(rootWatch, parent);
            if (parentWatch == null) {
                return null;
            }
            return getChildWatches(parentWatch).get(child);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, JDKFileTreeWatch> getChildWatches(JDKFileTreeWatch watch) {
        try {
            var field = JDKFileTreeWatch.class.getDeclaredField("childWatches");
            field.setAccessible(true);
            return (Map<Path, JDKFileTreeWatch>) field.get(watch);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
