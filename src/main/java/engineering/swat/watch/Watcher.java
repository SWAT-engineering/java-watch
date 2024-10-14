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
    private final WatchScope scope;
    private final Path path;
    private volatile Executor executor = CompletableFuture::runAsync;

    private static final Consumer<WatchEvent> NULL_HANDLER = p -> {};
    private volatile Consumer<WatchEvent> eventHandler = NULL_HANDLER;


    private Watcher(WatchScope scope, Path path) {
        this.scope = scope;
        this.path = path;
    }

    /**
     * Watch a path for updates, optionally also get events for its children/descendants
     * @param path which absolute path to monitor, can be a file or a directory, but has to be absolute
     * @param scope for directories you can also choose to monitor it's direct children or all it's descendants
     * @throws IllegalArgumentException in case a path is not supported (in relation to the scope)
     */
    public static Watcher watch(Path path, WatchScope scope) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("We can only watch absolute paths");
        }
        switch (scope) {
            case PATH_AND_CHILDREN: // intended fallthrough
            case PATH_AND_ALL_DESCENDANTS:
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("Only directories are supported for this scope: " + scope);
                }
                break;
            case PATH_ONLY:
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("Symlinks are not supported");
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported scope: " + scope);

        }
        return new Watcher(scope, path);
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
     * @throws IllegalStateException the watchers is not configured correctly (for example, missing {@link #onEvent(Consumer)}, or a watcher is started twice)
     */
    public Closeable start() throws IOException {
        if (this.eventHandler == NULL_HANDLER) {
            throw new IllegalStateException("There is no onEvent handler defined");
        }
        switch (scope) {
            case PATH_AND_CHILDREN: {
                var result = new JDKDirectoryWatcher(path, executor, this.eventHandler, false);
                result.start();
                return result;
            }
            case PATH_AND_ALL_DESCENDANTS: {
                try {
                    var result = new JDKDirectoryWatcher(path, executor, this.eventHandler, true);
                    result.start();
                    return result;
                } catch (Throwable ex) {
                    // no native support, use the simulation
                    logger.debug("Not possible to register the native watcher, using fallback for {}", path);
                    logger.trace(ex);
                    var result = new JDKRecursiveDirectoryWatcher(path, executor, this.eventHandler);
                    result.start();
                    return result;
                }
            }
            case PATH_ONLY: {
                var result = new JDKFileWatcher(path, executor, this.eventHandler);
                result.start();
                return result;
            }

            default:
                throw new IllegalStateException("Not supported yet");
        }
    }

}
