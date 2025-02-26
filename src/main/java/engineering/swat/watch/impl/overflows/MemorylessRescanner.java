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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

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
        try (var content = contentOf(watch.getPath(), watch.getScope())) {
            content
                .flatMap(this::generateEvents) // Paths aren't properly relativized yet...
                .map(watch::relativize)        // ...so they must be relativized first (wrt the root path of `watch`)
                .forEach(watch::handleEvent);
        }
    }

    protected Stream<Path> contentOf(Path path, WatchScope scope) {
        try {
            var maxDepth = scope == WatchScope.PATH_AND_ALL_DESCENDANTS ? Integer.MAX_VALUE : 1;
            return Files.walk(path, maxDepth).filter(p -> p != path);
        } catch (IOException e) {
            logger.error("Could not walk: {} ({})", path, e);
            return Stream.empty();
        }
    }

    protected Stream<WatchEvent> generateEvents(Path path) {
        try {
            var created = new WatchEvent(WatchEvent.Kind.CREATED, path);
            if (Files.size(path) == 0) {
                return Stream.of(created);
            } else {
                var modified = new WatchEvent(WatchEvent.Kind.MODIFIED, path);
                return Stream.of(created, modified);
            }
        } catch (IOException e) {
            logger.error("Could not generate events for: {} ({})", path, e);
            return Stream.empty();
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
