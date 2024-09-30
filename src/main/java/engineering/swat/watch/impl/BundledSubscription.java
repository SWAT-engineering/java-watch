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
        var retry = false; // The call might need to be retried when another thread interferes

        var active = subscriptions.computeIfAbsent(target, t -> new Subscription<>());
        synchronized (active) {

            // "Good" case: No other thread has modified the subscription of
            // `target` between getting `active` and entering this synchronized
            // block, so the reference held by `active` is still fresh enough
            if (active == subscriptions.get(target)) {
                active.add(eventListener);
                if (active.toBeClosed == null) {
                    var newSubscriptions = wrapped.subscribe(target, active);
                    active.setToBeClosed(newSubscriptions);
                    // The next thread that enters the synchronized block is now
                    // disabled from re-registering
                }
            }

            // "Bad" case: Another thread had modified the subscription of
            // `target`, so the reference held by `active` has become stale.
            // Thus, the call needs to be retried.
            else {
                retry = true;
            }
        }

        if (retry) {
            return subscribe(target, eventListener);
        } else {
            return () -> {
                synchronized (active) {
                    active.remove(eventListener);
                    if (!active.hasActiveConsumers()) {
                        subscriptions.remove(target);

                        // By removing the subscription of `target`, all aliases
                        // of `active` in other threads have become stale and
                        // shouldn't be used by those threads anymore. Only when
                        // this non-usage can be guaranteed, is it safe to close
                        // `active`.
                        assert active.toBeClosed != null;
                        active.toBeClosed.close();
                    }
                }
            };
        }
    }
}
