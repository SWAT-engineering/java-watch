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
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;
import engineering.swat.watch.impl.util.BundledSubscription;
import engineering.swat.watch.impl.util.SubscriptionKey;

public class JDKDirectoryWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final boolean nativeRecursive;
    private volatile @MonotonicNonNull Closeable bundledJDKWatcher;
    private volatile boolean closed = false;

    private static final BundledSubscription<SubscriptionKey, List<java.nio.file.WatchEvent<?>>>
        BUNDLED_JDK_WATCHERS = new BundledSubscription<>(JDKPoller::register);

    public JDKDirectoryWatch(Path directory, Executor exec,
            BiConsumer<EventHandlingWatch, WatchEvent> eventHandler,
            Predicate<WatchEvent> eventFilter) {

        this(directory, exec, eventHandler, eventFilter, false);
    }

    public JDKDirectoryWatch(Path directory, Executor exec,
            BiConsumer<EventHandlingWatch, WatchEvent> eventHandler,
            Predicate<WatchEvent> eventFilter, boolean nativeRecursive) {

        super(directory, exec, eventHandler, eventFilter);
        this.nativeRecursive = nativeRecursive;
    }

    public boolean isClosed() {
        return closed;
    }

    private void handleJDKEvents(List<java.nio.file.WatchEvent<?>> events) {
        exec.execute(() -> {
            for (var ev : events) {
                try {
                    handleEvent(translate(ev));
                }
                catch (Throwable ignored) {
                    logger.error("Ignoring downstream exception:", ignored);
                }
            }
        });
    }

    // -- JDKBaseWatch --

    @Override
    public WatchScope getScope() {
        return nativeRecursive ? WatchScope.PATH_AND_ALL_DESCENDANTS : WatchScope.PATH_AND_CHILDREN;
    }

    @Override
    public void handleEvent(WatchEvent e) {
        if (!closed) {
            super.handleEvent(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed && bundledJDKWatcher != null) {
            logger.trace("Closing watch for: {}", this.path);
            closed = true;
            bundledJDKWatcher.close();
        }
    }

    @Override
    protected synchronized void start() throws IOException {
        assert bundledJDKWatcher == null;
        var key = new SubscriptionKey(path, nativeRecursive);
        bundledJDKWatcher = BUNDLED_JDK_WATCHERS.subscribe(key, this::handleJDKEvents);
    }
}
