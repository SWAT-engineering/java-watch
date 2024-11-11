package engineering.swat.watch.impl.jdk;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

/**
 * It's not possible to monitor a single file (or directory), so we have to find a directory watcher, and connect to that
 *
 * Note that you should take care to call start only once.
 */
public class JDKFileWatcher implements ActiveWatch {
    private final Logger logger = LogManager.getLogger();
    private final Path file;
    private final Path fileName;
    private final Executor exec;
    private final Consumer<WatchEvent> eventHandler;
    private volatile @MonotonicNonNull Closeable activeWatch;

    public JDKFileWatcher(Path file, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.file = file;
        Path filename= file.getFileName();
        if (filename == null) {
            throw new IllegalArgumentException("Cannot pass in a root path");
        }
        this.fileName = filename;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    /**
     * Start the file watcher, but only do it once
     * @throws IOException
     */
    public void start() throws IOException {
        try {
            var dir = file.getParent();
            if (dir == null) {
                throw new IllegalArgumentException("cannot watch a single entry that is on the root");

            }
            assert !dir.equals(file);
            JDKDirectoryWatcher parentWatch;
            synchronized(this) {
                if (activeWatch != null) {
                    throw new IOException("Cannot start an already started watch");
                }
                activeWatch = parentWatch = new JDKDirectoryWatcher(dir, exec, this::filter);
                parentWatch.start();
            }
            logger.debug("Started file watch for {} (in reality a watch on {}): {}", file, dir, parentWatch);

        } catch (IOException e) {
            throw new IOException("Could not register file watcher for: " + file, e);
        }
    }

    private void filter(WatchEvent event) {
        if (fileName.equals(event.getRelativePath())) {
            eventHandler.accept(event);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (activeWatch != null) {
            activeWatch.close();
        }
    }
}
