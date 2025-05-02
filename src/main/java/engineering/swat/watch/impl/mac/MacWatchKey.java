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

class MacWatchKey implements WatchKey {
    private final MacWatchable watchable;
    private final MacWatchService service;
    private final PendingEvents pendingEvents;
    private final NativeEventStream stream;

    private volatile Configuration config = new Configuration();
    private volatile boolean cancelled = false;

    MacWatchKey(MacWatchable watchable, MacWatchService service) throws IOException {
        this.watchable = watchable;
        this.service = service;
        this.pendingEvents = new PendingEvents();
        this.stream = new NativeEventStream(watchable.getPath(), new OfferWatchEvent());
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
    MacWatchKey initialize(Kind<?>[] kinds, Modifier[] modifiers) throws IOException {
        if (isValid()) {
            config = new Configuration(kinds, modifiers);
            stream.open();
        }
        return this;
    }

    /**
     * Auxiliary container to manage the internal state of this watch key in a
     * single place (to make it easier to reason about concurrent accesses).
     */
    private class PendingEvents {
        private final BlockingQueue<WatchEvent<?>> pendingEvents = new LinkedBlockingQueue<>();
        private volatile boolean signalled = false;

        // Following the documentation `WatchKey`, initially, this watch key is
        // *ready* (i.e., `signalled` is false). When an event is offered, this
        // watch key becomes *signalled* and is enqueued at `service`.
        // Subsequently, this watch key remains signalled until it is reset; not
        // until the pending events are polled. Thus, at the same time,
        // `pendingEvents` can be empty and `signalled` can be true. The
        // interplay between `pendingEvents` and `signalled` is quite tricky,
        // and potentially subject to harmful races. The comments below the
        // following methods argue why such harmful races won't happen.

        void offerAndSignal(WatchEvent<?> event) {
            pendingEvents.offer(event);
            if (!signalled) {
                signalled = true;
                service.offer(MacWatchKey.this);
            }
        }

        List<WatchEvent<?>> drain() {
            var list = new ArrayList<WatchEvent<?>>(pendingEvents.size());
            pendingEvents.drainTo(list);
            return list;
        }

        void resignalIfNonEmpty() {
            if (signalled && !pendingEvents.isEmpty()) {
                service.offer(MacWatchKey.this);
            } else {
                signalled = false;
            }
        }

        // The crucial property that needs to be maintained is that when
        // `resignalIfNonEmpty` returns, either this watch key has been, or will
        // be, enqueued at `service`, or `signalled` is false. Otherwise, until
        // a next invocation of `reset` (including `resignalIfNonEmpty`),
        // consumers of `service` won't be able to dequeue this watch key (it
        // won't be queued by `offerAndSignal` while `signalled` is true), even
        // when `pendingEvents` becomes non-empty---this causes consumers to
        // miss events. Note: The documentation of `WatchService` doesn't
        // specify the need for a next invocation of `reset` after a succesful
        // one.
        //
        // To argue that the property holds, there are two cases to analyze:
        //
        //    - If the then-branch of `resignalIfNonEmpty` is executed, then
        //      this watch key has been enqueued at `service`, so the property
        //      holds. Note: It doesn't matter if, by the time
        //      `resignalIfNonEmpty` returns, this watch key has already been
        //      dequeued by another thread. This is because that other thread is
        //      then responsible to make a next invocation of `reset` (including
        //      `resignalIfNonEmpty`) after its usage of this watch key.
        //
        //    - If the else-branch of `resignalIfNonEmpty` is executed, then
        //      `signalled` may become `true` right after it's set to `false`.
        //      This happens when another thread concurrently invokes
        //      `offerAndSignal`. (There are no other places where `signalled`
        //      is modified.) But then, as part of `offerAndSignal`, this watch
        //      key will be enqueued at `service` by the other thread, too, so
        //      the property holds. Note: If we were to change the order of the
        //      statements in `offerAndSignal`, the property no longer holds.
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

                pendingEvents.offerAndSignal(event);
            }
        }
    }

    /**
     * Configuration of a watch key that affects which events to
     * {@link #ignore(Kind, Path)}.
     */
    private static class Configuration {
        private final Kind<?>[] kinds;
        private final boolean watchFileTree;

        public Configuration() {
            this(new Kind<?>[0], new Modifier[0]);
        }

        public Configuration(Kind<?>[] kinds, Modifier[] modifiers) {
            // Extract only the relevant information from `modifiers`
            var watchFileTree = false;
            for (var m : modifiers) {
                watchFileTree |= m == ExtendedWatchEventModifier.FILE_TREE;
            }

            this.kinds = Arrays.copyOf(kinds, kinds.length);
            this.watchFileTree = watchFileTree;
        }

        /**
         * Tests if an event should be ignored by a watch key with this
         * configuration. This is the case when one of the following is true:
         * (a) the watch key isn't configured to watch events of the given
         * {@code kind}; (b) the watch key is configured to watch only a single
         * directory, but the given {@code context} (a {@code Path}) points to a
         * file in a subdirectory.
         */
        public boolean ignore(Kind<?> kind, @Nullable Object context) {
            for (var k : kinds) {
                if (k == kind) {
                    if (watchFileTree) {
                        return false;
                    } else { // Watch a single directory
                        return context instanceof Path &&
                            ((Path) context).getNameCount() > 1; // File in subdirectory
                    }
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
        return pendingEvents.drain();
    }

    @Override
    public boolean reset() {
        if (!isValid()) {
            return false;
        }

        pendingEvents.resignalIfNonEmpty();

        // Invalidation of this key *during* the invocation of this method is
        // observationally equivalent to invalidation immediately *after*. Thus,
        // assume it doesn't happen and return `true`.
        return true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        watchable.unregister(service);
        stream.close();
    }

    @Override
    public Watchable watchable() {
        return watchable;
    }
}
