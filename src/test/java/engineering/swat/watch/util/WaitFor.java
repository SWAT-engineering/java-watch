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

    public WaitFor failFast(BooleanSupplier b) {
        this.failFast = b;
        return this;
    }

    public WaitFor failFast(AtomicBoolean b) {
        return failFast(b::get);
    }



    private boolean alreadyFailed() {
        if (failFast != null) {
            try {
                return failFast.getAsBoolean();
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return false;
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
            if (action.getAsBoolean()) {
                return true;
            }
            if (alreadyFailed()) {
                fail(message.get() + " was terminated by failFast predicate");
            }
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

    private void holdBlock(BooleanSupplier p) {
        // we keep going untill a false result
        block(() -> !p.getAsBoolean());
        assertTrue(p, message);
    }

    public void holds(BooleanSupplier p) {
        holdBlock(p);
    }

    public void holdsEmpty(Supplier<Stream<?>> stream) {
        holdBlock(() -> {
            assertEquals(Collections.emptyList(), stream.get().collect(Collectors.toList()), message);
            return true;
        });
    }


}
