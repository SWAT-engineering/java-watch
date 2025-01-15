package engineering.swat.watch.impl.macos.apis;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Interface for the "Dispatch Queue" API collection of the "Dispatch"
 * framework.
 *
 * https://developer.apple.com/documentation/dispatch/dispatch_queue?language=objc
 */
public interface DispatchQueue extends Library {
    DispatchQueue INSTANCE = Native.load("c", DispatchQueue.class);

    /*
     * https://developer.apple.com/documentation/dispatch/1453030-dispatch_queue_create?language=objc
     */
    Pointer dispatch_queue_create( // dispatch_queue_t
        String  label,             // const char*
        Pointer attr);             // dispatch_queue_attr_t
}
