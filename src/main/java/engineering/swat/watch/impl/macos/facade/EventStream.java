package engineering.swat.watch.impl.macos.facade;

import java.util.function.Consumer;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFAllocatorRef;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

import engineering.swat.watch.impl.macos.apis.DispatchQueue;
import engineering.swat.watch.impl.macos.apis.FileSystemEvents;
import engineering.swat.watch.impl.macos.apis.FileSystemEvents.FSEventStreamCallback;

public class EventStream implements AutoCloseable {
    private static final DispatchQueue DQ = DispatchQueue.INSTANCE;
    private static final FileSystemEvents FSE = FileSystemEvents.INSTANCE;
    private static final CFAllocatorRef CURRENT_DEFAULT_ALLOCATOR = null;

    private Pointer stream;
    private Pointer queue;
    private boolean closed;

    public EventStream(Consumer<Event[]> handler, String path) {
        try (
            var callback = new EventStream.Callback(handler);
            var pathsToWatch = new EventStream.PathsToWatch(path);
        ) {
            this.stream = createFSEventStream(callback, pathsToWatch.toCFArrayRef());
            this.queue = createDispatchQueue();
            this.closed = false;
        }

        FSE.FSEventStreamSetDispatchQueue(stream, queue);
        FSE.FSEventStreamStart(stream);
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("File system event stream is already closed");
        }

        FSE.FSEventStreamStop(stream);
        FSE.FSEventStreamSetDispatchQueue(stream, Pointer.NULL);
        FSE.FSEventStreamInvalidate(stream);
        FSE.FSEventStreamRelease(stream);
        DQ.dispatch_release(queue);

        stream = null;
        queue = null;
        closed = true;
    }

    private static Pointer createFSEventStream(FSEventStreamCallback callback, CFArrayRef pathsToWatch) {
        var allocator = CURRENT_DEFAULT_ALLOCATOR;
        var context   = Pointer.NULL;
        var sinceWhen = FSE.FSEventsGetCurrentEventId();
        var latency   = 0.0;
        var flags     = FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagFileEvents.mask;
        return FSE.FSEventStreamCreate(allocator, callback, context, pathsToWatch, sinceWhen, latency, flags);
    }

    private static Pointer createDispatchQueue() {
        var label = "engineering.swat.watch";
        var attr  = Pointer.NULL;
        return DQ.dispatch_queue_create(label, attr);
    }

    private static class Callback implements FSEventStreamCallback, AutoCloseable {
        private final Consumer<Event[]> handler;

        private Callback(Consumer<Event[]> handler) {
            this.handler = handler;
        }

        @Override
        public void callback(Pointer streamRef, Pointer clientCallBackInfo, long numEvents, Pointer eventPaths, Pointer eventFlags, Pointer eventIds) {
            if (numEvents > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Too many events");
            }

            var offset = 0;
            var length = (int) numEvents;

            var paths = eventPaths.getStringArray(offset, length);
            var flags = eventFlags.getIntArray(offset, length);
            var ids = eventIds.getLongArray(offset, length);

            var events = new Event[(int) numEvents];
            for (var i = 0; i < length; i++) {
                events[i] = new Event(paths[i], flags[i], ids[i]);
            }

            handler.accept(events);
        }

        @Override
        public void close() {
        }
    }

    public static class PathsToWatch implements AutoCloseable {
        private CFStringRef[] pathArray;
        private CFArrayRef arrayOfPaths;
        private boolean closed = false;

        public PathsToWatch(String... paths) {
            this.pathArray = createStringArray(paths);
            this.arrayOfPaths = createArrayOfStrings(pathArray);
            this.closed = false;
        }

        public CFArrayRef toCFArrayRef() {
            if (closed) {
                throw new IllegalStateException("Paths already deallocated");
            }
            return arrayOfPaths;
        }

        @Override
        public void close() {
            if (closed) {
                throw new IllegalStateException("Paths already deallocated");
            }

            for (var s : pathArray) {
                s.release();
            }
            arrayOfPaths.release();

            pathArray = null;
            arrayOfPaths = null;
            closed = true;
        }

        private static CFStringRef[] createStringArray(String... strings) {
            var n = strings.length;
            var ret = new CFStringRef[n];
            for (int i = 0; i < n; i++) {
                ret[i] = CFStringRef.createCFString(strings[i]);
            }
            return ret;
        }

        private static CFArrayRef createArrayOfStrings(CFStringRef[] strings) {
            var n = strings.length;
            var size = Native.getNativeSize(CFStringRef.class);

            var alloc = CURRENT_DEFAULT_ALLOCATOR;
            var values = new Memory(n * size);
            var numValues = new CFIndex(n);
            var callBacks = Pointer.NULL;

            for (int i = 0; i < n; i++) {
                values.setPointer(i * size, strings[i].getPointer());
            }

            return CoreFoundation.INSTANCE.CFArrayCreate(alloc, values, numValues, callBacks);
        }
    }
}
