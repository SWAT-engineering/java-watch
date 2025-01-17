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
        throwIfClosed();
        return withInterruptedExceptionHandler(() -> poll(timeout, unit));
    }

    @Override
    public WatchKey take() throws InterruptedException {
        throwIfClosed();
        return withInterruptedExceptionHandler(this::take);
    }

    private void throwIfClosed() {
        if (isClosed()) {
            throw new ClosedWatchServiceException();
        }
    }

    private WatchKey withInterruptedExceptionHandler(BlockingSupplier<WatchKey> supplier) throws InterruptedException {
        var t = Thread.currentThread();
        blockedThreads.add(t);
        try {
            return supplier.get();
        } catch (InterruptedException e) {
            throwIfClosed();
            throw e;
        } finally {
            blockedThreads.remove(t);
        }
    }

    @FunctionalInterface
    private static interface BlockingSupplier<T> {
        T get() throws InterruptedException;
    }
}
