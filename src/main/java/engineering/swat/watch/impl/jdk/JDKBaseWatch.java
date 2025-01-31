package engineering.swat.watch.impl.jdk;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

public abstract class JDKBaseWatch implements ActiveWatch {
    private final Logger logger = LogManager.getLogger();

    protected final Path path;
    protected final Executor exec;
    protected final Consumer<WatchEvent> eventHandler;

    protected JDKBaseWatch(Path path, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.path = path;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    public void start() throws IOException {
        try {
            if (!runIfFirstTime()) {
                throw new IllegalStateException("Could not restart already-started watch for: " + path);
            }
            logger.debug("Started watch for: {}", path);
        } catch (Exception e) {
            throw new IOException("Could not start watch for: " + path, e);
        }
    }

    protected WatchEvent translate(java.nio.file.WatchEvent<?> jdkEvent) {
        WatchEvent.Kind kind;
        if (jdkEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
            kind = WatchEvent.Kind.CREATED;
        }
        else if (jdkEvent.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
            kind = WatchEvent.Kind.MODIFIED;
        }
        else if (jdkEvent.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            kind = WatchEvent.Kind.DELETED;
        }
        else if (jdkEvent.kind() == StandardWatchEventKinds.OVERFLOW) {
            kind = WatchEvent.Kind.OVERFLOW;
        }
        else {
            throw new IllegalArgumentException("Unexpected watch event: " + jdkEvent);
        }
        var rootPath = path;
        var relativePath = kind == WatchEvent.Kind.OVERFLOW ? rootPath : (@Nullable Path)jdkEvent.context();

        var event = new WatchEvent(kind, rootPath, relativePath);
        logger.trace("Translated: {} to {}", jdkEvent, event);
        return event;
    }

    /**
     * Runs this watch if it's the first time. Intended to be called by method
     * `start`.
     *
     * @return `true` iff it's the first time this method is called
     * @throws IOException When an I/O exception of some sort (e.g., a nested
     * watch failed to start) has occurred
     */
    protected abstract boolean runIfFirstTime() throws IOException;
}
