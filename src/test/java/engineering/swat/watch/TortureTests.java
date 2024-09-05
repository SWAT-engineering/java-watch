package engineering.swat.watch;

import static engineering.swat.watch.WatchEvent.Kind.OVERFLOW;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TortureTests {

    private final Logger logger = LogManager.getLogger();

    private TestDirectory testDir;

    @BeforeEach
    void setup() throws IOException {
        testDir = new TestDirectory();
    }

    @AfterEach
    void cleanup() throws IOException {
        if (testDir != null) {
            testDir.close();
        }
    }

    @BeforeAll
    static void setupEverything() throws IOException {
        Awaitility.setDefaultTimeout(4, TimeUnit.SECONDS);
    }

    private final static int THREADS = 4;
    private final static int BURST_SIZE = 1000;
    private final static Duration STOP_AFTER = Duration.ofSeconds(4);
    @Test
    void pressureOnFSShouldNotMissAnything() throws InterruptedException, IOException {
        final var root = testDir.getTestDirectory();
        final var pathWritten = ConcurrentHashMap.<Path>newKeySet();
        final var stopRunning = new Semaphore(0);
        final var done = new Semaphore(0);
        final var jobs = new ArrayList<Callable<Void>>();

        for (int j = 0; j < THREADS; j++) {
            var r = new Random(j);
            jobs.add(() -> {
                try {
                    var end = LocalTime.now().plus(STOP_AFTER);
                    while (!stopRunning.tryAcquire(100, TimeUnit.MICROSECONDS)) {
                        if (LocalTime.now().isAfter(end)) {
                            break;
                        }
                        try {
                            // burst a bunch of creates creates and then sleep a bit
                            for (int i = 0; i< BURST_SIZE; i++) {
                                var file = root.resolve("l1" + r.nextInt(1000))
                                    .resolve("l2" + r.nextInt() + ".txt");
                                Files.createDirectories(file.getParent());
                                Files.writeString(file, "Hello world");
                                pathWritten.add(file);
                            }
                        } catch (IOException e) {
                        }
                        Thread.yield();
                    }
                    return null;
                } catch (InterruptedException e) {
                    return null;
				}
                finally {
                    done.release();
                }
            });
        }

        var pool = Executors.newCachedThreadPool();

        final var events = new AtomicInteger(0);
        var seenPaths = ConcurrentHashMap.<Path>newKeySet();
        var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .withExecutor(pool)
            .onEvent(ev -> {
                events.getAndIncrement();
                seenPaths.add(ev.calculateFullPath());
            });

        try (var activeWatch = watchConfig.start() ) {
            logger.info("Starting {} jobs", THREADS);
            pool.invokeAll(jobs);
            // now we generate a whole bunch of events
            Thread.sleep(STOP_AFTER.toMillis());
            logger.info("Stopping jobs");
            stopRunning.release(THREADS);
            assertTrue(done.tryAcquire(THREADS, STOP_AFTER.toMillis(), TimeUnit.MILLISECONDS), "The runners should have stopped running");
            logger.info("Generated: {} files",  pathWritten.size());

            logger.info("Waiting for the events processing to settle down");
            int lastEventCount = events.get();
            int stableCount = 0;
            do {
                Thread.sleep(STOP_AFTER.toMillis() * 2);
                int currentEventCounts = events.get();
                if (currentEventCounts == lastEventCount) {
                    if (stableCount == 2) {
                        logger.info("Stable after: {} events", currentEventCounts);
                        break;
                    }
                    else {
                        stableCount++;
                    }
                }
                else {
                    stableCount = 0;
                }
                lastEventCount = currentEventCounts;
            } while (true);
        }
        finally {
            stopRunning.release(THREADS);
            // shutdown the pool (so no new events are registered)
            pool.shutdown();
        }

        // but wait till all scheduled tasks have been completed
        pool.awaitTermination(10, TimeUnit.SECONDS);

        logger.info("Comparing events and files seen");
        // now make sure that the two sets are the same
        for (var f : pathWritten) {
            assertTrue(seenPaths.contains(f), "We should have seen all paths");
        }
    }
}
