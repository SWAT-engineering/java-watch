package engineering.swat.watch;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.impl.JDKDirectoryWatcher;
import engineering.swat.watch.impl.JDKRecursiveDirectoryWatcher;

public class Watcher {
    private final Logger logger = LogManager.getLogger();
    private final WatcherKind kind;
    private final Path path;
    private Executor executor = CompletableFuture::runAsync;

    private Consumer<WatchEvent> eventHandler = p -> {};


    private Watcher(WatcherKind kind, Path path) {
        this.kind = kind;
        this.path = path;
        logger.info("Constructor logger for: {} at {} level", path, kind);
    }

    private enum WatcherKind {
        FILE,
        DIRECTORY,
        RECURSIVE_DIRECTORY
    }

    public static Watcher singleFile(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("We can only watch absolute paths");
        }
        return new Watcher(WatcherKind.FILE, path);
    }

    public static Watcher singleDirectory(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("We can only watch absolute paths");
        }
        return new Watcher(WatcherKind.DIRECTORY, path);
    }

    public static Watcher recursiveDirectory(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("We can only watch absolute paths");
        }
        return new Watcher(WatcherKind.RECURSIVE_DIRECTORY, path);
    }

    public Watcher onEvent(Consumer<WatchEvent> eventHandler) {
        this.eventHandler = eventHandler;
        return this;
    }

    public Watcher withExecutor(Executor callbackHandler) {
        this.executor = callbackHandler;
        return this;
    }

    public Closeable start() throws IOException {
        switch (kind) {
            case DIRECTORY: {
                var result = new JDKDirectoryWatcher(path, executor, this.eventHandler);
                result.start();
                return result;
            }
            case RECURSIVE_DIRECTORY: {
                var result = new JDKRecursiveDirectoryWatcher(path, executor, this.eventHandler);
                result.start();
                return result;
            }
            case FILE:
            default:
                throw new IllegalArgumentException("Not supported yet");
        }
    }

}
