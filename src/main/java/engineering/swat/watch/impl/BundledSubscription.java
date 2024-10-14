package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * This is an internal class where we can join multiple subscriptions to the same target by only taking 1 actual subscription but forwarding them to all the interested parties.
 * This is used (for example) to avoid multiple JDKPoller registries for the same path
 */
public class BundledSubscription<Key extends @NonNull Object, Event extends @NonNull Object> implements ISubscribable<Key,Event> {
    private final ISubscribable<Key, Event> wrapped;
    private final ConcurrentMap<Key, Subscription<Event>> subscriptions = new ConcurrentHashMap<>();

    public BundledSubscription(ISubscribable<Key, Event> wrapped) {
        this.wrapped = wrapped;

    }

    private static class Subscription<R> implements Consumer<R> {
        private final List<Consumer<R>> consumers = new CopyOnWriteArrayList<>();
        private volatile @MonotonicNonNull Closeable toBeClosed;
        private volatile boolean closed = false;
        Subscription(Consumer<R> initial) {
            consumers.add(initial);
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

        boolean firstIs(Consumer<R> eventListener) {
            return consumers.stream().findFirst().orElse(null) == eventListener;
        }


    }

    @Override
    public Closeable subscribe(Key target, Consumer<Event> eventListener) throws IOException {
        while (true) {
            Subscription<Event> active = this.subscriptions.computeIfAbsent(target, t -> new Subscription<>(eventListener));
            // after this, there will only be 1 instance of active subscription in the map.
            // but we might have a race with remove, which can close the subscript between our get and our addition
            if (active.toBeClosed == null) {
                // we might be the first
                synchronized(active) {
                    if (active.toBeClosed == null) {
                        // we're the first here, so we can't be closed
                        // let's register ourselves
                        active.toBeClosed = (wrapped.subscribe(target, active));
                    }
                }
            }
            if (!active.firstIs(eventListener)) {
                // we weren't the one that got the compute action
                // so we'll add ourselves to the list
                //
                active.add(active);
            }
            if (active.closed) {
                // we tried, but we lost the race to add something to the list of subscriptions before we got closed
                continue;
            }
            return () -> {
                active.remove(eventListener);
                if (!active.hasActiveConsumers()) {
                    // we might be able to close it
                    // let's try to lock us down.
                    // just so that there are no 2 threads closing it
                    // and a bit so that there is no thread also just starting to register it
                    synchronized (active) {
                        if (!active.hasActiveConsumers() && !active.closed) {
                            // okay, it's still legal to close this one
                            // and no other thread is closing it
                            // so we're going to remove it from the map


                            // TODO: still a race! since beween line 95 and the one below, the line 83 can have been checked.
                            // maybe use atomic boolean to get this logic better?
                            active.closed = true;
                            subscriptions.remove(target, active);

                            if (active.toBeClosed != null) {
                                active.toBeClosed.close();
                            }
                        }
                    }
                }
            };
        }


    }


}
