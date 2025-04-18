package engineering.swat.watch.impl.mac;

import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCreateFlag.FILE_EVENTS;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCreateFlag.NO_DEFER;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCreateFlag.WATCH_ROOT;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_CREATED;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_INODE_META_MOD;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_MODIFIED;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamEventFlag.ITEM_REMOVED;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamEventFlag.MUST_SCAN_SUB_DIRS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

import engineering.swat.watch.impl.mac.apis.DispatchObjects;
import engineering.swat.watch.impl.mac.apis.DispatchQueue;
import engineering.swat.watch.impl.mac.apis.FileSystemEvents;
import engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCallback;

// Note: This file is designed to be the only place in this package where JNA is
// used and/or the native APIs are called. If the need to do so arises outside
// this file, consider extending this file to offer the required services
// without exposing JNA and/or the native APIs.

/**
 * <p>
 * Stream of native events for a path, issued by macOS.
 * </p>
 *
 * <p>
 * Note: Methods {@link #open()} and {@link #close()} synchronize on this object
 * to avoid races. The synchronization overhead is expected to be negligible, as
 * these methods are expected to be rarely called.
 * </p>
 */
public class NativeEventStream implements Closeable {

    // Native APIs
    private static final CoreFoundation   CF  = CoreFoundation.INSTANCE;
    private static final DispatchObjects  DO  = DispatchObjects.INSTANCE;
    private static final DispatchQueue    DQ  = DispatchQueue.INSTANCE;
    private static final FileSystemEvents FSE = FileSystemEvents.INSTANCE;

    // Native memory (automatically deallocated when set to `null`)
    private volatile @Nullable FSEventStreamCallback callback;
    private volatile @Nullable Pointer stream;
    private volatile @Nullable Pointer queue;

    private final Path path;
    private final NativeEventHandler handler;
    private volatile boolean closed;

    public NativeEventStream(Path path, NativeEventHandler handler) throws IOException {
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

        // Allocate native memory. (Checker Framework: The local variables are
        // `@NonNull` copies of the `@Nullable` fields.)
        var callback = this.callback = createCallback(path, handler);
        var stream = this.stream = createFSEventStream(path, callback);
        var queue = this.queue = createDispatchQueue();

        // Start the stream
        FSE.FSEventStreamSetDispatchQueue(stream, queue);
        FSE.FSEventStreamStart(stream);
    }

    private static FSEventStreamCallback createCallback(Path path, NativeEventHandler handler) {
        return new FSEventStreamCallback() {
            @Override
            public void callback(Pointer streamRef, Pointer clientCallBackInfo,
                    long numEvents, Pointer eventPaths, Pointer eventFlags, Pointer eventIds) {
                // This function is called each time native events are issued by
                // macOS. The purpose of this function is to perform the minimal
                // amount of processing to hide the native APIs from downstream
                // consumers, who are offered native events via `handler`.

                var paths = eventPaths.getStringArray(0, (int) numEvents);
                var flags = eventFlags.getIntArray(0, (int) numEvents);

                for (var i = 0; i < numEvents; i++) {
                    var context = path.relativize(Path.of(paths[i]));

                    // Note: Multiple "physical" native events might be merged
                    // into a single "logical" native event, so the following
                    // series of checks should be if-statements (instead of
                    // if/else-statements).
                    if (any(flags[i], ITEM_CREATED.mask)) {
                        handler.handle(ENTRY_CREATE, context);
                    }
                    if (any(flags[i], ITEM_REMOVED.mask)) {
                        handler.handle(ENTRY_DELETE, context);
                    }
                    if (any(flags[i], ITEM_MODIFIED.mask | ITEM_INODE_META_MOD.mask)) {
                        handler.handle(ENTRY_MODIFY, context);
                    }
                    if (any(flags[i], MUST_SCAN_SUB_DIRS.mask)) {
                        handler.handle(OVERFLOW, null);
                    }

                    // TODO: Fix renames (`ITEM_RENAMED`)
                }
            }

            private boolean any(int bits, int mask) {
                return (bits & mask) != 0;
            }
        };
    }

    private static Pointer createFSEventStream(Path path, FSEventStreamCallback callback) {
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
        if (stream != null) {
            var streamNonNull = stream; // Checker Framework: `@NonNull` copy of `@Nullable` field
            FSE.FSEventStreamStop(streamNonNull);
            FSE.FSEventStreamSetDispatchQueue(streamNonNull, Pointer.NULL);
            FSE.FSEventStreamInvalidate(streamNonNull);
            FSE.FSEventStreamRelease(streamNonNull);
        }
        if (queue != null) {
            DO.dispatch_release(queue);
        }

        // Deallocate native memory
        callback = null;
        stream = null;
        queue = null;
    }
}

/**
 * Array of strings in native memory, needed to create a new native event stream
 * (i.e., the {@code pathsToWatch} argument of {@code FSEventStreamCreate} is an
 * array of strings).
 */
class Strings implements AutoCloseable {

    // Native APIs
    private static final CoreFoundation CF = CoreFoundation.INSTANCE;

    // Native memory
    private final CFStringRef[] strings;
    private final CFArrayRef array;

    private volatile boolean closed = false;

    public Strings(String... strings) {
        // Allocate native memory
        this.strings = createCFStrings(strings);
        this.array = createCFArray(this.strings);
    }

    public CFArrayRef toCFArray() {
        if (closed) {
            throw new IllegalStateException("Paths already deallocated");
        }
        return array;
    }

    private static CFStringRef[] createCFStrings(String[] pathsToWatch) {
        var n = pathsToWatch.length;

        var strings = new CFStringRef[n];
        for (int i = 0; i < n; i++) {
            strings[i] = CFStringRef.createCFString(pathsToWatch[i]);
        }
        return strings;
    }

    private static CFArrayRef createCFArray(CFStringRef[] strings) {
        var n = strings.length;
        var size = Native.getNativeSize(CFStringRef.class);

        // Create a temporary array of pointers to the strings (automatically
        // freed when `values` goes out of scope)
        var values = new Memory(n * size);
        for (int i = 0; i < n; i++) {
            values.setPointer(i * size, strings[i].getPointer());
        }

        // Create a permanent array based on the temporary array
        var alloc = CF.CFAllocatorGetDefault();
        var numValues = new CFIndex(n);
        var callBacks = Pointer.NULL;
        return CF.CFArrayCreate(alloc, values, numValues, callBacks);
    }

    // -- AutoCloseable --

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("Paths already deallocated");
        } else {
            closed = true;
        }

        // Deallocate native memory
        for (var s : strings) {
            if (s != null) {
                s.release();
            }
        }
        if (array != null) {
            array.release();
        }
    }
}
