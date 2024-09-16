package engineering.swat.watch;

import static org.awaitility.Awaitility.doNotCatchUncaughtExceptionsByDefault;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

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

    private final class IOGenerator {
        private final Set<Path> pathsWritten = ConcurrentHashMap.<Path>newKeySet();
        private final Semaphore startRunning = new Semaphore(0);
        private final Semaphore stopRunning = new Semaphore(0);
        private final Semaphore done = new Semaphore(0);
        private final int jobs;

        IOGenerator(int jobs, Path root, Executor exec) {
            this.jobs = jobs;
            for (int j = 0; j < jobs; j++) {
                startJob(root.resolve("run" + j), new Random(j), exec);
            }
        }

        private final static int BURST_SIZE = 1000;

        private void startJob(final Path root, Random r, Executor exec) {
            exec.execute(() -> {
                try {
                    startRunning.acquire();
                    var end = LocalTime.now().plus(TestHelper.NORMAL_WAIT.multipliedBy(2));
                    while (!stopRunning.tryAcquire(100, TimeUnit.MICROSECONDS)) {
                        if (LocalTime.now().isAfter(end)) {
                            break;
                        }
                        try {
                            // burst a bunch of creates creates and then sleep a bit
                            for (int i = 0; i< BURST_SIZE; i++) {
                                var file = root.resolve("l1-" + r.nextInt(1000))
                                    .resolve("l2-" + r.nextInt(100))
                                    .resolve("l3-" + r.nextInt() + ".txt");
                                Files.createDirectories(file.getParent());
                                Files.writeString(file, "Hello world");
                                pathsWritten.add(file);
                            }
                        } catch (IOException e) {
                        }
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                }
                finally {
                    done.release();
                }
            });
        }

        void start() {
            startRunning.release(jobs);
        }

        Set<Path> stop() throws InterruptedException {
            stopRunning.release(jobs);
            startRunning.release(jobs);
            assertTrue(done.tryAcquire(jobs, TestHelper.NORMAL_WAIT.toMillis() * 2, TimeUnit.MILLISECONDS), "IO workers should stop in a reasonable time");
            return pathsWritten;
        }
    }

    private static final int THREADS = 4;

    @Test
    void pressureOnFSShouldNotMissNewFilesAnything() throws InterruptedException, IOException {
        final var root = testDir.getTestDirectory();
        var pool = Executors.newCachedThreadPool();

        var io = new IOGenerator(THREADS, root, pool);


        final var events = new AtomicInteger(0);
        final var happened = new Semaphore(0);
        var seenCreates = ConcurrentHashMap.<Path>newKeySet();
        var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .withExecutor(pool)
            .onEvent(ev -> {
                events.getAndIncrement();
                happened.release();
                var fullPath = ev.calculateFullPath();
                switch (ev.getKind()) {
                    case CREATED:
                        seenCreates.add(fullPath);
                        break;
                    case MODIFIED:
                        // platform specific if this comes by or not
                        break;
                    default:
                        logger.error("Unexpected event: {}", ev);
                        break;
                }
            });

        Set<Path> pathsWritten;

        try (var activeWatch = watchConfig.start() ) {
            logger.info("Starting {} jobs", THREADS);
            io.start();
            // now we generate a whole bunch of events
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            logger.info("Stopping jobs");
            pathsWritten = io.stop();
            logger.info("Generated: {} files",  pathsWritten.size());

            logger.info("Waiting for the events processing to stabilize");
            waitForStable(events, happened);

        }
        catch (Exception ex) {
            logger.catching(ex);
            throw ex;

        }
        finally {
            try {
                logger.info("stopping IOGenerator");
                io.stop();
            }
            catch (Throwable _ignored) {}
            logger.info("Shutting down pool");
            // shutdown the pool (so no new events are registered)
            pool.shutdown();
        }

        // but wait till all scheduled tasks have been completed
        // pool.awaitTermination(10, TimeUnit.SECONDS);

        logger.info("Calculating sizes");
        logger.info("Comparing events ({} events for {} paths) and files (total {}) created", events.get(), seenCreates.size(), pathsWritten.size());
        logger.info("Comparing paths");
        // now make sure that the two sets are the same
        for (var f : pathsWritten) {
            assertTrue(seenCreates.contains(f), () -> "Missing create event for: " + f);
        }
    }



    @Test
    //Deletes can race the filesystem, so you might miss a few files in a dir, if that dir is already deleted
    @EnabledIfEnvironmentVariable(named="TORTURE_DELETE", matches="true")
    void pressureOnFSShouldNotMissDeletes() throws InterruptedException, IOException {
        final var root = testDir.getTestDirectory();
        var pool = Executors.newCachedThreadPool();

        Set<Path> pathsWritten;
        var seenDeletes = ConcurrentHashMap.<Path>newKeySet();
        var io = new IOGenerator(THREADS, root, pool);
        try {
            io.start();
            Thread.sleep(TestHelper.NORMAL_WAIT.toMillis());
            pathsWritten = io.stop();

            final var events = new AtomicInteger(0);
            final var happened = new Semaphore(0);
            var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
                .withExecutor(pool)
                .onEvent(ev -> {
                    events.getAndIncrement();
                    happened.release();
                    var fullPath = ev.calculateFullPath();
                    switch (ev.getKind()) {
                        case DELETED:
                            seenDeletes.add(fullPath);
                            break;
                        case MODIFIED:
                            // happens on dir level, as the files are getting removed
                            break;
                        default:
                            logger.error("Unexpected event: {}", ev);
                            break;
                    }
                });

            try (var activeWatch = watchConfig.start() ) {
                logger.info("Deleting files now", THREADS);
                testDir.deleteAllFiles();
                logger.info("Waiting for the events processing to stabilize");
                waitForStable(events, happened);
            }
        }
        finally {
            try {
                io.stop();
            }
            catch (Throwable _ignored) {}
            // shutdown the pool (so no new events are registered)
            pool.shutdown();
        }

        // but wait till all scheduled tasks have been completed
        pool.awaitTermination(10, TimeUnit.SECONDS);

        logger.info("Comparing events and files seen");
        // now make sure that the two sets are the same
        for (var f : pathsWritten) {
            assertTrue(seenDeletes.contains(f), () -> "Missing delete event for: " + f);
        }
    }



    private void waitForStable(final AtomicInteger events, final Semaphore happened) throws InterruptedException {
        int lastEventCount = events.get();
        int stableCount = 0;
        do {
            while (happened.tryAcquire(TestHelper.SHORT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                happened.drainPermits();
            }
            int currentEventCounts = events.get();
            if (currentEventCounts == lastEventCount) {
                if (stableCount == 30) {
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
