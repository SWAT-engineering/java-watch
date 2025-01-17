package engineering.swat.watch.impl.mac.nio.file;

import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagFileEvents;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagFullHistory;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagIgnoreSelf;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagMarkSelf;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagNoDefer;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagUseCFTypes;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagUseExtendedData;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateFlagWatchRoot;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCreateFlags.kFSEventStreamCreateWithDocID;

import java.util.ArrayList;
import java.util.function.Consumer;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFAllocatorRef;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

import engineering.swat.watch.impl.mac.jna.DispatchObjects;
import engineering.swat.watch.impl.mac.jna.DispatchQueue;
import engineering.swat.watch.impl.mac.jna.FileSystemEvents;
import engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamCallback;

public class JNAFacade implements AutoCloseable {
    private static final DispatchQueue DQ = DispatchQueue.INSTANCE;
    private static final DispatchObjects DO = DispatchObjects.INSTANCE;
    private static final FileSystemEvents FSE = FileSystemEvents.INSTANCE;
    private static final CFAllocatorRef CURRENT_DEFAULT_ALLOCATOR = null;

    private Pointer stream;
    private Pointer queue;
    private boolean closed;

    public JNAFacade(Consumer<Iterable<MacWatchEvent>> handler, MacWatchable watchable) {
        try (
            var callback = new JNAFacade.Callback(handler, watchable);
            var pathsToWatch = new JNAFacade.PathsToWatch(watchable);
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
        DO.dispatch_release(queue);

        stream = null;
        queue = null;
        closed = true;
    }

    private static Pointer createFSEventStream(FSEventStreamCallback callback, CFArrayRef pathsToWatch) {
        var allocator = CURRENT_DEFAULT_ALLOCATOR;
        var context   = Pointer.NULL;
        var sinceWhen = FSE.FSEventsGetCurrentEventId();
        var latency   = 0.15;
        var flags     = Flag.all(Flag.NO_DEFER, Flag.WATCH_ROOT, Flag.FILE_EVENTS);
        return FSE.FSEventStreamCreate(allocator, callback, context, pathsToWatch, sinceWhen, latency, flags);
    }

    private static Pointer createDispatchQueue() {
        var label = "engineering.swat.watch";
        var attr  = Pointer.NULL;
        return DQ.dispatch_queue_create(label, attr);
    }

    private static class Callback implements FSEventStreamCallback, AutoCloseable {
        private final Consumer<Iterable<MacWatchEvent>> handler;
        private final MacWatchable watchable;

        private Callback(Consumer<Iterable<MacWatchEvent>> handler, MacWatchable watchable) {
            this.handler = handler;
            this.watchable = watchable;
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

            var events = new ArrayList<MacWatchEvent>();
            for (var i = 0; i < length; i++) {
                var size = events.size();

                System.err.println(new MacWatchEvent(watchable, paths[i], flags[i], ids[i]));

                // TODO: Generalize/rethink this approach... The problem to be
                // solved is that multiple "physical" events seem to be merged
                // together in the same "logical" event object.

                if (MacWatchEvent.Flag.ITEM_CREATED.check(flags[i])) {
                    var mask = MacWatchEvent.Flag.all(
                        MacWatchEvent.Flag.ITEM_CREATED,
                        MacWatchEvent.Flag.ITEM_IS_DIR,
                        MacWatchEvent.Flag.ITEM_IS_FILE,
                        MacWatchEvent.Flag.ITEM_XATTR_MOD);

                    events.add(new MacWatchEvent(watchable, paths[i], flags[i] & mask, ids[i]));
                }

                if (MacWatchEvent.Flag.ITEM_REMOVED.check(flags[i])) {
                    var mask = MacWatchEvent.Flag.all(
                        MacWatchEvent.Flag.ITEM_REMOVED,
                        MacWatchEvent.Flag.ITEM_IS_DIR,
                        MacWatchEvent.Flag.ITEM_IS_FILE);

                    events.add(new MacWatchEvent(watchable, paths[i], flags[i] & mask, ids[i]));
                }

                if (MacWatchEvent.Flag.ITEM_MODIFIED.check(flags[i])) {
                    var mask = MacWatchEvent.Flag.all(
                        MacWatchEvent.Flag.ITEM_MODIFIED,
                        MacWatchEvent.Flag.ITEM_INODE_META_MOD,
                        MacWatchEvent.Flag.ITEM_IS_DIR,
                        MacWatchEvent.Flag.ITEM_IS_FILE,
                        MacWatchEvent.Flag.ITEM_XATTR_MOD);

                    events.add(new MacWatchEvent(watchable, paths[i], flags[i] & mask, ids[i]));
                }

                if (size == events.size()) {
                    events.add(new MacWatchEvent(watchable, paths[i], flags[i], ids[i]));
                }
            }

            System.err.println();
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

        public PathsToWatch(MacWatchable watchable) {
            this.pathArray = createStringArray(watchable.getPath().toString());
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

    private static enum Flag {
        USE_CF_TYPES     (kFSEventStreamCreateFlagUseCFTypes),
        NO_DEFER         (kFSEventStreamCreateFlagNoDefer),
        WATCH_ROOT       (kFSEventStreamCreateFlagWatchRoot),
        IGNORE_SELF      (kFSEventStreamCreateFlagIgnoreSelf),
        FILE_EVENTS      (kFSEventStreamCreateFlagFileEvents),
        MARK_SELF        (kFSEventStreamCreateFlagMarkSelf),
        FULL_HISTORY     (kFSEventStreamCreateFlagFullHistory),
        USE_EXTENDED_DATA(kFSEventStreamCreateFlagUseExtendedData),
        WITH_DOC_ID      (kFSEventStreamCreateWithDocID);

        private final int mask;

        private Flag(int mask) {
            this.mask = mask;
        }

        public static int all(Flag... flags) {
            var ret = 0x00000000;
            for (var f : flags) {
                ret |= f.mask;
            }
            return ret;
        }
    }
}
