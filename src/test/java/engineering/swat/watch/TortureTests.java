package engineering.swat.watch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.WatchEvent.Kind;

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

    private final static int THREADS = 4;
    private final static int BURST_SIZE = 1000;
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
                    var end = LocalTime.now().plus(TestHelper.NORMAL_WAIT);
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
        final var happened = new Semaphore(0);
        var seenPaths = ConcurrentHashMap.<Path>newKeySet();
        var seenDeletes = ConcurrentHashMap.<Path>newKeySet();
        var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .withExecutor(pool)
            .onEvent(ev -> {
                events.getAndIncrement();
                happened.release();
                Path fullPath = ev.calculateFullPath();
                if (ev.getKind() == Kind.DELETED) {
                    seenDeletes.add(fullPath);
                }
                else {
                    seenPaths.add(fullPath);
                }
            });

        try (var activeWatch = watchConfig.start() ) {
            logger.info("Starting {} jobs", THREADS);
            pool.invokeAll(jobs);
            // now we generate a whole bunch of events
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            logger.info("Stopping jobs");
            stopRunning.release(THREADS);
            assertTrue(done.tryAcquire(THREADS, TestHelper.NORMAL_WAIT.toMillis(), TimeUnit.MILLISECONDS), "The runners should have stopped running");
            logger.info("Generated: {} files",  pathWritten.size());

            logger.info("Waiting for the events processing to settle down");
            waitForStable(events, happened);

            logger.info("Now deleting everything");
            testDir.deleteAllFiles();
            logger.info("Waiting for the events processing to settle down");
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            waitForStable(events, happened);
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
            assertTrue(seenPaths.contains(f), () -> "Missing event for: " + f);
            assertTrue(seenDeletes.contains(f), () -> "Missing delete for: " + f);
        }
    }

    private void waitForStable(final AtomicInteger events, final Semaphore happened) throws InterruptedException {
        int lastEventCount = events.get();
        int stableCount = 0;
        do {
            while (happened.tryAcquire(TestHelper.SHORT_WAIT.toMillis() / 4, TimeUnit.MILLISECONDS)) {
                happened.drainPermits();
            }
            int currentEventCounts = events.get();
            if (currentEventCounts == lastEventCount) {
                if (stableCount == 20) {
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
}
