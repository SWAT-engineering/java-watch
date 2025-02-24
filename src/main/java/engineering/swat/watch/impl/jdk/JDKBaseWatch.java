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
package engineering.swat.watch.impl.jdk;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.impl.EventHandlingWatch;

public abstract class JDKBaseWatch implements EventHandlingWatch {
    private final Logger logger = LogManager.getLogger();

    protected final Path path;
    protected final Executor exec;
    protected final Consumer<WatchEvent> eventHandler;
    protected final AtomicBoolean started = new AtomicBoolean();

    protected JDKBaseWatch(Path path, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.path = path;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    public void open() throws IOException {
        try {
            if (!startIfFirstTime()) {
                throw new IllegalStateException("Could not restart already-started watch for: " + path);
            }
            logger.debug("Started watch for: {}", path);
        } catch (Exception e) {
            throw new IOException("Could not start watch for: " + path, e);
        }
    }

    /**
     * Starts this watch.
     *
     * @throws IOException When an I/O exception of some sort has occurred
     * (e.g., a nested watch failed to start)
     */
    protected abstract void start() throws IOException;

    /**
     * Starts this watch if it's the first time.
     *
     * @return `true` iff it's the first time this method is called
     * @throws IOException When an I/O exception of some sort has occurred
     * (e.g., a nested watch failed to start)
     */
    protected boolean startIfFirstTime() throws IOException {
        if (started.compareAndSet(false, true)) {
            start();
            return true;
        } else {
            return false;
        }
    }

    protected WatchEvent translate(java.nio.file.WatchEvent<?> jdkEvent) {
        var kind = translate(jdkEvent.kind());
        var rootPath = path;
        var relativePath = kind == WatchEvent.Kind.OVERFLOW ? null : (@Nullable Path) jdkEvent.context();

        var event = new WatchEvent(kind, rootPath, relativePath);
        logger.trace("Translated: {} to {}", jdkEvent, event);
        return event;
    }

    private WatchEvent.Kind translate(java.nio.file.WatchEvent.Kind<?> jdkKind) {
        if (jdkKind == StandardWatchEventKinds.ENTRY_CREATE) {
            return WatchEvent.Kind.CREATED;
        }
        if (jdkKind == StandardWatchEventKinds.ENTRY_MODIFY) {
            return WatchEvent.Kind.MODIFIED;
        }
        if (jdkKind == StandardWatchEventKinds.ENTRY_DELETE) {
            return WatchEvent.Kind.DELETED;
        }
        if (jdkKind == StandardWatchEventKinds.OVERFLOW) {
            return WatchEvent.Kind.OVERFLOW;
        }

        throw new IllegalArgumentException("Unexpected watch kind: " + jdkKind);
    }

    // -- EventHandlingWatch --

    @Override
    public void handleEvent(WatchEvent e) {
        eventHandler.accept(e);
    }

    @Override
    public Path getPath() {
        return path;
    }
}
