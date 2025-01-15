package engineering.swat.watch.impl.macos.apis;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.platform.mac.CoreFoundation.CFAllocatorRef;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

/**
 * Interface for the "File System Events" API collection of the "Core Services"
 * framework. See:
 *
 * https://developer.apple.com/documentation/coreservices/file_system_events?language=objc
 */
public interface FileSystemEvents extends Library {
    FileSystemEvents INSTANCE = Native.load("CoreServices", FileSystemEvents.class);

    @FieldOrder({ "copyDescription", "info", "release", "retain", "version" })
    public static class FSEventStreamContext extends Structure {
        public Object copyDescription;
        public Object info;
        public Object release;
        public Object retain;
        public Object version;
    }

    Pointer FSEventStreamCreate(        // FSEventStreamRef
        CFAllocatorRef allocator,       // CFAllocator
        FSEventStreamCallback callback, // FSEventStreamCallback
        Pointer context, // FSEventStreamContext
        CFArrayRef pathsToWatch,        // CFArray
        long sinceWhen,                 // FSEventStreamEventId
        double latency,                 // CFTimeInterval
        int flags);                     // FSEventStreamCreateFlags

    void FSEventStreamSetDispatchQueue( // void
        Pointer streamRef,              // FSEventStreamRef
        Pointer q);                     // dispatch_queue_t

    boolean FSEventStreamStart( // Boolean
        Pointer streamRef);     // FSEventStreamRef

    void FSEventStreamStop( // void
        Pointer streamRef); // FSEventStreamRef

    void FSEventStreamRelease( // void
        Pointer streamRef); // FSEventStreamRef

    void FSEventStreamInvalidate(/* FSEventStreamRef */ Pointer streamRef);

    void FSEventStreamShow( // void
        Pointer streamRef); // ConstFSEventStreamRef

    long FSEventsGetCurrentEventId(); // FSEventStreamEventId

    //
    // Functions
    //

    //
    // Enumerations
    //

    static enum FSEventStreamCreateFlags {
        kFSEventStreamCreateFlagNone           (0x00000000),
        kFSEventStreamCreateFlagUseCFTypes     (0x00000001),
        kFSEventStreamCreateFlagNoDefer        (0x00000002),
        kFSEventStreamCreateFlagWatchRoot      (0x00000004),
        kFSEventStreamCreateFlagIgnoreSelf     (0x00000008),
        kFSEventStreamCreateFlagFileEvents     (0x00000010),
        kFSEventStreamCreateFlagMarkSelf       (0x00000020),
        kFSEventStreamCreateFlagFullHistory    (0x00000080),
        kFSEventStreamCreateFlagUseExtendedData(0x00000040),
        kFSEventStreamCreateWithDocID          (0x00000100);

        public final int mask;

        private FSEventStreamCreateFlags(int mask) {
            this.mask = mask;
        }
    }

    // https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags?language=objc
    static class FSEventStreamEventFlags {
        public static final int kFSEventStreamEventFlagNone               = 0x00000000;
        public static final int kFSEventStreamEventFlagMustScanSubDirs    = 0x00000001;
        public static final int kFSEventStreamEventFlagUserDropped        = 0x00000002;
        public static final int kFSEventStreamEventFlagKernelDropped      = 0x00000004;
        public static final int kFSEventStreamEventFlagEventIdsWrapped    = 0x00000008;
        public static final int kFSEventStreamEventFlagHistoryDone        = 0x00000010;
        public static final int kFSEventStreamEventFlagRootChanged        = 0x00000020;
        public static final int kFSEventStreamEventFlagMount              = 0x00000040;
        public static final int kFSEventStreamEventFlagUnmount            = 0x00000080;
        public static final int kFSEventStreamEventFlagItemChangeOwner    = 0x00004000;
        public static final int kFSEventStreamEventFlagItemCreated        = 0x00000100;
        public static final int kFSEventStreamEventFlagItemFinderInfoMod  = 0x00002000;
        public static final int kFSEventStreamEventFlagItemInodeMetaMod   = 0x00000400;
        public static final int kFSEventStreamEventFlagItemIsDir          = 0x00020000;
        public static final int kFSEventStreamEventFlagItemIsFile         = 0x00010000;
        public static final int kFSEventStreamEventFlagItemIsHardlink     = 0x00100000;
        public static final int kFSEventStreamEventFlagItemIsLastHardlink = 0x00200000;
        public static final int kFSEventStreamEventFlagItemIsSymlink      = 0x00040000;
        public static final int kFSEventStreamEventFlagItemModified       = 0x00001000;
        public static final int kFSEventStreamEventFlagItemRemoved        = 0x00000200;
        public static final int kFSEventStreamEventFlagItemRenamed        = 0x00000800;
        public static final int kFSEventStreamEventFlagItemXattrMod       = 0x00008000;
        public static final int kFSEventStreamEventFlagOwnEvent           = 0x00080000;
        public static final int kFSEventStreamEventFlagItemCloned         = 0x00400000;
    }

    //
    // Data types
    //

    // https://developer.apple.com/documentation/coreservices/fseventstreamcallback?language=objc
    static interface FSEventStreamCallback extends Callback {
        void callback(                  // void
            Pointer streamRef,          // ConstFSEventStreamRef
            Pointer clientCallBackInfo, // void*
            long numEvents,             // size_t
            Pointer eventPaths,         // void*
            Pointer eventFlags,         // FSEventStreamEventFlags*
            Pointer eventIds);          // FSEventStreamEventIds*
    }

    //
    // Constants
    //

    // https://developer.apple.com/documentation/coreservices/kfseventstreameventextendeddatapathkey?language=objc
    static final CFStringRef kFSEventStreamEventExtendedDataPathKey = CFStringRef.createCFString("path");

    // https://developer.apple.com/documentation/coreservices/kfseventstreameventextendedfileidkey?language=objc
    static final CFStringRef kFSEventStreamEventExtendedFileIDKey = CFStringRef.createCFString("fileID");
}
