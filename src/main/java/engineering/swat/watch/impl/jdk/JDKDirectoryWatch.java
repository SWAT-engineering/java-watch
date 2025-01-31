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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.impl.util.BundledSubscription;
import engineering.swat.watch.impl.util.SubscriptionKey;

public class JDKDirectoryWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private volatile @MonotonicNonNull Closeable bundledWatch;
    private final boolean nativeRecursive;

    private static final BundledSubscription<SubscriptionKey, List<java.nio.file.WatchEvent<?>>>
        BUNDLED_JDK_WATCHERS = new BundledSubscription<>(JDKPoller::register);

    public JDKDirectoryWatch(Path directory, Executor exec, Consumer<WatchEvent> eventHandler) {
        this(directory, exec, eventHandler, false);
    }

    public JDKDirectoryWatch(Path directory, Executor exec, Consumer<WatchEvent> eventHandler, boolean nativeRecursive) {
        super(directory, exec, eventHandler);
        this.nativeRecursive = nativeRecursive;
    }

    private void handleChanges(List<java.nio.file.WatchEvent<?>> events) {
        exec.execute(() -> {
            for (var ev : events) {
                try {
                    eventHandler.accept(translate(ev));
                }
                catch (Throwable ignored) {
                    logger.error("Ignoring downstream exception:", ignored);
                }
            }
        });
    }

    private WatchEvent translate(java.nio.file.WatchEvent<?> ev) {
        WatchEvent.Kind kind;
        if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
            kind = WatchEvent.Kind.CREATED;
        }
        else if (ev.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
            kind = WatchEvent.Kind.MODIFIED;
        }
        else if (ev.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            kind = WatchEvent.Kind.DELETED;
        }
        else if (ev.kind() == StandardWatchEventKinds.OVERFLOW) {
            kind = WatchEvent.Kind.OVERFLOW;
        }
        else {
            throw new IllegalArgumentException("Unexpected watch event: " + ev);
        }
        var path = kind == WatchEvent.Kind.OVERFLOW ? this.path : (@Nullable Path)ev.context();
        logger.trace("Translated: {} to {} at {}", ev, kind, path);
        return new WatchEvent(kind, path, path);
    }

    // -- JDKBaseWatch --

    @Override
    public synchronized void close() throws IOException {
        if (bundledWatch != null) {
            logger.trace("Closing watch for: {}", this.path);
            bundledWatch.close();
        }
    }

    @Override
    protected synchronized boolean runIfFirstTime() throws IOException {
        if (bundledWatch != null) {
            return false;
        }

        var key = new SubscriptionKey(path, nativeRecursive);
        bundledWatch = BUNDLED_JDK_WATCHERS.subscribe(key, this::handleChanges);
        return true;
    }
}
