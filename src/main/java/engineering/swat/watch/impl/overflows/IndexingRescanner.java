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
        rescan(path, scope); // Make an initial scan to populate the index
    }

    // -- MemorylessRescanner --

    @Override
    protected MemorylessRescanner.FileVisitor newFileVisitor() {
        return new FileVisitor();
    }

    protected class FileVisitor extends MemorylessRescanner.FileVisitor {
        // Field to keep track of the paths that are visited during the current
        // rescan. Subsequently, the `DELETED` events since the previous rescan
        // can be approximated.
        private Set<Path> visited = new HashSet<>();

        @Override
        protected void addEvents(Path path, BasicFileAttributes attrs) {
            visited.add(path);
            var lastModifiedTimeOld = index.get(path);
            var lastModifiedTimeNew = attrs.lastModifiedTime();

            // The path isn't indexed yet
            if (lastModifiedTimeOld == null) {
                index.put(path, lastModifiedTimeNew);
                super.addEvents(path, attrs);
            }

            // The path is already indexed, and the previous last-modified-time
            // is older than the current last-modified-time
            else if (lastModifiedTimeOld.compareTo(lastModifiedTimeNew) < 0) {
                index.put(path, lastModifiedTimeNew);
                events.add(new WatchEvent(WatchEvent.Kind.MODIFIED, path));
            }
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            // If the visitor is back at the root of the rescan, then the time
            // is right to issue `DELETED` events based on the set of `visited`
            // paths.
            if (dir.equals(start)) {
                var i = index.keySet().iterator();
                while (i.hasNext()) {
                    var p = i.next();
                    if (p.startsWith(start) && !visited.contains(p)) {
                        events.add(new WatchEvent(WatchEvent.Kind.DELETED, p));
                        i.remove(); // Remove `p` from `index`
                    }
                }
            }

            return super.postVisitDirectory(dir, exc);
        }
    }

    @Override
    public void accept(EventHandlingWatch watch, WatchEvent event) {
        // Auto-handle `OVERFLOW` events
        super.accept(watch, event);

        // In addition to auto-handling `OVERFLOW` events, extra processing is
        // needed to update the index when `CREATED`, `MODIFIED`, and `DELETED`
        // events happen.
        var fullPath = event.calculateFullPath();
        switch (event.getKind()) {
            case MODIFIED:
                // If a `MODIFIED` event happens for a path that's not in the
                // index yet, then a `CREATED` event has somehow been missed.
                // Just in case, it's issued synthetically here.
                if (!index.containsKey(fullPath)) {
                    var created = new WatchEvent(WatchEvent.Kind.CREATED, fullPath);
                    watch.handleEvent(created);
                }
                // Fallthrough intended
            case CREATED:
                try {
                    index.put(fullPath, Files.getLastModifiedTime(fullPath));
                } catch (IOException e) {
                    logger.error("Could not get modification time of: {} ({})", fullPath, e);
                }
                break;
            case DELETED:
                index.remove(fullPath);
                break;
            default:
                logger.error("Could not auto-handle event of kind: {}", event.getKind());
                break;
        }
    }
}
