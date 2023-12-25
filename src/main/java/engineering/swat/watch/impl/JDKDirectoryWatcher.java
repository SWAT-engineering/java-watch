package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import engineering.swat.watch.WatchEvent;

public class JDKDirectoryWatcher implements Closeable {
    private final Logger logger = LogManager.getLogger();
    private final Path directory;
    private final Executor exec;
    private final Consumer<WatchEvent> eventHandler;
    private volatile @MonotonicNonNull Closeable activeWatch;

    public JDKDirectoryWatcher(Path directory, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.directory = directory;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    public void start() throws IOException {
        try {
            if (activeWatch != null) {
                // TODO make sure there is no cross thread race possible here.
                throw new IOException("Cannot start a watcher twice");
            }
            activeWatch = JDKPoller.INSTANCE.register(directory, this::handleChanges);
            logger.debug("Started watch for: {}", directory);
        } catch (IOException e) {
            throw new IOException("Could not register directory watcher for: " + directory, e);
        }
    }

    private void handleChanges(List<java.nio.file.WatchEvent<?>> events) {
        for (var ev: events) {
            logger.trace("Handling event: {} for {}", ev, directory);
            exec.execute(() -> eventHandler.accept(translate(ev)));
        }
    }

    private WatchEvent translate(java.nio.file.WatchEvent<?> ev) {
        WatchEvent.Kind kind;
        if (ev.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
            kind = WatchEvent.Kind.CREATED;
        }
        else if (ev.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
            kind = WatchEvent.Kind.MODIFIED;
        }
        else if (ev.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            kind = WatchEvent.Kind.DELETED;
        }
        else {
            throw new IllegalArgumentException("Unexpected watch event: " + ev);
        }
        var path = (Path)ev.context();
        if (path == null) {
            throw new IllegalArgumentException("Missing path in event: " + ev);
        }
        logger.trace("Translated: {} to {} at {}", ev, kind, path);
        return new WatchEvent(kind, directory, path);
    }

    @Override
    public void close() throws IOException {
        if (activeWatch != null) {
            logger.debug("Closing watch for: {}", this.directory);
            activeWatch.close();
        }
    }
}
