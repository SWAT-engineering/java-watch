package engineering.swat.watch.impl.mac.apis;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Interface for the "Dispatch Objects" API collection of the "Dispatch"
 * framework.
 *
 * @see https://developer.apple.com/documentation/dispatch/dispatch_objects?language=objc
 */
public interface DispatchObjects extends Library {
    DispatchObjects INSTANCE = Native.load("c", DispatchObjects.class);

    /**
     * @param object {@code dispatch_object_t}
     * @see https://developer.apple.com/documentation/dispatch/1496328-dispatch_release?language=objc
     */
    void dispatch_release(Pointer object);
}
