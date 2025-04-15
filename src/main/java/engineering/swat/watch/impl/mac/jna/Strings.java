package engineering.swat.watch.impl.mac.jna;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

/**
 * Array of strings in native memory
 */
class Strings implements AutoCloseable {

    // Native APIs
    private static final CoreFoundation CF = CoreFoundation.INSTANCE;

    // Native memory
    private volatile @Nullable CFStringRef[] strings;
    private volatile @Nullable CFArrayRef array;

    private volatile boolean closed = false;

    public Strings(String... pathsToWatch) {
        // Allocate native memory
        this.strings = createCFStrings(pathsToWatch);
        this.array = createCFArray(strings);
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

        var values = new Memory(n * size);
        for (int i = 0; i < n; i++) {
            values.setPointer(i * size, strings[i].getPointer());
        }

        var alloc     = CF.CFAllocatorGetDefault();
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
            s.release();
        }
        array.release();

        strings = null;
        array = null;
    }
}
