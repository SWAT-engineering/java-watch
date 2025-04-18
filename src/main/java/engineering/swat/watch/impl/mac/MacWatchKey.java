package engineering.swat.watch.impl.mac;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.sun.nio.file.ExtendedWatchEventModifier;

public class MacWatchKey implements WatchKey {
    private final MacWatchable watchable;
    private final MacWatchService service;
    private final BlockingQueue<WatchEvent<?>> pendingEvents;

    private volatile @Nullable NativeEventStream stream;
    private volatile Configuration config = new Configuration();
    private volatile boolean signalled = false;
    private volatile boolean cancelled = false;

    public MacWatchKey(MacWatchable watchable, MacWatchService service) {
        this.watchable = watchable;
        this.service = service;
        this.pendingEvents = new LinkedBlockingQueue<>();
    }

    /**
     * Reconfigures this watch key with the given {@code kinds} and
     * {@code modifiers}, and opens an internal event stream, if needed.
     */
    public void reconfigure(Kind<?>[] kinds, Modifier[] modifiers) throws IOException {
        if (!cancelled) {
            config = new Configuration(kinds, modifiers);
            openStream();
        }
    }

    private void signalWhen(boolean condition) {
        if (condition) {
            signalled = true;
            service.offer(this);
            // The order of these statements is important. If it's the other way
            // around, then the following harmful interleaving of an offering
            // thread (Thread 1) and a polling thread (Thread 2) can happen:
            //   - Thread 1:
            //       - Add event to `pendingEvents` [`handle`]
            //       - Test `!signalled` is true [`handle`+`signalWhen`]
            //       - Offer `this` to `service` [`signalWhen`].
            //   - Thread 2:
            //       - Poll `this` from `service` [`MacWatchService.poll`]
            //       - Drain events from `pendingEvents` [`pollEvents`]
            //       - Set `signalled` to false [`reset`]
            //       - Test `!pendingEvents.empty()` is false [`reset`+`signalWhen`]
            //   - Thread 1:
            //       - Set `signalled` to true [`signalWhen`]. At this point:
            //         (a) `this` isn't offered to `service`; (b) subsequent
            //         calls of `handle` will not cause `this` to be offered. As
            //         a result, no subsequent events are propagated.
        }
    }

    // The following two methods synchronize on this object to avoid races. The
    // synchronization overhead is expected to be negligible, as these methods
    // are expected to be rarely called.

    private synchronized void openStream() throws IOException {
        if (stream == null) {
            stream = new NativeEventStream(watchable.getPath(), new OfferWatchEvent());
            stream.open();
        }
    }

    private synchronized void closeStream() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    /**
     * Handler for native events, issued by macOS. When called, it checks if the
     * native event is eligible for downstream consumption, creates and enqueues
     * a suitable {@link WatchEvent}, and signals the service (when needed).
     */
    private class OfferWatchEvent implements NativeEventHandler {
        @Override
        public <T> void handle(Kind<T> kind, T context) {
            if (!cancelled && !config.ignore(kind, context)) {

                var event = new WatchEvent<T>() {
                    @Override
                    public Kind<T> kind() {
                        return kind;
                    }
                    @Override
                    public int count() {
                        // We currently don't need/use event counts, so let's
                        // keep the code simple for now.
                        throw new UnsupportedOperationException();
                    }
                    @Override
                    public T context() {
                        return context;
                    }
                };

                pendingEvents.offer(event);
                signalWhen(!signalled);
            }
        }
    }

    /**
     * Configuration of a watch key that affects which events to
     * {@link #ignore(Kind, Path)}.
     */
    private static class Configuration {
        private final Kind<?>[] kinds;
        private final boolean singleDirectory;

        public Configuration() {
            this(new Kind<?>[0], new Modifier[0]);
        }

        public Configuration(Kind<?>[] kinds, Modifier[] modifiers) {
            // Extract only the necessary information from `modifiers`
            var fileTree = false;
            for (var m : modifiers) {
                fileTree |= m == ExtendedWatchEventModifier.FILE_TREE;
            }

            this.kinds = Arrays.copyOf(kinds, kinds.length);
            this.singleDirectory = !fileTree;
        }

        /**
         * Tests if an event should be ignored by a watch key with this
         * configuration. This is the case when one of the following is true:
         * (a) the watch key isn't configured to watch events of the given
         * {@code kind}; (b) the watch key is configured to watch only the root
         * level of a file tree, but the given {@code context} (a {@code Path})
         * points to a non-root level.
         */
        public boolean ignore(Kind<?> kind, @Nullable Object context) {
            for (var k : kinds) {
                if (k == kind) {
                    return singleDirectory &&
                        context instanceof Path && ((Path) context).getNameCount() > 1;
                }
            }
            return true;
        }
    }

    // -- WatchKey --

    @Override
    public boolean isValid() {
        return !cancelled && !service.isClosed();
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
        var list = new ArrayList<WatchEvent<?>>(pendingEvents.size());
        pendingEvents.drainTo(list);
        return list;
    }

    @Override
    public boolean reset() {
        if (!isValid()) {
            return false;
        }

        signalled = false;
        signalWhen(!pendingEvents.isEmpty());

        // Invalidation of this key *during* the execution of this method is
        // observationally equivalent to invalidation immediately *after*. Thus,
        // assume it doesn't happen and return `true`.
        return true;
    }

    @Override
    public void cancel() {
        cancelled = true;
        watchable.unregister(service);
        closeStream();
    }

    @Override
    public Watchable watchable() {
        return watchable;
    }
}
