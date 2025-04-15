package engineering.swat.watch.impl.mac.jna;

import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamCreateFlag.FILE_EVENTS;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamCreateFlag.NO_DEFER;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamCreateFlag.WATCH_ROOT;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_CREATED;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_INODE_META_MOD;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_MODIFIED;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_REMOVED;
import static engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamEventFlag.MUST_SCAN_SUB_DIRS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent.Kind;
import java.util.function.BiConsumer;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;

import engineering.swat.watch.impl.mac.jna.apis.DispatchObjects;
import engineering.swat.watch.impl.mac.jna.apis.DispatchQueue;
import engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents;
import engineering.swat.watch.impl.mac.jna.apis.FileSystemEvents.FSEventStreamCallback;

/**
 * <p>
 * Stream of events for a path. The events are issued by macOS.
 * </p>
 *
 * <p>
 * Note: Methods {@link #open()} and {@link #close()} synchronize on this object
 * to avoid races. The synchronization overhead is expected to be negligible, as
 * these methods are expected to be rarely called.
 * </p>
 */
public class EventStream implements Closeable {

    // Native APIs
    private static final CoreFoundation   CF  = CoreFoundation.INSTANCE;
    private static final DispatchQueue    DQ  = DispatchQueue.INSTANCE;
    private static final DispatchObjects  DO  = DispatchObjects.INSTANCE;
    private static final FileSystemEvents FSE = FileSystemEvents.INSTANCE;

    // Native memory (automatically deallocated when set to `null`)
    private volatile @Nullable FSEventStreamCallback callback;
    private volatile @Nullable Pointer stream;
    private volatile @Nullable Pointer queue;

    private final Path path;
    private final BiConsumer<Kind<?>, Path> handler;
    private volatile boolean closed;

    public EventStream(Path path, BiConsumer<Kind<?>, Path> handler) throws IOException {
        this.path = path.toRealPath(); // Resolve symbolic links
        this.handler = handler;
        this.closed = true;
    }

    public synchronized void open() {
        if (!closed) {
            throw new IllegalStateException("Stream already open");
        } else {
            closed = false;
        }

        // Allocate native memory
        callback = createCallback(handler, path);
        stream = createFSEventStream(callback, path);
        queue = createDispatchQueue();

        // Start the stream
        FSE.FSEventStreamSetDispatchQueue(stream, queue);
        FSE.FSEventStreamStart(stream);
    }

    private static FSEventStreamCallback createCallback(BiConsumer<Kind<?>, Path> handler, Path path) {
        return new FSEventStreamCallback() {
            @Override
            public void callback(Pointer streamRef, Pointer clientCallBackInfo,
                    long numEvents, Pointer eventPaths, Pointer eventFlags, Pointer eventIds) {

                var paths = eventPaths.getStringArray(0, (int) numEvents);
                var flags = eventFlags.getIntArray(0, (int) numEvents);

                for (var i = 0; i < numEvents; i++) {
                    var context = path.relativize(Path.of(paths[i]));

                    // Note: Multiple "physical" events might be merged into a
                    // single "logical" event, so the following series of checks
                    // should be if-statements (instead of if/else-statements).
                    if (any(flags[i], ITEM_CREATED.mask)) {
                        handler.accept(ENTRY_CREATE, context);
                    }
                    if (any(flags[i], ITEM_REMOVED.mask)) {
                        handler.accept(ENTRY_DELETE, context);
                    }
                    if (any(flags[i], ITEM_MODIFIED.mask | ITEM_INODE_META_MOD.mask)) {
                        handler.accept(ENTRY_MODIFY, context);
                    }
                    if (any(flags[i], MUST_SCAN_SUB_DIRS.mask)) {
                        handler.accept(OVERFLOW, null);
                    }
                }
            }

            private boolean any(int bits, int mask) {
                return (bits & mask) != 0;
            }
        };
    }

    private static Pointer createFSEventStream(FSEventStreamCallback callback, Path path) {
        try (
            var pathsToWatch = new Strings(path.toString());
        ) {
            var allocator = CF.CFAllocatorGetDefault();
            var context   = Pointer.NULL;
            var sinceWhen = FSE.FSEventsGetCurrentEventId();
            var latency   = 0.15;
            var flags     = NO_DEFER.mask | WATCH_ROOT.mask | FILE_EVENTS.mask;
            return FSE.FSEventStreamCreate(allocator, callback, context, pathsToWatch.toCFArray(), sinceWhen, latency, flags);
        }
    }

    private static Pointer createDispatchQueue() {
        var label = "engineering.swat.watch";
        var attr  = Pointer.NULL;
        return DQ.dispatch_queue_create(label, attr);
    }

    // -- Closeable --

    @Override
    public synchronized void close() {
        if (closed) {
            throw new IllegalStateException("Stream is already closed");
        } else {
            closed = true;
        }

        // Stop the stream
        FSE.FSEventStreamStop(stream);
        FSE.FSEventStreamSetDispatchQueue(stream, Pointer.NULL);
        FSE.FSEventStreamInvalidate(stream);
        FSE.FSEventStreamRelease(stream);
        DO.dispatch_release(queue);

        // Deallocate native memory
        callback = null;
        stream = null;
        queue = null;
    }
}
