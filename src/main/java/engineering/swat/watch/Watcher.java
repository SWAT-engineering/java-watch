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

    private static final Consumer<Path> NO_OP = p -> {};
    private static final Consumer<WatchEvent> NO_OP_WE = p -> {};

    private Consumer<Path> createHandler = NO_OP;
    private Consumer<Path> modifiedHandler = NO_OP;
    private Consumer<Path> deletedHandler = NO_OP;
    private Consumer<Path> overflowHandler = NO_OP;
    private Consumer<WatchEvent> eventHandler = NO_OP_WE;


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

    public Watcher onCreate(Consumer<Path> createHandler) {
        this.createHandler = createHandler;
        return this;
    }

    public Watcher onModified(Consumer<Path> changeHandler) {
        this.modifiedHandler = changeHandler;
        return this;
    }

    public Watcher onDeleted(Consumer<Path> removeHandler) {
        this.deletedHandler = removeHandler;
        return this;
    }

    public Watcher onOverflow(Consumer<Path> overflowHandler) {
        this.overflowHandler = overflowHandler;
        return this;
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
                var result = new JDKDirectoryWatcher(path, executor, this::handleEvent);
                result.start();
                return result;
            }
            case RECURSIVE_DIRECTORY: {
                var result = new JDKRecursiveDirectoryWatcher(path, executor, this::handleEvent);
                result.start();
                return result;
            }
            case FILE:
            default:
                throw new IllegalArgumentException("Not supported yet");
        }
    }

    private void handleEvent(WatchEvent ev) {
        switch (ev.getKind()) {
            case CREATED:
                createHandler.accept(ev.calculateFullPath());
                break;
            case DELETED:
                deletedHandler.accept(ev.calculateFullPath());
                break;
            case MODIFIED:
                modifiedHandler.accept(ev.calculateFullPath());
                break;
            case OVERFLOW:
                overflowHandler.accept(ev.calculateFullPath());
                break;
        }
        eventHandler.accept(ev);
    }
}
