package engineering.swat.watch.impl.mac.jni;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.List;

/**
 * Start a recursive watch on a folder
 */
public class FileSystemEvents implements Closeable {
    static {
        NativeLibrary.load();
    }

    private native long start(String path, Runnable signal);
    private native void stop(long watchId);
    private native boolean anyEvents(long watchId);
    private native List<WatchEvent<?>> pollEvents(long watchId);


    private volatile boolean closed = false;
    private final long activeWatch;

    public FileSystemEvents(Path path, Runnable notifyNewEvents) {
        activeWatch = start(path.toString(), notifyNewEvents);
    }

    public boolean anyEvents() throws IOException {
        if (closed) {
            throw new IOException("Watch is already closed");
        }
        return anyEvents(activeWatch);
    }

    public List<WatchEvent<?>> pollEvents() throws IOException {
        if (closed) {
            throw new IOException("Watch is already closed");
        }
        return pollEvents(activeWatch);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        stop(activeWatch);
    }


}
