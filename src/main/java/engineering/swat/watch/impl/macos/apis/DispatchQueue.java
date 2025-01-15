package engineering.swat.watch.impl.macos.apis;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * https://developer.apple.com/documentation/dispatch/dispatch_queue?language=objc
 */
public interface DispatchQueue extends Library {
    DispatchQueue INSTANCE = Native.load("c", DispatchQueue.class);

    interface DispatchFunction extends Callback { // dispatch_function_t
        void callback(        // void
            Pointer context); // void*
    }

    Pointer dispatch_queue_create( // dispatch_queue_t
        String  label,             // const char*
        Pointer attr);             // dispatch_queue_attr_t

    Pointer dispatch_get_global_queue( // dispatch_queue_global_t
        int identifier,                // intptr_t
        int flags);                    // uintptr_t

    String dispatch_queue_get_label( // const char*
        Pointer queue);              // dispatch_queue_t

    void dispatch_sync_f(       // void
        Pointer queue,          // dispatch_queue_t
        Pointer context,        // void*
        DispatchFunction work); // dispatch_function_t

    void dispatch_async_f(      // void
        Pointer queue,          // dispatch_queue_t
        Pointer context,        // void*
        DispatchFunction work); // dispatch_function_t


    void dispatch_release( //void
        Pointer object); //dispatch_object_t


}
