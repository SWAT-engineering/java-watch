package engineering.swat.watch.impl;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class JDKPoller {
    private JDKPoller() {}

    private static final Logger logger = LogManager.getLogger();
    private static final Map<WatchKey, Consumer<List<WatchEvent<?>>>> watchers = new ConcurrentHashMap<>();
    private static final WatchService service;
    private static final int nCores = Runtime.getRuntime().availableProcessors();
    /**
     * We have to be a bit careful with registering too many paths in parallel
     * Linux can be thrown into a deadlock if you try to start 1000 threads and then do a register at the same time.
     */
    private static final ExecutorService registerPool = Executors.newFixedThreadPool(nCores);

    static {
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("Could not start watcher", e);
        }
        // kick off the poll loop
        poll();
    }

    private static void poll() {
        try {
            WatchKey hit;
            while ((hit = service.poll()) != null) {
                logger.trace("Got hit: {}", hit);
                try {
                    var watchHandler = watchers.get(hit);
                    if (watchHandler != null) {
                        var events = hit.pollEvents();
                        logger.trace("Found watcher for hit: {}, sending: {} (size: {})", watchHandler, events, events.size());
                        watchHandler.accept(events);
                    }
                }
                catch (Throwable t) {
                    logger.catching(Level.INFO, t);
                    // one exception shouldn't stop all the processing
                }
                finally{
                    hit.reset();
                }
            }

        }
        finally {
            // schedule next run
            // note we don't want to have multiple polls running in parallel
            // so that is why we only schedule the next one after we're done
            // processing all messages
            CompletableFuture
                .delayedExecutor(1, TimeUnit.MILLISECONDS)
                .execute(JDKPoller::poll);
        }
    }


    public static Closeable register(Path path, Consumer<List<WatchEvent<?>>> changes) throws IOException {
        logger.debug("Register watch for: {}", path);

        try {
            return CompletableFuture.supplyAsync(() -> {
                    try {
                        return path.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_MODIFY, ENTRY_DELETE);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, registerPool) // read registerPool why we have to add a limiter here
                .thenApplyAsync(key -> {
                    watchers.put(key, changes);
                    return new Closeable() {
                        @Override
                        public void close() throws IOException {
                            logger.debug("Closing watch for: {}", path);
                            key.cancel();
                            watchers.remove(key);
                        }
                    };
                })
                .get(); // we have to do a get here, to make sure the `register` function blocks
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException)e.getCause();
            }
            throw new IOException("Could not register path", e.getCause());
        } catch (InterruptedException e) {
            // the pool was closing, forward it
            Thread.currentThread().interrupt();
            throw new IOException("The registration was canceled");
        }
    }
}
