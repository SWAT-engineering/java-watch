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
package engineering.swat.watch;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Random;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TortureTests {

    private final Logger logger = LogManager.getLogger();

    private TestDirectory testDir;

    @BeforeAll
    static void setupEverything() {
        Awaitility.setDefaultTimeout(TestHelper.LONG_WAIT.getSeconds(), TimeUnit.SECONDS);
    }

    @BeforeEach
    void setup() throws IOException {
        testDir = new TestDirectory();
    }

    @AfterEach
    void cleanup() {
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

        private static final int BURST_SIZE = 1000;

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
                            // burst a bunch of creates and then sleep a bit
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

    @ParameterizedTest
    @EnumSource(names = { "ALL", "DIFF" })
    void pressureOnFSShouldNotMissNewFilesAnything(Approximation whichFiles) throws InterruptedException, IOException {
        final var root = testDir.getTestDirectory();
        var pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);

        var io = new IOGenerator(THREADS, root, pool);

        var seenCreates = ConcurrentHashMap.<Path>newKeySet();
        var watchConfig = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
            .withExecutor(pool)
            .onOverflow(whichFiles)
            .on(ev -> {
                var fullPath = ev.calculateFullPath();
                switch (ev.getKind()) {
                    case CREATED:
                        seenCreates.add(fullPath);
                        break;
                    case MODIFIED:
                        // platform specific if this comes by or not
                        break;
                    case OVERFLOW:
                        // Overflows might happen, but they're auto-handled, so
                        // they can be ignored here
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

            await("After a while we should have seen all the create events")
                .timeout(TestHelper.LONG_WAIT.multipliedBy(50))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> seenCreates.containsAll(pathsWritten));
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
    }

    private final int TORTURE_REGISTRATION_THREADS = THREADS * 500;

    @RepeatedTest(failureThreshold=1, value = 20)
    void manyRegistrationsForSamePath() throws InterruptedException, IOException {
        var startRegistering = new Semaphore(0);
        var startedWatching = new Semaphore(0);
        var startDeregistring = new Semaphore(0);
        var done = new Semaphore(0);
        var seen = ConcurrentHashMap.<Path>newKeySet();
        var exceptions = new LinkedBlockingDeque<Exception>();

        for (int t = 0; t < TORTURE_REGISTRATION_THREADS; t++) {
            var r = new Thread(() -> {
                try {
                    var watcher = Watch
                        .build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
                        .on(e -> seen.add(e.calculateFullPath()));
                    startRegistering.acquire();
                    try (var c = watcher.start()) {
                        startedWatching.release();
                        startDeregistring.acquire();
                    }
                    catch(Exception e) {
                        startedWatching.release();
                        exceptions.push(e);
                    }
                } catch (InterruptedException e1) {
                }
                finally {
                    done.release();
                }
            });
            r.setDaemon(true);
            r.start();
        }

        try {
            startRegistering.release(TORTURE_REGISTRATION_THREADS);
            startDeregistring.release(TORTURE_REGISTRATION_THREADS - 1);
            startedWatching.acquire(TORTURE_REGISTRATION_THREADS); // make sure they are all started
            done.acquire(TORTURE_REGISTRATION_THREADS - 1);
            assertTrue(seen.isEmpty(), "No events should have been sent");
            var target = testDir.getTestDirectory().resolve("test124.txt");
            Files.writeString(target, "Hello World");
            var expected = Collections.singleton(target);
            await("We should see only one event")
                .failFast(() -> !exceptions.isEmpty())
                .timeout(TestHelper.LONG_WAIT)
                .pollInterval(Duration.ofMillis(10))
                .until(() -> seen, expected::equals);
            if (!exceptions.isEmpty()) {
                fail(exceptions.pop());
            }
        }
        finally {
            startDeregistring.release(TORTURE_REGISTRATION_THREADS);
        }
    }

    static Stream<Approximation> manyRegisterAndUnregisterSameTimeSource() {
        Approximation[] values = { Approximation.ALL, Approximation.DIFF };
        return TestHelper.streamOf(values, 5);
    }

    @ParameterizedTest
    @MethodSource("manyRegisterAndUnregisterSameTimeSource")
    void manyRegisterAndUnregisterSameTime(Approximation whichFiles) throws InterruptedException, IOException {
        var startRegistering = new Semaphore(0);
        var startedWatching = new Semaphore(0);
        var stopAll = new Semaphore(0);
        var done = new Semaphore(0);
        var seen = ConcurrentHashMap.<Long>newKeySet();
        var exceptions = new LinkedBlockingDeque<Exception>();
        var target = testDir.getTestDirectory().resolve("test124.txt");
        int amountOfWatchersActive = 0;
        try {
            for (int t = 0; t < THREADS; t++) {
                final boolean finishWatching = t % 2 == 0;
                if (finishWatching) {
                    amountOfWatchersActive++;
                }
                var r = new Thread(() -> {
                    try {
                        var id = Thread.currentThread().getId();
                        startRegistering.acquire();
                        for (int k = 0; k < 1000; k++) {
                            var watcher = Watch
                                .build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
                                .onOverflow(whichFiles)
                                .on(e -> {
                                    if (e.calculateFullPath().equals(target)) {
                                        seen.add(id);
                                    }
                                });
                            try (var c = watcher.start()) {
                                if (finishWatching && k + 1 == 1000) {
                                    startedWatching.release();
                                    stopAll.acquire();
                                }
                            }
                            catch(Exception e) {
                                exceptions.push(e);
                            }
                        }
                    } catch (InterruptedException e1) {
                    }
                    finally {
                        done.release();
                    }
                });
                r.setDaemon(true);
                r.start();
            }

            startRegistering.release(THREADS);
            done.acquire(THREADS - amountOfWatchersActive);
            startedWatching.acquire(amountOfWatchersActive);
            assertTrue(seen.isEmpty(), "No events should have been sent");
            Files.writeString(target, "Hello World");
            await("We should see only exactly the " + amountOfWatchersActive + " events we expect")
                .failFast(() -> !exceptions.isEmpty())
                .pollDelay(TestHelper.NORMAL_WAIT.minusMillis(100))
                .until(seen::size, Predicate.isEqual(amountOfWatchersActive))
                ;
            if (!exceptions.isEmpty()) {
                fail(exceptions.pop());
            }
        }
        finally {
            stopAll.release(amountOfWatchersActive);
        }
    }

    @ParameterizedTest
    @EnumSource(names = { "ALL", "DIFF" })
    //Deletes can race the filesystem, so you might miss a few files in a dir, if that dir is already deleted
    @EnabledIfEnvironmentVariable(named="TORTURE_DELETE", matches="true")
    void pressureOnFSShouldNotMissDeletes(Approximation whichFiles) throws InterruptedException, IOException {
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
            var watchConfig = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
                .withExecutor(pool)
                .onOverflow(whichFiles)
                .on(ev -> {
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
                logger.info("Deleting files now ({} threads)", THREADS);
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
            Thread.yield();
            while (happened.tryAcquire(TestHelper.SHORT_WAIT.toMillis() * 2, TimeUnit.MILLISECONDS)) {
                happened.drainPermits();
            }

            int currentEventCounts = events.get();
            if (currentEventCounts == lastEventCount) {
                stableCount++;
            }
            else {
                lastEventCount = currentEventCounts;
                stableCount = 0;
            }
        } while (stableCount < 60);
        logger.info("Stable after: {} events", lastEventCount);
    }
}
