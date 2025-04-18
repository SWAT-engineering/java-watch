package engineering.swat.watch.impl.mac;

import java.io.IOException;
import java.nio.file.WatchKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Warning: This class is experimental, untested, and unused. We don't need
 * blocking operations currently, but it would be nice (?) to have them
 * eventually.
 */
public class MacBlockingWatchService extends MacWatchService {
    private final Set<Thread> blockedThreads = ConcurrentHashMap.newKeySet();

    private <K> K throwIfClosedDuring(BlockingSupplier<K> supplier) throws InterruptedException {
        var t = Thread.currentThread();
        blockedThreads.add(t);
        throwIfClosed();
        try {
            return supplier.get();
        } catch (InterruptedException e) {
            throwIfClosed();
            // If this service isn't closed yet, then definitely the interrupt
            // can't have originated from this service. Thus, re-throw it.
            throw e;
        } finally {
            blockedThreads.remove(t);
        }
    }

    @FunctionalInterface
    private static interface BlockingSupplier<T> {
        T get() throws InterruptedException;
    }

    // -- MacWatchService --

    @Override
    public void close() throws IOException {
        super.close();
        for (var t : blockedThreads) {
            t.interrupt();
        }
    }

    @Override
    public @Nullable WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        return this.<@Nullable WatchKey> throwIfClosedDuring(
            () -> pendingKeys.poll(timeout, unit));
    }

    @Override
    public WatchKey take() throws InterruptedException {
        return throwIfClosedDuring(pendingKeys::take);
    }
}
