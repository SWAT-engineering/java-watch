/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2023, Swat.engineering
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package engineering.swat.watch.impl.jdk;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.nio.file.ExtendedWatchEventModifier;

import engineering.swat.watch.DaemonThreadPool;
import engineering.swat.watch.impl.mac.MacWatchService;
import engineering.swat.watch.impl.util.SubscriptionKey;

/**
 * This class is a wrapper around the JDK WatchService, it takes care to poll the service for new events, and then distributes them to the right parties
 */
class JDKPoller {
    private JDKPoller() {}

    private static final Logger logger = LogManager.getLogger();
    private static final Map<WatchKey, Consumer<List<WatchEvent<?>>>> watchers = new ConcurrentHashMap<>();
    private static final WatchService service;
    /**
     * We have to be a bit careful with registering too many paths in parallel
     * Linux can be thrown into a deadlock if you try to start 1000 threads and then do a register at the same time.
     */
    private static final ExecutorService registerPool = DaemonThreadPool.buildConstrainedCached("JavaWatch-rate-limit-registry", Runtime.getRuntime().availableProcessors());

    static {
        try {
            service = Platform.get().newWatchService();
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


    public static Closeable register(SubscriptionKey path, Consumer<List<WatchEvent<?>>> changesHandler) throws IOException {
        logger.debug("Register watch for: {}", path);

        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Watchable watchable = Platform.get().newWatchable(path.getPath());
                    WatchEvent.Kind<?>[] kinds = new WatchEvent.Kind[]{ ENTRY_CREATE, ENTRY_MODIFY, OVERFLOW, ENTRY_DELETE };
                    if (path.isRecursive()) {
                        return watchable.register(service, kinds, ExtendedWatchEventModifier.FILE_TREE);
                    }
                    else {
                        return watchable.register(service, kinds);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, registerPool) // read registerPool why we have to add a limiter here
            .thenApplyAsync(key -> {
                watchers.put(key, changesHandler);
                return new Closeable() {
                    @Override
                    public void close() throws IOException {
                        logger.debug("Closing watch for: {}", path);
                        if (watchers.remove(key, changesHandler)) {
                            key.cancel();
                        }
                    }
                };
            })
            .get(); // we have to do a get here, to make sure the `register` function blocks
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException && e.getCause().getCause() instanceof IOException) {
                throw (IOException)e.getCause().getCause();
            }
            throw new IOException("Could not register path", e.getCause());
        } catch (InterruptedException e) {
            // the pool was closing, forward it
            Thread.currentThread().interrupt();
            throw new IOException("The registration was canceled");
        }
    }

    private static interface Platform {
        WatchService newWatchService() throws IOException;
        Watchable newWatchable(Path path);

        static final Platform MAC = new Platform() {
            @Override
            public WatchService newWatchService() throws IOException {
                return new MacWatchService();
            }
            @Override
            public Watchable newWatchable(Path path) {
                return MacWatchService.newWatchable(path);
            }
        };

        static final Platform DEFAULT = new Platform() {
            @Override
            public WatchService newWatchService() throws IOException {
                return FileSystems.getDefault().newWatchService();
            }
            @Override
            public Watchable newWatchable(Path path) {
                return path;
            }
        };

        static final Platform CURRENT = current(); // Assumption: the platform doesn't change

        private static Platform current() {
            if (com.sun.jna.Platform.isMac()) {
                var key = "engineering.swat.java-watch.mac";
                var val = System.getProperty(key);
                if (val != null) {
                    if (val.equals("fsevents")) {
                        return MAC;
                    } else if (val.equals("jdk")) {
                        return DEFAULT;
                    } else {
                        logger.warn("Unexpected value \"{}\" for system property \"{}\". Using value \"jdk\" instead.", val, key);
                        return DEFAULT;
                    }
                } else {
                    return MAC;
                }
            }

            return DEFAULT;
        }

        static Platform get() {
            return CURRENT;
        }
    }
}
