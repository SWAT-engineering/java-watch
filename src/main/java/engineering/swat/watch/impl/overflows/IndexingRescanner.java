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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class IndexingRescanner extends MemorylessRescanner {
    private final Logger logger = LogManager.getLogger();
    private final Map<Path, FileTime> index = new ConcurrentHashMap<>();

    public IndexingRescanner(Executor exec, Path path, WatchScope scope) {
        super(exec);
        new Indexer(path, scope).walkFileTree(); // Make an initial scan to populate the index
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
        // Field to keep track of the paths that are visited during the current
        // rescan. After the visit, the `DELETED` events that happened since the
        // previous rescan can be approximated.
        private Set<Path> visited = new HashSet<>();

        public Generator(Path path, WatchScope scope) {
            super(path, scope);
        }

        // -- MemorylessRescanner.Generator --

        @Override
        protected void generateEvents(Path path, BasicFileAttributes attrs) {
            visited.add(path);
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
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            // If the visitor is back at the root of the rescan, then the time
            // is right to issue `DELETED` events based on the set of `visited`
            // paths.
            if (dir.equals(path)) {
                for (var p : index.keySet()) {
                    if (p.startsWith(path) && !visited.contains(p)) {
                        events.add(new WatchEvent(WatchEvent.Kind.DELETED, p));
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
                        watch.handleEvent(created);
                    }
                } catch (IOException e) {
                    logger.error("Could not get modification time of: {} ({})", fullPath, e);
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
