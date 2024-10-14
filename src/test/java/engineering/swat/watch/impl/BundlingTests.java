package engineering.swat.watch.impl;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.TestHelper;

public class BundlingTests {

    private final Logger logger = LogManager.getLogger();
    private BundledSubscription<Long, Boolean> target;
    private FakeSubscribable fakeSubs;

    private static class FakeSubscribable implements ISubscribable<Long, Boolean>  {
        private final Map<Long, Consumer<Boolean>> subs = new ConcurrentHashMap<>();

        @Override
        public Closeable subscribe(Long target, Consumer<Boolean> eventListener) throws IOException {
            subs.put(target, eventListener);
            return () -> {
                subs.remove(target, eventListener);
            };
        }

        void publish(Long x) {
            var s = subs.get(x);
            if (s != null) {
                s.accept(true);
            }
        }
    };


    @BeforeEach
    void setup() {
        fakeSubs = new FakeSubscribable();
        target = new BundledSubscription<>(fakeSubs);
    }

    @BeforeAll
    static void setupEverything() {
        Awaitility.setDefaultTimeout(TestHelper.LONG_WAIT.getSeconds(), TimeUnit.SECONDS);
    }

    private static final int SUBs = 100;
    private static final long MSGs = 100_000;

    @Test
    void manySubscriptions() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        List<Closeable> closers = new ArrayList<>();

        for (int i = 0; i < MSGs; i++) {
            for (int j = 0; j < SUBs; j++) {
                closers.add(target.subscribe(Long.valueOf(i), b -> hits.incrementAndGet()));
            }
        }

        logger.info("Sending single message");
        fakeSubs.publish(Long.valueOf(0));
        assertEquals(SUBs, hits.get());
        logger.info("Sending all messages");
        hits.set(0);
        for (int i = 0; i < MSGs; i++) {
            fakeSubs.publish(Long.valueOf(i));
        }
        assertEquals(SUBs * MSGs, hits.get());

        logger.info("Clearing subs in parallel");
        for (var clos : closers) {
            CompletableFuture.runAsync(() -> {
                try {
                    clos.close();
                } catch (IOException e) {
                    logger.catching(e);
                }
            });
        }

        await("Closing should finish")
            .until(fakeSubs.subs::isEmpty);
        logger.info("Done clearing");


    }

    @RepeatedTest(failureThreshold = 1, value=50)
    void parallelSubscriptions() throws IOException, InterruptedException {
        var hits = new AtomicInteger();
        var endPointReached = new Semaphore(0);
        var waitingForClose = new Semaphore(0);
        var done = new Semaphore(0);

        int active = 0;
        for (int j = 0; j < SUBs; j++) {
            boolean keepAround = j % 2 == 0;
            if (keepAround) {
                active++;
            }
            var t = new Thread(() -> {
                for (int k =0; k < 1000; k++) {
                    try (var c = target.subscribe(Long.valueOf(0), b -> hits.incrementAndGet())) {
                        if (keepAround && k + 1 == 1000) {
                            endPointReached.release();
                            waitingForClose.acquire();
                        }
                    } catch (Exception ignored) {
                        logger.catching(ignored);
                    }
                }
                done.release();
            });
            t.setDaemon(true);
            t.start();
        }

        endPointReached.acquire(active);
        done.acquire(SUBs - active);
        fakeSubs.publish(Long.valueOf(0));

        await("Subscriptions should have hit")
            .untilAtomic(hits, IsEqual.equalTo(active));
        waitingForClose.release(active);
    }

}
