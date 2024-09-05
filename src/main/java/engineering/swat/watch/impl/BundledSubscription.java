package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class BundledSubscription<A, R> implements ISubscribable<A,R> {
    private final ISubscribable<A, R> around;
    private final Map<A, Subscription<R>> subscriptions = new ConcurrentHashMap<>();

    public BundledSubscription(ISubscribable<A, R> around) {
        this.around = around;

    }

    private static class Subscription<R> implements Consumer<R> {
        private final List<Consumer<R>> consumers = new CopyOnWriteArrayList<>();
        private volatile @MonotonicNonNull Closeable closer;
        Subscription(Consumer<R> initialConsumer) {
            consumers.add(initialConsumer);
        }

        public void setCloser(Closeable closer) {
            this.closer = closer;
        }

        void add(Consumer<R> newConsumer) {
            consumers.add(newConsumer);
        }

        synchronized boolean remove(Consumer<R> existingConsumer) {
            consumers.remove(existingConsumer);
            return consumers.isEmpty();
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
    public Closeable subscribe(A target, Consumer<R> eventListener) throws IOException {
        var active = this.subscriptions.get(target);
        if (active == null) {
            active = new Subscription<>(eventListener);
            var newSubscriptions = around.subscribe(target, active);
            active.setCloser(newSubscriptions);
            var lostRace = this.subscriptions.putIfAbsent(target, active);
            if (lostRace != null) {
                try {
                    newSubscriptions.close();
                } catch (IOException _ignore) {
                    // ignore
                }
                lostRace.add(eventListener);
                active = lostRace;
            }
        }
        else {
            active.add(eventListener);
        }
        var finalActive = active;
        return () -> {
            if (finalActive.remove(eventListener)) {
                subscriptions.remove(target);
                if (finalActive.hasActiveConsumers()) {
                    // we lost the race, someone else added something again
                    // so we put it back in the list
                    subscriptions.put(target, finalActive);
                }
                else {
                    finalActive.closer.close();
                }
            }
        };

    }


}
