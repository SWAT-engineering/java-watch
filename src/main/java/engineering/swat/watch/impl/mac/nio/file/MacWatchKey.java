package engineering.swat.watch.impl.mac.nio.file;

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

import engineering.swat.watch.impl.mac.jna.EventStream;

public class MacWatchKey implements WatchKey {
    private final MacWatchable watchable;
    private final MacWatchService service;
    private final BlockingQueue<WatchEvent<?>> pendingEvents;

    private volatile @Nullable EventStream stream;
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
        }
    }

    // The following two methods synchronize on this object to avoid races. The
    // synchronization overhead is expected to be negligible, as these methods
    // are expected to be rarely called.

    private synchronized void openStream() throws IOException {
        if (stream == null) {
            stream = new EventStream(watchable.getPath(), (k, c) -> {
                if (!cancelled && !config.ignore(k, c)) {
                    pendingEvents.offer(MacWatchEvent.create(k, c));
                    signalWhen(!signalled);
                }
            });
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
         * level of a file tree, but the given {@code context} points to a
         * non-root level.
         */
        public boolean ignore(Kind<?> kind, Path context) {
            for (var k : kinds) {
                if (k == kind) {
                    return singleDirectory && context.getNameCount() > 1;
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
