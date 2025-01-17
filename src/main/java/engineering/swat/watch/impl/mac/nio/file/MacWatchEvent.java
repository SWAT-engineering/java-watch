package engineering.swat.watch.impl.mac.nio.file;

import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagEventIdsWrapped;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagHistoryDone;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemChangeOwner;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemCloned;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemCreated;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemFinderInfoMod;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemInodeMetaMod;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemIsDir;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemIsFile;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemIsHardlink;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemIsLastHardlink;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemIsSymlink;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemModified;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemRemoved;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemRenamed;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagItemXattrMod;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagKernelDropped;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagMount;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagMustScanSubDirs;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagOwnEvent;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagRootChanged;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagUnmount;
import static engineering.swat.watch.impl.mac.jna.FileSystemEvents.FSEventStreamEventFlags.kFSEventStreamEventFlagUserDropped;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;

public class MacWatchEvent implements WatchEvent<Path> {
    private final MacWatchable watchable;
    private final String path;
    private final int flags;
    private final long id;

    public MacWatchEvent(MacWatchable watchable, String path, int flags, long id) {
        this.watchable = watchable;
        this.path = path;
        this.flags = flags;
        this.id = id;
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

        public static int all(Flag... flags) {
            var ret = 0x00000000;
            for (var f : flags) {
                ret |= f.mask;
            }
            return ret;
        }
    }

    // -- Object --

    @Override
    public String toString() {
        var s = "";
        for (var f : Flag.values()) {
            if (f.check(flags)) {
                s += ", " + f.name();
            }
        }
        s = !s.isEmpty() ? s.substring(2) : s;
        s = path + ", [" + s + "], " + id;
        return s;
    }

    // -- WatchEvent --

    @Override
    public Kind<Path> kind() {
        if (isEntryCreate()) {
            return ENTRY_CREATE;
        }
        if (isEntryDelete()) {
            return ENTRY_DELETE;
        }
        if (isEntryModify()) {
            return ENTRY_MODIFY;
        }
        return null;
    }

    @Override
    public int count() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path context() {
        var root = watchable.getPath();
        try {
            root = root.toRealPath(); // Resolve symlinks, best-effort
        } catch (IOException _e) {
        }
        var leaf = Path.of(path);
        return root.relativize(leaf);
    }

    private boolean isEntryCreate() {
        return Flag.ITEM_CREATED.check(flags);
    }

    private boolean isEntryDelete() {
        // TODO: Check move to recycle bin?
        return Flag.ITEM_REMOVED.check(flags);
    }

    private boolean isEntryModify() {
        return Flag.ITEM_MODIFIED.check(flags)
                || (Flag.ITEM_INODE_META_MOD.check(flags) && Flag.ITEM_IS_DIR.check(flags));
    }
}
