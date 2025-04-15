// package engineering.swat.watch.impl.mac;

// import java.io.BufferedReader;
// import java.io.IOException;
// import java.io.InputStreamReader;
// import java.nio.file.FileSystem;
// import java.nio.file.Path;
// import java.util.concurrent.atomic.AtomicInteger;
// import java.util.concurrent.atomic.AtomicLong;

// import com.sun.jna.Memory;
// import com.sun.jna.Native;
// import com.sun.jna.Pointer;
// import com.sun.jna.platform.mac.CoreFoundation;
// import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
// import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
// import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

// import engineering.swat.watch.impl.mac.apis.DispatchQueue;
// import engineering.swat.watch.impl.mac.apis.FileSystemEvents;
// import engineering.swat.watch.impl.mac.nio.file.MacWatchable;
// import engineering.swat.watch.impl.mac.nio.file.Stream;

// public class Main {

//     public static void hello_world() throws IOException {

//         // Dispatch
//         Pointer queue;
//         queue = DispatchQueue.INSTANCE.dispatch_queue_create("q", null);

//         // Core Services

//         AtomicLong x = new AtomicLong();
//         FileSystemEvents.FSEventStreamCallback g = (x1, x2, n, x4, x5, x6) -> {
//             // System.out.println("x: " + x.addAndGet(n));



//             var paths = x4.getStringArray(0, (int) n);
//             var flags = x5.getIntArray(0, (int) n);
//             var ids = x6.getLongArray(0, (int) n);

//             for (int i = 0; i < n; i++) {
//                 System.out.println("" + paths[i]);
//             }
//         };

//         CFStringRef path = CFStringRef.createCFString("/Users/sungshik/Desktop/tmp");

//         Memory memory = new Memory(Native.getNativeSize(CFStringRef.class));
//         memory.setPointer(0, path.getPointer());
//         CFArrayRef pathsToWatch = CoreFoundation.INSTANCE.CFArrayCreate(null, memory, new CFIndex(1), null);

//         long sinceWhen = FileSystemEvents.INSTANCE.FSEventsGetCurrentEventId(); // kFSEventStreamEventIdSinceNow
//         int flags = 22;

//         Pointer stream = FileSystemEvents.INSTANCE.FSEventStreamCreate(null, g, null, pathsToWatch, sinceWhen, 0.15, flags);
//         // FileSystemEvents.INSTANCE.FSEventStreamShow(stream);

//         FileSystemEvents.INSTANCE.FSEventStreamSetDispatchQueue(stream, queue);
//         FileSystemEvents.INSTANCE.FSEventStreamStart(stream);

//         // readLine();
//         // System.exit(0);
//     }


//     public static void main(String[] args) throws IOException {

//         hello_world();
//         readLine();
//         System.exit(0);

//         var path = "";
//         path = "/Users/sungshik/Desktop/java-watch/src/main/java/engineering/swat/watch/impl/mac/Main.java";
//         path = "/Users/sungshik/Desktop/tmp";

//         var stream = new Stream(events -> {
//             // var s = "";
//             // for (var e : events) {
//             //     s += "\n" + e;
//             // }
//             // if (!s.isEmpty()) {
//             //     System.out.println("\nEvents: " + s);
//             // }
//         }, new MacWatchable(Path.of(path)));

//         readLine();

//         stream.close();
//     }

//     public static void readLine() throws IOException {
//         new BufferedReader(new InputStreamReader(System.in)).readLine();
//     }
// }
