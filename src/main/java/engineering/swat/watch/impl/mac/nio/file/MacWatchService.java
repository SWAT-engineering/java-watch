package engineering.swat.watch.impl.mac.nio.file;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MacWatchService implements WatchService {
    private final BlockingQueue<MacWatchKey> pendingKeys = new LinkedBlockingQueue<>();
    private final Set<Thread> blockedThreads = ConcurrentHashMap.newKeySet();

    private volatile boolean closed = false;

    public boolean offer(MacWatchKey key) {
        return pendingKeys.offer(key);
    }

    public boolean isClosed() {
        return closed;
    }

    private void throwIfClosed() {
        if (isClosed()) {
            throw new ClosedWatchServiceException();
        }
    }

    private WatchKey throwIfClosedDuring(BlockingSupplier<WatchKey> supplier) throws InterruptedException {
        var t = Thread.currentThread();
        blockedThreads.add(t);
        throwIfClosed();
        try {
            return supplier.get();
        } catch (InterruptedException e) {
            throwIfClosed(); // TODO: Set interrupt status if this throw happens?

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

    // -- WatchService --

    @Override
    public void close() throws IOException {
        closed = true;
        for (var t : blockedThreads) {
            t.interrupt();
        }
    }

    @Override
    public WatchKey poll() {
        throwIfClosed();
        return pendingKeys.poll();
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        return throwIfClosedDuring(() -> pendingKeys.poll(timeout, unit));
    }

    @Override
    public WatchKey take() throws InterruptedException {
        return throwIfClosedDuring(pendingKeys::take);
    }
}
