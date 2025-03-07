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
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class MemorylessRescanner implements BiConsumer<EventHandlingWatch, WatchEvent> {
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
        var generator = newGenerator(watch.getPath(), watch.getScope());
        generator.walkFileTree();
        generator.eventStream()
            .map(watch::relativize)
            .forEach(watch::handleEvent);
    }

    protected Generator newGenerator(Path path, WatchScope scope) {
        return new Generator(path, scope);
    }

    protected class Generator extends BaseFileVisitor {
        protected final List<WatchEvent> events = new ArrayList<>();

        public Generator(Path path, WatchScope scope) {
            super(path, scope);
        }

        public Stream<WatchEvent> eventStream() {
            return events.stream();
        }

        protected void generateEvents(Path path, BasicFileAttributes attrs) {
            events.add(new WatchEvent(WatchEvent.Kind.CREATED, path));
            if (attrs.isRegularFile() && attrs.size() > 0) {
                events.add(new WatchEvent(WatchEvent.Kind.MODIFIED, path));
            }
        }

        // -- BaseFileVisitor --

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (!path.equals(dir)) {
                generateEvents(dir, attrs);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            generateEvents(file, attrs);
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
