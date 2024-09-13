package engineering.swat.watch;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.impl.JDKDirectoryWatcher;
import engineering.swat.watch.impl.JDKFileWatcher;
import engineering.swat.watch.impl.JDKRecursiveDirectoryWatcher;

/**
 * Watch a path for changes.
 *
 * It will avoid common errors using the raw apis, and will try to use the most native api where possible.
 * Note, there are differences per platform that cannot be avoided, please review the readme of the library.
 */
public class Watcher {
    private final Logger logger = LogManager.getLogger();
    private final WatcherKind kind;
    private final Path path;
    private Executor executor = CompletableFuture::runAsync;

    private static final Consumer<WatchEvent> NULL_HANDLER = p -> {};
    private Consumer<WatchEvent> eventHandler = NULL_HANDLER;


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

    /**
     * Request a watcher for a single path (file or directory).
     * If it's a file, depending on the platform this will watch the whole directory and filter the results, or only watch a single file.
     * @param path a single path entry, either a file or a directory
     * @return a watcher that only fires events related to the requested path
     * @throws IOException in case the path is not absolute
     */
    public static Watcher single(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("We can only watch absolute paths");
        }
        return new Watcher(WatcherKind.FILE, path);
    }

    /**
     * Request a watcher for a directory, getting events for its direct children.
     * @param path a directory to monitor for changes
     * @return a watcher that fires events for any of the direct children (and its self).
     * @throws IOException in cas the path is not absolute or it's not an directory
     */
    public static Watcher singleDirectory(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("We can only watch absolute paths");
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Only directories are supported");
        }
        return new Watcher(WatcherKind.DIRECTORY, path);
    }

    /**
     * Request a watcher for a directory, getting events for all of its children. Even those added after the watch started.
     * On some platforms, this can be quite expansive, so be sure you want this.
     * @param path a directory to monitor for changes
     * @return a watcher that fires events for any of its children (and its self).
     * @throws IOException in case the path is not absolute or it's not an directory
     */
    public static Watcher recursiveDirectory(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("We can only watch absolute paths");
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Only directories are supported");
        }
        return new Watcher(WatcherKind.RECURSIVE_DIRECTORY, path);
    }

    /**
     * Callback that gets executed for every event. Can get called quite a bit, so be careful what happens here.
     * Use the {@link #withExecutor(Executor)} function to influence the sequencing of these events.
     * By default they can arrive in parallel.
     * @param eventHandler a callback that handles the watch event, will be called once per event.
     * @return this for optional method chaining
     */
    public Watcher onEvent(Consumer<WatchEvent> eventHandler) {
        this.eventHandler = eventHandler;
        return this;
    }

    /**
     * Optionally configure the executor in which the {@link #onEvent(Consumer)} callbacks are scheduled.
     * If not defined, every task will be scheduled on the {@link java.util.concurrent.ForkJoinPool#commonPool()}.
     * @param callbackHandler worker pool to use
     * @return this for optional method chaining
     */
    public Watcher withExecutor(Executor callbackHandler) {
        this.executor = callbackHandler;
        return this;
    }

    /**
     * Start watch the path for events.
     * @return a subscription for the watch, when closed, new events will stop being registered to the worker pool.
     * @throws IOException in case the starting of the watcher caused an underlying IO exception
     * @throws IllegalStateException the watchers is not configured correctly (for example, missing {@link #onEvent(Consumer)})
     */
    public Closeable start() throws IOException, IllegalStateException {
        if (this.eventHandler == NULL_HANDLER) {
            throw new IllegalStateException("There is no onEvent handler defined");
        }
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
            case FILE: {
                var result = new JDKFileWatcher(path, executor, this.eventHandler);
                result.start();
                return result;
            }

            default:
                throw new IllegalStateException("Not supported yet");
        }
    }

}
