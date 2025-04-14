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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TestHelper {

    public static final Duration TINY_WAIT;
    public static final Duration SHORT_WAIT;
    public static final Duration NORMAL_WAIT;
    public static final Duration LONG_WAIT;

    static {
        var delayFactorConfig = System.getenv("DELAY_FACTOR");
        int delayFactor = delayFactorConfig == null ? 1 : Integer.parseInt(delayFactorConfig);
        var os = System.getProperty("os.name", "?").toLowerCase();
        if (os.contains("mac")) {
            // OSX is SLOW on it's watches
            delayFactor *= 2;
        }
        else if (os.contains("win")) {
            // windows watches can be slow to get everything
            // published
            // especially on small core systems
            delayFactor *= 4;
        }
        TINY_WAIT = Duration.ofMillis(250 * delayFactor);
        SHORT_WAIT = Duration.ofSeconds(1 * delayFactor);
        NORMAL_WAIT = Duration.ofSeconds(4 * delayFactor);
        LONG_WAIT = Duration.ofSeconds(8 * delayFactor);
    }

    public static <T> Stream<T> streamOf(T[] values, int repetitions) {
        return streamOf(values, repetitions, false);
    }

    public static <T> Stream<T> streamOf(T[] values, int repetitions, boolean sortByRepetition) {
        if (sortByRepetition) {
            return IntStream
                .range(0, repetitions)
                .boxed()
                .flatMap(i -> Arrays.stream(values));
        }
        else { // Sort by value
            return Arrays.stream(values).flatMap(v ->
                IntStream.range(0, repetitions).mapToObj(i -> v));
        }
    }

    /**
     * Helper class to keep track of a list of events and query it (on a
     * slightly higher level of abstraction than manipulation of event streams).
     */
    public static class Bookkeeper implements Consumer<WatchEvent> {
        private final Queue<WatchEvent> events = new ConcurrentLinkedQueue<>();

        public void reset() {
            events.clear();
        }

        public Events events() {
            return new Events(events.stream());
        }

        public static class Events {
            private final Stream<WatchEvent> stream;

            private Events(Stream<WatchEvent> stream) {
                this.stream = stream;
            }

            public boolean any() {
                return stream.findAny().isPresent();
            }

            public boolean any(WatchEvent event) {
                return stream.anyMatch(e -> WatchEvent.areEquivalent(e, event));
            }

            public boolean none() {
                return !any();
            }

            public boolean none(WatchEvent event) {
                return !any(event);
            }

            public Events kind(WatchEvent.Kind... kinds) {
                return new Events(stream.filter(e -> contains(kinds, e.getKind())));
            }

            public Events kindNot(WatchEvent.Kind... kinds) {
                return new Events(stream.filter(e -> !contains(kinds, e.getKind())));
            }

            public Events rootPath(Path... rootPaths) {
                return new Events(stream.filter(e -> contains(rootPaths, e.getRootPath())));
            }

            public Events rootPathNot(Path... rootPaths) {
                return new Events(stream.filter(e -> !contains(rootPaths, e.getRootPath())));
            }

            public Events relativePath(Path... relativePaths) {
                return new Events(stream.filter(e -> contains(relativePaths, e.getRelativePath())));
            }

            public Events relativePathNot(Path... relativePaths) {
                return new Events(stream.filter(e -> !contains(relativePaths, e.getRelativePath())));
            }

            private boolean contains(Object[] a, Object key) {
                for (var elem : a) {
                    if (elem.equals(key)) {
                        return true;
                    }
                }
                return false;
            }
        }

        @Override
        public void accept(WatchEvent event) {
            events.offer(event);
        }

        @Override
        public String toString() {
            return events.toString();
        }
    }
}
