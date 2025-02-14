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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.core.TerminalFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class AwaitilityTests {

    // @RepeatedTest(failureThreshold=1, value = 20)
    @Test
    void manyRegisterAndUnregisterSameTime() throws InterruptedException, IOException {

        await()
                .failFast(() -> false)
                .pollDelay(Duration.ofSeconds(11))
                .until(() -> 5, Predicate.isEqual(5))
                ;


        // var startRegistering = new Semaphore(0);
        // var startedWatching = new Semaphore(0);
        // var stopAll = new Semaphore(0);
        // var done = new Semaphore(0);
        // var seen = ConcurrentHashMap.<Long>newKeySet();
        // var exceptions = new LinkedBlockingDeque<Exception>();
        // var target = testDir.getTestDirectory().resolve("test124.txt");
        // int amountOfWatchersActive = 0;
        // try {
        //     for (int t = 0; t < THREADS; t++) {
        //         final boolean finishWatching = t % 2 == 0;
        //         if (finishWatching) {
        //             amountOfWatchersActive++;
        //         }
        //         var r = new Thread(() -> {
        //             try {
        //                 var id = Thread.currentThread().getId();
        //                 startRegistering.acquire();
        //                 for (int k = 0; k < 1000; k++) {
        //                     var watcher = Watcher
        //                         .watch(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
        //                         .on(e -> {
        //                             if (e.calculateFullPath().equals(target)) {
        //                                 seen.add(id);
        //                             }
        //                         });
        //                     try (var c = watcher.start()) {
        //                         if (finishWatching && k + 1 == 1000) {
        //                             startedWatching.release();
        //                             stopAll.acquire();
        //                         }
        //                     }
        //                     catch(Exception e) {
        //                         exceptions.push(e);
        //                     }
        //                 }
        //             } catch (InterruptedException e1) {
        //             }
        //             finally {
        //                 done.release();
        //             }
        //         });
        //         r.setDaemon(true);
        //         r.start();
        //     }

        //     startRegistering.release(THREADS);
        //     done.acquire(THREADS - amountOfWatchersActive);
        //     startedWatching.acquire(amountOfWatchersActive);
        //     assertTrue(seen.isEmpty(), "No events should have been sent");
        //     Files.writeString(target, "Hello World");

        //     logger.info("Ready to await... ({} watchers, {} ms, {} exceptions pending)",
        //         amountOfWatchersActive,
        //         TestHelper.NORMAL_WAIT.minusMillis(100).toMillis(),
        //         exceptions.size());

        //     await("We should see only exactly the " + amountOfWatchersActive + " events we expect")
        //         .failFast(() -> !exceptions.isEmpty())
        //         .pollDelay(TestHelper.NORMAL_WAIT.minusMillis(100))
        //         .until(seen::size, Predicate.isEqual(amountOfWatchersActive))
        //         ;

        //     logger.info("Await complete");

        //     if (!exceptions.isEmpty()) {
        //         fail(exceptions.pop());
        //     }
        // }
        // catch (Exception e) {
        //     e.printStackTrace();
        //     if (!exceptions.isEmpty()) {
        //         exceptions.peek().printStackTrace();
        //         fail(exceptions.pop());
        //     }
        // }
        // finally {
        //     stopAll.release(amountOfWatchersActive);
        // }

    }
}
