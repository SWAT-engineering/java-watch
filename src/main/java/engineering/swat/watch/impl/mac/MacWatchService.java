package engineering.swat.watch.impl.mac;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.Nullable;

public class MacWatchService implements WatchService {
    protected final BlockingQueue<MacWatchKey> pendingKeys = new LinkedBlockingQueue<>();
    protected volatile boolean closed = false;

    public boolean offer(MacWatchKey key) {
        return pendingKeys.offer(key);
    }

    public boolean isClosed() {
        return closed;
    }

    protected void throwIfClosed() {
        if (isClosed()) {
            throw new ClosedWatchServiceException();
        }
    }

    // -- WatchService --

    @Override
    public void close() throws IOException {
        closed = true;
    }

    @Override
    public @Nullable WatchKey poll() {
        throwIfClosed();
        return pendingKeys.poll();
    }

    @Override
    public @Nullable WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        // We currently don't need/use blocking operations, so let's keep the
        // code simple for now.
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey take() throws InterruptedException {
        // We currently don't need/use blocking operations, so let's keep the
        // code simple for now.
        throw new UnsupportedOperationException();
    }
}
