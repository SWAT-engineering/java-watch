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
package engineering.swat.watch.impl.mac;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.sun.nio.file.ExtendedWatchEventModifier;

public class MacWatchKey implements WatchKey {
    private final MacWatchable watchable;
    private final MacWatchService service;
    private final BlockingQueue<WatchEvent<?>> pendingEvents;

    private volatile @Nullable NativeEventStream stream;
    private volatile Configuration config = new Configuration();
    private volatile boolean signalled = false;
    private volatile boolean cancelled = false;

    public MacWatchKey(MacWatchable watchable, MacWatchService service) {
        this.watchable = watchable;
        this.service = service;
        this.pendingEvents = new LinkedBlockingQueue<>();
    }

    /**
     * Initializes this watch key by: (1) configuring it with the given
     * {@code kinds} and {@code modifiers}; (2) opening a native event stream,
     * if none is open yet for this watch key. This method can be invoked
     * multiple times. If this watch key is invalid, then invoking this method
     * has no effect.
     *
     * @return This watch key
     */
    public MacWatchKey initialize(Kind<?>[] kinds, Modifier[] modifiers) throws IOException {
        if (isValid()) {
            config = new Configuration(kinds, modifiers);
            openStream();
        }
        return this;
    }

    private void signalWhen(boolean condition) {
        if (condition) {
            signalled = true;
            service.offer(this);
            // The order of these statements is important. If it's the other way
            // around, then the following harmful interleaving of an "offering
            // thread" (Thread 1) and a "polling thread" (Thread 2) can happen:
            //   - Thread 1:
            //       - `handle`: Add event to `pendingEvents`
            //       - `handle`, `signalWhen`: Test `!signalled` is true
            //       - `signalWhen`: Offer `this` to `service`.
            //   - Thread 2:
            //       - `MacWatchService.poll`: Poll `this` from `service`
            //       - `pollEvents`: Drain events from `pendingEvents`
            //       - `reset`: Set `signalled` to false []
            //       - `reset`, `signalWhen`: Test `!pendingEvents.empty()` is false
            //   - Thread 1:
            //       - `signalWhen`: Set `signalled` to true. At this point
            //         `this` isn't offered to `service`, but subsequent
            //         invocations of `handle` will not cause `this` to be
            //         offered. As a result, no subsequent events are
            //         propagated.
        }
    }

    // The following two methods synchronize on this object to avoid races. The
    // synchronization overhead is expected to be negligible, as these methods
    // are expected to be rarely invoked.

    private synchronized void openStream() throws IOException {
        if (stream == null) {
            stream = new NativeEventStream(watchable.getPath(), new OfferWatchEvent());
            stream.open();
        }
    }

    private synchronized void closeStream() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    /**
     * Handler for native events, issued by macOS. When invoked, it checks if
     * the native event is eligible for downstream consumption, creates and
     * enqueues a {@link WatchEvent}, and signals the service (when needed).
     */
    private class OfferWatchEvent implements NativeEventHandler {
        @Override
        public <T> void handle(Kind<T> kind, T context) {
            if (!cancelled && !config.ignore(kind, context)) {

                var event = new WatchEvent<T>() {
                    @Override
                    public Kind<T> kind() {
                        return kind;
                    }
                    @Override
                    public int count() {
                        // We currently don't need/use event counts, so let's
                        // keep the code simple for now.
                        throw new UnsupportedOperationException();
                    }
                    @Override
                    public T context() {
                        return context;
                    }
                };

                pendingEvents.offer(event);
                signalWhen(!signalled);
            }
        }
    }

    /**
     * Configuration of a watch key that affects which events to
     * {@link #ignore(Kind, Path)}.
     */
    private static class Configuration {
        private final Kind<?>[] kinds;
        private final boolean singleDirectory;

        public Configuration() {
            this(new Kind<?>[0], new Modifier[0]);
        }

        public Configuration(Kind<?>[] kinds, Modifier[] modifiers) {
            // Extract only the relevant information from `modifiers`
            var fileTree = false;
            for (var m : modifiers) {
                fileTree |= m == ExtendedWatchEventModifier.FILE_TREE;
            }

            this.kinds = Arrays.copyOf(kinds, kinds.length);
            this.singleDirectory = !fileTree;
        }

        /**
         * Tests if an event should be ignored by a watch key with this
         * configuration. This is the case when one of the following is true:
         * (a) the watch key isn't configured to watch events of the given
         * {@code kind}; (b) the watch key is configured to watch only the root
         * level of a file tree, but the given {@code context} (a {@code Path})
         * points to a non-root level.
         */
        public boolean ignore(Kind<?> kind, @Nullable Object context) {
            for (var k : kinds) {
                if (k == kind) {
                    return singleDirectory &&
                        context instanceof Path && ((Path) context).getNameCount() > 1;
                }
            }
            return true;
        }
    }

    // -- WatchKey --

    @Override
    public boolean isValid() {
        return !cancelled && !service.isClosed();
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
        var list = new ArrayList<WatchEvent<?>>(pendingEvents.size());
        pendingEvents.drainTo(list);
        return list;
    }

    @Override
    public boolean reset() {
        if (!isValid()) {
            return false;
        }

        signalled = false;
        signalWhen(!pendingEvents.isEmpty());

        // Invalidation of this key *during* the invocation of this method is
        // observationally equivalent to invalidation immediately *after*. Thus,
        // assume it doesn't happen and return `true`.
        return true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        watchable.unregister(service);
        closeStream();
    }

    @Override
    public Watchable watchable() {
        return watchable;
    }
}
