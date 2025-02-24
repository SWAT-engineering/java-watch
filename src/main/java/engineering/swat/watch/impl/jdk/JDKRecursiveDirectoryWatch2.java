package engineering.swat.watch.impl.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

public class JDKRecursiveDirectoryWatch2 extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final Map<Path, JDKRecursiveDirectoryWatch2> childWatches = new ConcurrentHashMap<>();
    private final JDKBaseWatch delegate;

    public JDKRecursiveDirectoryWatch2(Path directory, Executor exec, BiConsumer<ActiveWatch, WatchEvent> eventHandler) {
        super(directory, exec, eventHandler);
        this.delegate = new JDKDirectoryWatch(directory, exec, updateChildWatches().andThen(eventHandler), false);
    }

    /**
     * @return An event handler that updates the child watches according to the
     * following rules: (a) when an overflow happens, it's propagated to each
     * existing child watch; (b) when a subdirectory creation happens, a new
     * child watch is opened for that subdirectory; (c) when a subdirectory
     * deletion happens, an existing child watch is closed for that
     * subdirectory.
     */
    private BiConsumer<ActiveWatch, WatchEvent> updateChildWatches() {
        return (watch, event) -> {
            var kind = event.getKind();

            if (kind == WatchEvent.Kind.OVERFLOW) {
                forEachChild(this::reportOverflowToChildWatch);
                return;
            }

            var child = event.calculateFullPath();
            var directory = child.toFile().isDirectory();

            if (kind == WatchEvent.Kind.CREATED && directory) {
                openChildWatch(child);
                // Events in the newly created directory (`child`) might have
                // been missed between its creation (`event`) and setting up its
                // watch. Erring on the side of caution, generate an overflow
                // event for the watch.
                reportOverflowToChildWatch(child);
            }

            if (kind == WatchEvent.Kind.DELETED && directory) {
                closeChildWatch(child);
            }
        };
    }

    private void openChildWatch(Path child) {
        var childWatch = new JDKRecursiveDirectoryWatch2(child, exec, (w, e) ->
            // Same as `eventHandler`, except each event is pre-processed such
            // that the last segment of the root path becomes the first segment
            // of the relative path. For instance, `foo/bar` (root path) and
            // `baz.txt` (relative path) are pre-processed to `foo` (root path)
            // and `bar/baz.txt` (relative path). This is to ensure the parent
            // directory of a child directory is reported as the root directory
            // of the event.
            eventHandler.accept(w, relativize(e))
        );

        if (childWatches.putIfAbsent(child, childWatch) == null) {
            try {
                childWatch.open();
            } catch (IOException e) {
                logger.error("Could not open (recursive) subdirectory watch for: {} ({})", child, e);
            }
        }
    }

    private void closeChildWatch(Path child) {
        var childWatch = childWatches.remove(child);
        if (childWatch != null) {
            try {
                childWatch.close();
            } catch (IOException e) {
                logger.error("Could not close (recursive) subdirectory watch for: {} ({})", child, e);
            }
        }
    }

    private void reportOverflowToChildWatch(Path child) {
        var childWatch = childWatches.get(child);
        if (childWatch != null) {
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, child);
            childWatch.handleEvent(overflow);
        }
    }

    private void forEachChild(Consumer<Path> action) {
        try (var children = Files.find(path, 1, (p, attrs) -> p != path && attrs.isDirectory())) {
            children.forEach(action);
        } catch (IOException e) {
            logger.error("Recursive directory watch (for: {}) could not iterate over its children ({})", path, e);
        }
    }

    // -- JDKBaseWatch --

    @Override
    public void handleEvent(WatchEvent event) {
        delegate.handleEvent(event);
    }

    @Override
    public synchronized void close() throws IOException {
        delegate.close();
        for (var childWatch : childWatches.values()) {
            childWatch.close();
        }
    }

    @Override
    protected synchronized void start() throws IOException {
        // It's important to open the watch for the parent first, before opening
        // watches for the children. Otherwise, new directories created while
        // the watches are being set up might remain unnoticed.
        delegate.open();
        forEachChild(this::openChildWatch);
    }
}
