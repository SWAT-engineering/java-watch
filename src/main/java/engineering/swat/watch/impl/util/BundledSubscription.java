package engineering.swat.watch.impl.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * This is an internal class where we can join multiple subscriptions to the same target by only taking 1 actual subscription but forwarding them to all the interested parties.
 * This is used (for example) to avoid multiple JDKPoller registries for the same path
 */
public class BundledSubscription<Key extends @NonNull Object, Event extends @NonNull Object> implements ISubscribable<Key,Event> {
    private static final Logger logger = LogManager.getLogger();
    private final ISubscribable<Key, Event> wrapped;
    private final ConcurrentMap<Key, Subscription<Event>> subscriptions = new ConcurrentHashMap<>();

    public BundledSubscription(ISubscribable<Key, Event> wrapped) {
        this.wrapped = wrapped;

    }

    private static class Subscription<R> implements Consumer<R> {
        private final List<Consumer<R>> consumers = new CopyOnWriteArrayList<>();
        private volatile @MonotonicNonNull Closeable toBeClosed;
        private volatile boolean closed = false;
        Subscription() {
        }

        void add(Consumer<R> newConsumer) {
            consumers.add(newConsumer);
        }

        void remove(Consumer<R> existingConsumer) {
            consumers.remove(existingConsumer);
        }

        @Override
        public void accept(R t) {
            for (var child: consumers) {
                child.accept(t);
            }
        }

        boolean hasActiveConsumers() {
            return !consumers.isEmpty();
        }
    }

    @Override
    public Closeable subscribe(Key target, Consumer<Event> eventListener) throws IOException {
        while (true) {
            Subscription<Event> active = this.subscriptions.computeIfAbsent(target, t -> new Subscription<>());
            // after this, there will only be 1 instance of active subscription in the map.
            // but we might have a race with remove, which can close the subscript between our get and our addition
            // since this code is very hard to get right without locks, and shouldn't be run too often
            // we take a big lock around the subscription management
            synchronized(active) {
                if (active.closed) {
                    // we lost the race with closing the subscription, so we retry
                    continue;
                }
                active.add(eventListener);
                if (active.toBeClosed == null) {
                    // the watch is not active yet, and we were the first to get the lock
                    active.toBeClosed = wrapped.subscribe(target, active);
                }
            }
            return () -> {
                boolean scheduleClose = false;
                synchronized(active) {
                    active.remove(eventListener);
                    scheduleClose = !active.hasActiveConsumers() && !active.closed;
                }
                if (scheduleClose) {
                    // to avoid hammering the system with closes & registers in a short periode
                    // we schedule the cleanup of watches in the background, when even after a small delay
                    // nobody is interested in a certain file anymore
                    CompletableFuture
                        .delayedExecutor(100, TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            synchronized(active) {
                                if (!active.hasActiveConsumers() && !active.closed) {
                                    // still ready to be closed
                                    active.closed = true;
                                    this.subscriptions.remove(target, active);
                                    if (active.toBeClosed != null) {
                                        try {
                                            active.toBeClosed.close();
                                        } catch (IOException e) {
                                            logger.error("Unhandled exception while closing the watcher for {} in the background", target, e);
                                        }
                                    }
                                }
                            }
                        });
                }
            };
        }
    }


}
