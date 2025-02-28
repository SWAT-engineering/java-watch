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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class MemorylessRescanner implements BiConsumer<EventHandlingWatch, WatchEvent> {
    private final Logger logger = LogManager.getLogger();
    private final Executor exec;

    public MemorylessRescanner(Executor exec) {
        this.exec = exec;
    }

    /**
     * Rescan all files in the scope of `watch` and issue `CREATED` and
     * `MODIFIED` events (not `DELETED` events) for each file. This method
     * should typically be executed asynchronously (using `exec`).
     */
    protected void rescan(EventHandlingWatch watch) {
        var start = watch.getPath();
        var options = EnumSet.noneOf(FileVisitOption.class);
        var maxDepth = watch.getScope() == WatchScope.PATH_AND_ALL_DESCENDANTS ? Integer.MAX_VALUE : 1;
        var visitor = new FileVisitor(watch);

        try {
            Files.walkFileTree(start, options, maxDepth, visitor);
        } catch (IOException e) {
            logger.error("Could not walk: {} ({})", start, e);
        }

        for (var e : visitor.getEvents()) {
            watch.handleEvent(e);
        }
    }

    private class FileVisitor extends SimpleFileVisitor<Path> {
        private final EventHandlingWatch watch;
        private final List<WatchEvent> events;

        public FileVisitor(EventHandlingWatch watch) {
            this.watch = watch;
            this.events = new ArrayList<>();
        }

        public List<WatchEvent> getEvents() {
            return events;
        }

        private void addEvents(Path path, BasicFileAttributes attrs) {
            events.add(newEvent(WatchEvent.Kind.CREATED, path));
            if (attrs.isRegularFile() && attrs.size() > 0) {
                events.add(newEvent(WatchEvent.Kind.MODIFIED, path));
            }
        }

        private WatchEvent newEvent(WatchEvent.Kind kind, Path fullPath) {
            var event = new WatchEvent(kind, fullPath);
            return watch.relativize(event);
        }

        // -- SimpleFileVisitor --

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (!watch.getPath().equals(dir)) {
                addEvents(dir, attrs);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            addEvents(file, attrs);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            logger.error("Could not generate events for file: {} ({})", file, exc);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
                logger.error("Could not successfully walk: {} ({})", dir, exc);
            }
            return FileVisitResult.CONTINUE;
        }
    }

    // -- BiConsumer --

    @Override
    public void accept(EventHandlingWatch watch, WatchEvent event) {
        if (event.getKind() == WatchEvent.Kind.OVERFLOW) {
            exec.execute(() -> rescan(watch));
        }
    }
}
