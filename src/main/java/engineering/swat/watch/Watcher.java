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

public class Watcher {
    private final Logger logger = LogManager.getLogger();
    private final WatcherKind kind;
    private final Path path;
    private Executor executor = CompletableFuture::runAsync;

    private static final Consumer<Path> NO_OP = p -> {};

    private Consumer<Path> createHandler = NO_OP;
    private Consumer<Path> modifiedHandler = NO_OP;
    private Consumer<Path> deletedHandler = NO_OP;


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

    public Watcher withExecutor(Executor callbackHandler) {
        this.executor = callbackHandler;
        return this;
    }

    public Closeable start() throws IOException {
        switch (kind) {
            case DIRECTORY:
                var result = new JDKDirectoryWatcher(path, executor, this::handleEvent);
                result.start();
                return result;
            case FILE:
            case RECURSIVE_DIRECTORY:
            default:
                throw new IllegalArgumentException("Not supported yet");
        }
    }

    private void handleEvent(WatchEvent ev) {
        switch (ev.getKind()) {
            case CREATED:
                callIfDefined(createHandler, ev);
                break;
            case DELETED:
                callIfDefined(deletedHandler, ev);
                break;
            case MODIFIED:
                callIfDefined(modifiedHandler, ev);
                break;
        }
    }

    private void callIfDefined(Consumer<Path> target, WatchEvent ev) {
        if (target != NO_OP) {
            executor.execute(() -> target.accept(ev.calculateFullPath()));
        }
    }
}
