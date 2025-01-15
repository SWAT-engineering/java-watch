package engineering.swat.watch.impl.macos.facade;

import static engineering.swat.watch.impl.macos.apis.FileSystemEvents.FSEventStreamEventFlags.*;

public class Event {
    public final String path;
    public final int flags;
    public final long id;

    public Event(String path, int flags, long id) {
        this.path = path;
        this.flags = flags;
        this.id = id;
    }

    @Override
    public String toString() {
        var s = "";
        for (var f : Flag.values()) {
            if (f.check(flags)) {
                s += ", " + f.name();
            }
        }
        s = !s.isEmpty() ? s.substring(2) : s ;
        s = path + ", [" + s + "], " + id;
        return s;
    }

    public static enum Flag {
        MUST_SCAN_SUB_DIRS    (kFSEventStreamEventFlagMustScanSubDirs),
        USER_DROPPED          (kFSEventStreamEventFlagUserDropped),
        KERNEL_DROPPED        (kFSEventStreamEventFlagKernelDropped),
        EVENT_IDS_WRAPPED     (kFSEventStreamEventFlagEventIdsWrapped),
        HISTORY_DONE          (kFSEventStreamEventFlagHistoryDone),
        ROOT_CHANGED          (kFSEventStreamEventFlagRootChanged),
        MOUNT                 (kFSEventStreamEventFlagMount),
        UNMOUNT               (kFSEventStreamEventFlagUnmount),
        ITEM_CHANGE_OWNER     (kFSEventStreamEventFlagItemChangeOwner),
        ITEM_CREATED          (kFSEventStreamEventFlagItemCreated),
        ITEM_FINDER_INFO_MOD  (kFSEventStreamEventFlagItemFinderInfoMod),
        ITEM_INODE_META_MOD   (kFSEventStreamEventFlagItemInodeMetaMod),
        ITEM_IS_DIR           (kFSEventStreamEventFlagItemIsDir),
        ITEM_IS_FILE          (kFSEventStreamEventFlagItemIsFile),
        ITEM_IS_HARD_LINK     (kFSEventStreamEventFlagItemIsHardlink),
        ITEM_IS_LAST_HARD_LINK(kFSEventStreamEventFlagItemIsLastHardlink),
        ITEM_IS_SYMLINK       (kFSEventStreamEventFlagItemIsSymlink),
        ITEM_MODIFIED         (kFSEventStreamEventFlagItemModified),
        ITEM_REMOVED          (kFSEventStreamEventFlagItemRemoved),
        ITEM_RENAMED          (kFSEventStreamEventFlagItemRenamed),
        ITEM_XATTR_MOD        (kFSEventStreamEventFlagItemXattrMod),
        OWN_EVENT             (kFSEventStreamEventFlagOwnEvent),
        ITEM_CLONED           (kFSEventStreamEventFlagItemCloned);

        private final int mask;

        private Flag(int mask) {
            this.mask = mask;
        }

        public boolean check(int flags) {
            return (flags & mask) == mask;
        }
    }
}
