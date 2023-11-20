package engineering.swat.watch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public class Watcher {
    private final WatcherKind kind;
    private final Path path;
    private Executor executor = CompletableFuture::runAsync;

    private static final Consumer<Path> NO_OP = p -> {};

    private Consumer<Path> createHandler = NO_OP;
    private Consumer<Path> changeHandler = NO_OP;
    private Consumer<Path> removeHandler = NO_OP;


    private Watcher(WatcherKind kind, Path path) {
        this.kind = kind;
        this.path = path;
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

    public Watcher onChange(Consumer<Path> changeHandler) {
        this.changeHandler = changeHandler;
        return this;
    }

    public Watcher onRemove(Consumer<Path> removeHandler) {
        this.removeHandler = removeHandler;
        return this;
    }

    public WatchSubscription start() {
        return null;
    }



}
