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
        Subscription() {
        }

        public void setToBeClosed(Closeable closer) {
            this.toBeClosed = closer;
        }

        public void add(Consumer<R> newConsumer) {
            consumers.add(newConsumer);
        }

        public void remove(Consumer<R> existingConsumer) {
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
        var active = this.subscriptions.computeIfAbsent(target, t -> new Subscription<>());
        boolean first = false;
        if (active.toBeClosed == null) {
            // we just added a new one
            // so lets take a lock on it, and try to be the one that gets to initialize it
            synchronized(active) {
                // now lock on it to make sure nobo
                if (active.toBeClosed == null) {
                    first = true;
                    active.add(eventListener); // we know we already have the lock, and we need to do this before we register the watch
                    var newSubscriptions = wrapped.subscribe(target, active);
                    active.setToBeClosed(newSubscriptions);
                }
                else {
                }
            }
        }
        // at this point we have to be sure that we're not the first to in the list
        // since we might have won the race on the compute, but lost the race
        if (!first) {
            active.add(eventListener);
        }
        return () -> {
            active.remove(eventListener);
            if (!active.hasActiveConsumers()) {
                subscriptions.remove(target);
                if (active.hasActiveConsumers()) {
                    // we lost the race, someone else added something again
                    // so we put it back in the list
                    subscriptions.put(target, active);
                }
                else {
                    if (active.toBeClosed != null) {
                        active.toBeClosed.close();
                    }
                }
            }
        };

    }


}
