package engineering.swat.watch.impl.mac;

import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCreateFlag.FILE_EVENTS;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCreateFlag.NO_DEFER;
import static engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamCreateFlag.WATCH_ROOT;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFIndex;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;

import engineering.swat.watch.TestDirectory;
import engineering.swat.watch.impl.mac.apis.DispatchObjects;
import engineering.swat.watch.impl.mac.apis.DispatchQueue;
import engineering.swat.watch.impl.mac.apis.FileSystemEvents;
import engineering.swat.watch.impl.mac.apis.FileSystemEvents.FSEventStreamEventFlag;

class APIs {
    private static final Logger LOGGER = LogManager.getLogger();

    // Native APIs
    private static final CoreFoundation   CF  = CoreFoundation.INSTANCE;
    private static final DispatchObjects  DO  = DispatchObjects.INSTANCE;
    private static final DispatchQueue    DQ  = DispatchQueue.INSTANCE;
    private static final FileSystemEvents FSE = FileSystemEvents.INSTANCE;

    @Test
    void smokeTest() throws IOException {
        try (var test = new TestDirectory()) {
            var ready = new AtomicBoolean(false);
            var paths = ConcurrentHashMap.<String> newKeySet();

            var s = test.getTestDirectory().toString();
            var handler = (MinimalWorkingExample.EventHandler) (path, flags, id) -> {
                synchronized (ready) {
                    while (!ready.get()) {
                        try {
                            ready.wait();
                        } catch (InterruptedException e) {
                            LOGGER.error("Unexpected interrupt. Test likely to fail. Event ignored ({}).", prettyPrint(path, flags, id));
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                paths.remove(path);
            };

            try (var mwe = new MinimalWorkingExample(s, handler)) {
                var dir = test.getTestDirectory().toRealPath();
                paths.add(Files.writeString(dir.resolve("a.txt"), "foo").toString());
                paths.add(Files.writeString(dir.resolve("b.txt"), "bar").toString());
                paths.add(Files.createFile(dir.resolve("d.txt")).toString());

                synchronized (ready) {
                    ready.set(true);
                    ready.notifyAll();
                }

                await("The event handler has been called").until(paths::isEmpty);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        var s = "/Users/sungshik/Desktop/tmp";
        var handler = (MinimalWorkingExample.EventHandler) (path, flags, id) -> {
            LOGGER.info(prettyPrint(path, flags, id));
        };

        try (var mwe = new MinimalWorkingExample(s, handler)) {
            // Block the program from terminating until `ENTER` is pressed
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        }
    }

    private static String prettyPrint(String path, int flags, long id) {
        var flagsPrettyPrinted = Stream
            .of(FSEventStreamEventFlag.values())
            .filter(f -> (f.mask & flags) == f.mask)
            .map(Object::toString)
            .collect(Collectors.joining(", "));

        var format = "path: \"%s\", flags: [%s], id: %s";
        return String.format(format, path, flagsPrettyPrinted, id);
    }

    private static class MinimalWorkingExample implements Closeable {
        FileSystemEvents.FSEventStreamCallback callback;
        Pointer stream;
        Pointer queue;

        public MinimalWorkingExample(String s, EventHandler handler) {

            // Allocate singleton array of paths
            CFStringRef pathToWatch = CFStringRef.createCFString(s);
            CFArrayRef pathsToWatch = null;
            {
                var values = new Memory(Native.getNativeSize(CFStringRef.class));
                values.setPointer(0, pathToWatch.getPointer());
                pathsToWatch = CF.CFArrayCreate(
                    CF.CFAllocatorGetDefault(),
                    values,
                    new CFIndex(1),
                    null);
            } // Automatically free `values` when it goes out of scope

            // Allocate callback
            this.callback = (x1, x2, x3, x4, x5, x6) -> {
                var paths = x4.getStringArray(0, (int) x3);
                var flags = x5.getIntArray(0, (int) x3);
                var ids = x6.getLongArray(0, (int) x3);
                for (int i = 0; i < x3; i++) {
                    handler.handle(paths[i], flags[i], ids[i]);
                }
            };

            // Allocate stream
            this.stream = FSE.FSEventStreamCreate(
                CF.CFAllocatorGetDefault(),
                callback,
                Pointer.NULL,
                pathsToWatch,
                FSE.FSEventsGetCurrentEventId(),
                0.15,
                NO_DEFER.mask | WATCH_ROOT.mask | FILE_EVENTS.mask);

            // Deallocate array of paths
            pathsToWatch.release();
            pathToWatch.release();

            // Allocate queue
            this.queue = DQ.dispatch_queue_create("q", null);

            // Start stream
            FSE.FSEventStreamSetDispatchQueue(stream, queue);
            FSE.FSEventStreamStart(stream);
            FSE.FSEventStreamShow(stream);
        }

        @Override
        public void close() throws IOException {

            // Stop stream
            FSE.FSEventStreamStop(stream);
            FSE.FSEventStreamSetDispatchQueue(stream, Pointer.NULL);
            FSE.FSEventStreamInvalidate(stream);
            FSE.FSEventStreamRelease(stream);
            DO.dispatch_release(queue);

            // Deallocate queue, stream, and callback
            this.queue = null;
            this.stream = null;
            this.callback = null;
        }

        @FunctionalInterface
        private static interface EventHandler {
            void handle(String path, int flags, long id);
        }
    }
}
