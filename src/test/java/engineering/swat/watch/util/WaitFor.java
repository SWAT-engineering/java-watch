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
package engineering.swat.watch.util;

import static java.lang.System.currentTimeMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.checkerframework.checker.nullness.qual.Nullable;

public class WaitFor {

    private static Duration defaultWaitTime = Duration.ofSeconds(2);

    public static void setDefaultTimeout(Duration newDefaultTime) {
        defaultWaitTime = newDefaultTime;
    }

    private final Supplier<String> message;
    private @Nullable BooleanSupplier failFast;
    private @Nullable Duration time;
    private @Nullable Duration poll;
    private @Nullable Supplier<String> failMessage;


    private WaitFor(Supplier<String> message) {
        this.message = message;
    }

    public static WaitFor await(String message) {
        return await(() -> message);
    }

    public static WaitFor await(Supplier<String> message) {
        return new WaitFor(message);
    }

    public WaitFor time(Duration d) {
        this.time = d;
        return this;
    }

    public WaitFor pollInterval(Duration d) {
        this.poll = d;
        return this;
    }

    public WaitFor failFast(String message, BooleanSupplier b) {
        return failFast(() -> message, b);
    }

    public WaitFor failFast(Supplier<String> message, BooleanSupplier b) {
        this.failFast = b;
        this.failMessage = message;
        return this;
    }

    public WaitFor failFast(Supplier<String> message, AtomicBoolean b) {
        return failFast(message, b::get);
    }
    public WaitFor failFast(String message, AtomicBoolean b) {
        return failFast(message, b::get);
    }



    private void checkFailed() {
        if (failFast != null) {
            try {
                if (failFast.getAsBoolean()) {
                    var actualFailMessage = failMessage;
                    if (actualFailMessage == null) {
                        actualFailMessage = () -> this.message.get() + " was terminated earlier due to fail fast";
                    }
                    fail(actualFailMessage);
                }
            } catch (RuntimeException ex) {
            }
        }
    }

    private Duration calculatePoll(Duration time) {
        var poll = this.poll;
        if (poll == null || poll.toMillis() < 1) {
            poll = derivePoll(time);
        }
        return poll;
    }

    private Duration calculateTime() {
        var time = this.time;
        if (time == null) {
            time = defaultWaitTime;
        }
        return time;
    }

    private static Duration derivePoll(Duration time) {
        var result = time.dividedBy(100);
        if (result.toMillis() > 1) {
            return result;
        }
        result = time.dividedBy(10);
        if (result.toMillis() > 1) {
            return result;
        }
        return Duration.ofMillis(1);
    }


    private boolean block(BooleanSupplier action) {
        var time = calculateTime();
        var poll = calculatePoll(time);

        var end = currentTimeMillis() + time.toMillis();
        while (end > currentTimeMillis()) {
            var start = currentTimeMillis();
            checkFailed();
            if (action.getAsBoolean()) {
                return true;
            }
            checkFailed();
            var stop = currentTimeMillis();
            var remaining = poll.toMillis() - (stop - start);
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return false;
    }



    private static <T> Set<T> notSeen(Stream<T> stream, Collection<T> needles) {
        var seen = new HashSet<>(needles);
        stream.anyMatch(e -> {
            seen.remove(e);
            return seen.isEmpty(); // fast exit the stream
        });
        return seen;
    }

    public <T> void untilContains(Supplier<Stream<T>> stream, T needle) {
        assertTrue(block(() -> stream.get().anyMatch(needle::equals)), () -> message.get() + " due to missing: " + needle);
    }

    public <T> void untilContainsAll(Supplier<Stream<T>> stream, Collection<T> needles) {
        if (!block(() -> notSeen(stream.get(), needles).isEmpty())) {
            assertEquals(Collections.emptySet(), notSeen(stream.get(), needles), () -> message.get() + " due to not all entries present");
        }
    }

    public void until(AtomicBoolean b) {
        until(b::get);
    }

    public void until(BooleanSupplier p) {
        assertTrue(block(p), message);
    }

    public <T> void untilEquals(Supplier<T> val, T expected) {
        assertTrue(block(() -> expected.equals(val.get())), message);
        assertEquals(expected, val.get(), message);
    }

    private void holdBlock(BooleanSupplier p) {
        // we keep going untill a false result
        block(() -> !p.getAsBoolean());
        assertTrue(p, message);
    }

    public void holds(AtomicBoolean b) {
        holds(b::get);
    }
    public void holds(BooleanSupplier p) {
        holdBlock(p);
    }

    public void holdsFalse(AtomicBoolean p) {
        holdsFalse(p::get);
    }
    public void holdsFalse(BooleanSupplier p) {
        holds(() -> !p.getAsBoolean());
    }

    public void holdsEmpty(Supplier<Stream<?>> stream) {
        holdBlock(() -> {
            assertEquals(Collections.emptyList(), stream.get().collect(Collectors.toList()), message);
            return true;
        });
    }

    private void delayedHoldsBlock(BooleanSupplier p, Runnable finalCheck) {
        AtomicBoolean turnedTrue = new AtomicBoolean(false);
        block(() -> {
            var result = p.getAsBoolean();
            if (result) {
                turnedTrue.set(true);
                // keep running, all good
                return false;
            }
            // now if false, that could be fine, if we're never turned true yet
            return turnedTrue.get();
        });
        finalCheck.run();
    }

    public void delayedHolds(BooleanSupplier p) {
        delayedHoldsBlock(p, () -> assertTrue(p, message));
    }

    public <T> void delayedHoldsEquals(Supplier<T> sup, T expected) {
        delayedHoldsBlock(
            () -> expected.equals(sup.get()),
            () -> assertEquals(expected, sup.get(), message)
        );
    }

}
