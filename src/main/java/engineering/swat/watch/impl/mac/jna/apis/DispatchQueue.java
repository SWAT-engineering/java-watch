package engineering.swat.watch.impl.mac.jna.apis;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Interface for the "Dispatch Queue" API collection of the "Dispatch"
 * framework.
 *
 * @see https://developer.apple.com/documentation/dispatch/dispatch_queue?language=objc
 */
public interface DispatchQueue extends Library {
    DispatchQueue INSTANCE = Native.load("c", DispatchQueue.class);

    /**
     * @param label {@code dispatch_queue_t}
     * @param attr  {@code const char*}
     * @return      {@code dispatch_queue_t}
     * @see https://developer.apple.com/documentation/dispatch/1453030-dispatch_queue_create?language=objc
     */
    Pointer dispatch_queue_create(String label, Pointer attr);
}
