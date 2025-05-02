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
package engineering.swat.watch.impl.mac.apis;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation.CFAllocatorRef;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

/**
 * Interface for the "File System Events" API collection of the "Core Services"
 * framework.
 *
 * https://developer.apple.com/documentation/coreservices/file_system_events?language=objc
 */
public interface FileSystemEvents extends Library {
    FileSystemEvents INSTANCE = Native.load("CoreServices", FileSystemEvents.class);

    // -- Functions --

    /**
     * @param allocator    {@code CFAllocator}
     * @param callback     {@code FSEventStreamCallback}
     * @param context      {@code FSEventStreamContext}
     * @param pathsToWatch {@code CFArray}
     * @param sinceWhen    {@code FSEventStreamEventId}
     * @param latency      {@code CFTimeInterval}
     * @param flags        {@code FSEventStreamCreateFlags}
     * @return             {@code FSEventStreamRef}
     * @see https://developer.apple.com/documentation/coreservices/1443980-fseventstreamcreate?language=objc
     */
    Pointer FSEventStreamCreate(CFAllocatorRef allocator, FSEventStreamCallback callback,
        Pointer context, CFArrayRef pathsToWatch, long sinceWhen, double latency, int flags);

    /**
     * @param streamRef {@code FSEventStreamRef}
     * @see https://developer.apple.com/documentation/coreservices/1446990-fseventstreaminvalidate?language=objc
     */
    void FSEventStreamInvalidate(Pointer streamRef);

    /**
     * @param streamRef {@code FSEventStreamRef}
     * @see https://developer.apple.com/documentation/coreservices/1445989-fseventstreamrelease?language=objc
     */
    void FSEventStreamRelease(Pointer streamRef);

    /**
     * @param streamRef {@code FSEventStreamRef}
     * @param q         {@code dispatch_queue_t}
     * @see https://developer.apple.com/documentation/coreservices/1444164-fseventstreamsetdispatchqueue?language=objc
     */
    void FSEventStreamSetDispatchQueue(Pointer streamRef, Pointer q);

     /**
      * @param streamRef {@code FSEventStreamRef}
      * @see https://developer.apple.com/documentation/coreservices/1444302-fseventstreamshow?language=objc
      */
    boolean FSEventStreamShow(Pointer streamRef);

     /**
      * @param streamRef {@code FSEventStreamRef}
      * @return          {@code Boolean}
      * @see https://developer.apple.com/documentation/coreservices/1448000-fseventstreamstart?language=objc
      */
    boolean FSEventStreamStart(Pointer streamRef);

    /**
     * @param streamRef {@code FSEventStreamRef}
     * @see https://developer.apple.com/documentation/coreservices/1447673-fseventstreamstop?language=objc
     */
    void FSEventStreamStop(Pointer streamRef);

    /**
     * @return {@code FSEventStreamEventId}
     * @see https://developer.apple.com/documentation/coreservices/1442917-fseventsgetcurrenteventid?language=objc
     */
    long FSEventsGetCurrentEventId();

    // -- Enumerations --

    /**
     * @see https://developer.apple.com/documentation/coreservices/1455376-fseventstreamcreateflags?language=objc
     */
    static enum FSEventStreamCreateFlag {
        NONE             (0x00000000),
        USE_CF_TYPES     (0x00000001),
        NO_DEFER         (0x00000002),
        WATCH_ROOT       (0x00000004),
        IGNORE_SELF      (0x00000008),
        FILE_EVENTS      (0x00000010),
        MARK_SELF        (0x00000020),
        FULL_HISTORY     (0x00000080),
        USE_EXTENDED_DATA(0x00000040),
        WITH_DOC_ID      (0x00000100);

        public final int mask;

        private FSEventStreamCreateFlag(int mask) {
            this.mask = mask;
        }
    }

    /**
     * @see https://developer.apple.com/documentation/coreservices/1455361-fseventstreameventflags?language=objc
     */
    static enum FSEventStreamEventFlag {
        NONE                  (0x00000000),
        MUST_SCAN_SUB_DIRS    (0x00000001),
        USER_DROPPED          (0x00000002),
        KERNEL_DROPPED        (0x00000004),
        EVENT_IDS_WRAPPED     (0x00000008),
        HISTORY_DONE          (0x00000010),
        ROOT_CHANGED          (0x00000020),
        MOUNT                 (0x00000040),
        UNMOUNT               (0x00000080),
        ITEM_CHANGE_OWNER     (0x00004000),
        ITEM_CREATED          (0x00000100),
        ITEM_FINDER_INFO_MOD  (0x00002000),
        ITEM_INODE_META_MOD   (0x00000400),
        ITEM_IS_DIR           (0x00020000),
        ITEM_IS_FILE          (0x00010000),
        ITEM_IS_HARD_LINK     (0x00100000),
        ITEM_IS_LAST_HARD_LINK(0x00200000),
        ITEM_IS_SYMLINK       (0x00040000),
        ITEM_MODIFIED         (0x00001000),
        ITEM_REMOVED          (0x00000200),
        ITEM_RENAMED          (0x00000800),
        ITEM_XATTR_MOD        (0x00008000),
        OWN_EVENT             (0x00080000),
        ITEM_CLONED           (0x00400000);

        public final int mask;

        private FSEventStreamEventFlag(int mask) {
            this.mask = mask;
        }
    }

    // -- Data types --

    static interface FSEventStreamCallback extends Callback {
        /**
         * @param streamRef          {@code ConstFSEventStreamRef}
         * @param clientCallBackInfo {@code void*}
         * @param numEvents          {@code size_t}
         * @param eventPaths         {@code void*}
         * @param eventFlags         {@code FSEventStreamEventFlags*}
         * @param eventIds           {@code FSEventStreamEventIds*}
         * @see https://developer.apple.com/documentation/coreservices/fseventstreamcallback?language=objc
         */
        void callback(Pointer streamRef, Pointer clientCallBackInfo,
            long numEvents, Pointer eventPaths, Pointer eventFlags, Pointer eventIds);
    }

    // -- Constants --

    /**
     * @see https://developer.apple.com/documentation/coreservices/kfseventstreameventextendeddatapathkey?language=objc
     */
    static final CFStringRef kFSEventStreamEventExtendedDataPathKey = CFStringRef.createCFString("path");

    /**
     * @see https://developer.apple.com/documentation/coreservices/kfseventstreameventextendedfileidkey?language=objc
     */
    static final CFStringRef kFSEventStreamEventExtendedFileIDKey = CFStringRef.createCFString("fileID");
}
