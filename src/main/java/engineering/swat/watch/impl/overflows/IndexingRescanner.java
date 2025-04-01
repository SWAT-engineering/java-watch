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
package engineering.swat.watch.impl.overflows;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class IndexingRescanner extends MemorylessRescanner {
    private final Logger logger = LogManager.getLogger();
    private final PathMap<FileTime> index = new PathMap<>();

    public IndexingRescanner(Executor exec, Path path, WatchScope scope) {
        super(exec);
        new Indexer(path, scope).walkFileTree(); // Make an initial scan to populate the index
    }

    private static class PathMap<V> {
        private final Map<Path, Map<Path, V>> values = new ConcurrentHashMap<>();
        //                ^^^^      ^^^^
        //                Parent    File name (regular file or directory)

        public @Nullable V put(Path p, V value) {
            return apply(put(value), p);
        }

        public @Nullable V get(Path p) {
            return apply(this::get, p);
        }

        public Set<Path> getParents() {
            return (Set<Path>) values.keySet();
        }

        public Set<Path> getFileNames(Path parent) {
            var inner = values.get(parent);
            return inner == null ? Collections.emptySet() : (Set<Path>) inner.keySet();
        }

        public @Nullable V remove(Path p) {
            return apply(this::remove, p);
        }

        private static <V> @Nullable V apply(BiFunction<Path, Path, @Nullable V> action, Path p) {
            var parent = p.getParent();
            var fileName = p.getFileName();
            if (parent != null && fileName != null) {
                return action.apply(parent, fileName);
            } else {
                throw new IllegalArgumentException("The path should have both a parent and a file name");
            }
        }

        private BiFunction<Path, Path, @Nullable V> put(V value) {
            return (parent, fileName) -> put(parent, fileName, value);
        }

        private @Nullable V put(Path parent, Path fileName, V value) {
            var inner = values.computeIfAbsent(parent, x -> new ConcurrentHashMap<>());

            // This thread (henceforth: "here") optimistically puts a new entry
            // in `inner`. However, another thread (henceforth: "there") may
            // concurrently remove `inner` from `values`. Thus, the new entry
            // may be lost. The comments below explain the countermeasures.
            var previous = inner.put(fileName, value);

            // <-- At this point "here", if `values.remove(parent)` happens
            //     "there", then `values.get(parent) != inner` becomes true
            //     "here", so the new entry will be re-put "here".
            if (values.get(parent) != inner) {
                previous = put(parent, fileName, value);
            }
            // <-- At this point "here", `!inner.isEmpty()` has become true
            //     "there", so if `values.remove(parent)` happens "there", then
            //     the new entry will be re-put "there".
            return previous;
        }

        private @Nullable V get(Path parent, Path fileName) {
            var inner = values.get(parent);
            return inner == null ? null : inner.get(fileName);
        }

        private @Nullable V remove(Path parent, Path fileName) {
            var inner = values.get(parent);
            if (inner != null) {
                var removed = inner.remove(fileName);

                // This thread (henceforth: "here") optimistically removes
                // `inner` from `values` when it has become empty. However,
                // another thread (henceforth: "there") may concurrently put a
                // new entry in `inner`. Thus, the new entry may be lost. The
                // comments below explain the countermeasures.
                if (inner.isEmpty() && values.remove(parent, inner)) {

                    // <-- At this point "here", if `inner.put(...)` happens
                    //     "there", then `!inner.isEmpty()` becomes true "here",
                    //     so the new entry is re-put "here".
                    if (!inner.isEmpty()) {
                        for (var e : inner.entrySet()) {
                            put(parent, e.getKey(), e.getValue());
                        }
                    }
                    // <-- At this point "here", `values.get(parent) != inner`
                    //     has become true "there", so if `inner.put(...)`
                    //     happens "there", then the new entry will be re-put
                    //     "there".
                }
                return removed;
            } else {
                return null;
            }
        }
    }

    private class Indexer extends BaseFileVisitor {
        public Indexer(Path path, WatchScope scope) {
            super(path, scope);
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (!path.equals(dir)) {
                index.put(dir, attrs.lastModifiedTime());
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            index.put(file, attrs.lastModifiedTime());
            return FileVisitResult.CONTINUE;
        }
    }

    // -- MemorylessRescanner --

    @Override
    protected MemorylessRescanner.Generator newGenerator(Path path, WatchScope scope) {
        return new Generator(path, scope);
    }

    protected class Generator extends MemorylessRescanner.Generator {
        // Field to keep track of (a stack of sets, of file names, of) the paths
        // that are visited during the current rescan (one frame for each nested
        // subdirectory), to approximate `DELETED` events that happened since
        // the previous rescan. Instances of this class are supposed to be used
        // non-concurrently, so no synchronization to access this field is
        // needed.
        private final Deque<Set<Path>> visited = new ArrayDeque<>();

        public Generator(Path path, WatchScope scope) {
            super(path, scope);
            this.visited.push(new HashSet<>()); // Initial set for content of `path`
        }

        private void addToPeeked(Deque<Set<Path>> deque, Path p) {
            var peeked = deque.peek();
            var fileName = p.getFileName();
            if (peeked != null && fileName != null) {
                peeked.add(fileName);
            }
        }

        // -- MemorylessRescanner.Generator --

        @Override
        protected void generateEvents(Path path, BasicFileAttributes attrs) {
            var lastModifiedTimeOld = index.get(path);
            var lastModifiedTimeNew = attrs.lastModifiedTime();

            // The path isn't indexed yet
            if (lastModifiedTimeOld == null) {
                super.generateEvents(path, attrs);
            }

            // The path is already indexed, and the previous last-modified-time
            // is older than the current last-modified-time
            else if (lastModifiedTimeOld.compareTo(lastModifiedTimeNew) < 0) {
                events.add(new WatchEvent(WatchEvent.Kind.MODIFIED, path));
            }
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            addToPeeked(visited, dir);
            visited.push(new HashSet<>());
            return super.preVisitDirectory(dir, attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            addToPeeked(visited, file);
            return super.visitFile(file, attrs);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            // Issue `DELETED` events based on the set of paths visited in `dir`
            var visitedInDir = visited.pop();
            if (visitedInDir != null) {
                for (var p : index.getFileNames(dir)) {
                    if (!visitedInDir.contains(p)) {
                        var fullPath = dir.resolve(p);
                        // The index may have been updated during the visit, so
                        // even if `p` isn't contained in `visitedInDir`, by
                        // now, it might have come into existance.
                        if (!Files.exists(fullPath)) {
                            events.add(new WatchEvent(WatchEvent.Kind.DELETED, fullPath));
                        }
                    }
                }
            }
            return super.postVisitDirectory(dir, exc);
        }
    }

    // -- MemorylessRescanner --

    @Override
    public void accept(EventHandlingWatch watch, WatchEvent event) {
        // Auto-handle `OVERFLOW` events
        super.accept(watch, event);

        // Additional processing is needed to update the index when `CREATED`,
        // `MODIFIED`, and `DELETED` events happen.
        var kind = event.getKind();
        var fullPath = event.calculateFullPath();
        switch (kind) {
            case CREATED:
            case MODIFIED:
                try {
                    var lastModifiedTimeNew = Files.getLastModifiedTime(fullPath);
                    var lastModifiedTimeOld = index.put(fullPath, lastModifiedTimeNew);

                    // If a `MODIFIED` event happens for a path that wasn't in
                    // the index yet, then a `CREATED` event has somehow been
                    // missed. Just in case, it's issued synthetically here.
                    if (lastModifiedTimeOld == null && kind == WatchEvent.Kind.MODIFIED) {
                        var created = new WatchEvent(WatchEvent.Kind.CREATED, fullPath);
                        watch.handleEvent(watch.relativize(created));
                    }
                } catch (IOException e) {
                    // It can happen that, by the time a `CREATED`/`MODIFIED`
                    // event is handled above, getting the last-modified-time
                    // fails because the file has already been deleted. That's
                    // fine: we can just ignore the event. (The corresponding
                    // `DELETED` event will later be handled and remove the file
                    // from the index.) If the file exists, though, something
                    // went legitimately wrong, so it needs to be reported.
                    if (Files.exists(fullPath)) {
                        logger.error("Could not get modification time of: {} ({})", fullPath, e);
                    }
                }
                break;
            case DELETED:
                index.remove(fullPath);
                break;
            case OVERFLOW: // Already auto-handled above
                break;
        }
    }
}
