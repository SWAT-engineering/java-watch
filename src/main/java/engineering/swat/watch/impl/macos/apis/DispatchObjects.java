package engineering.swat.watch.impl.macos.apis;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Interface for the "Dispatch Objects" API collection of the "Dispatch"
 * framework.
 *
 * https://developer.apple.com/documentation/dispatch/dispatch_objects?language=objc
 */
public interface DispatchObjects extends Library {
    DispatchObjects INSTANCE = Native.load("c", DispatchObjects.class);

    /*
     * https://developer.apple.com/documentation/dispatch/1496328-dispatch_release?language=objc
     */
    void dispatch_release( // void
        Pointer object);   // dispatch_object_t
}
