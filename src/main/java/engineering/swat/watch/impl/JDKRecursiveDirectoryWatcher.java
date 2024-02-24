package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchEvent.Kind;

public class JDKRecursiveDirectoryWatcher implements Closeable {
    private final Logger logger = LogManager.getLogger();
    private final Path directory;
    private final Executor exec;
    private final Consumer<WatchEvent> eventHandler;
    private final ConcurrentMap<Path, Closeable> activeWatches = new ConcurrentHashMap<>();

    public JDKRecursiveDirectoryWatcher(Path directory, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.directory = directory;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    public void start() throws IOException {
        try {
            logger.debug("Starting recursive watch for: {}", directory);
            startRecursive(directory, false);
        } catch (IOException e) {
            throw new IOException("Could not register directory watcher for: " + directory, e);
        }
    }

    private void wrappedHandler(WatchEvent ev) {
        logger.trace("Unwrapping event: {}", ev);
        try {
            if (ev.getKind() == Kind.CREATED) {
                // between the event and the current state of the file system
                // we might have some nested directories we missed
                // so if we have a new directory, we have to go in and iterate over it
                // we also have to report all nested files & dirs as created paths
                // but we don't want to burden ourselves with those events
                try {
                    var newEvents = startRecursive(ev.calculateFullPath(), true);
                    logger.trace("Reporting new nested directories & files: {}", newEvents);
                    exec.execute(() -> newEvents.forEach(eventHandler));
                } catch (IOException e) {
                    logger.error("Could not register new watch for: {} ({})", ev.calculateFullPath(), e);
                }
            }
            else if (ev.getKind() == Kind.DELETED) {
                handleDeleteDirectory(ev.calculateFullPath());
            }
        } finally {
            eventHandler.accept(ev);
        }
    }

    private void handleDeleteDirectory(Path removedPath) {
        try {
            var existingWatch = activeWatches.remove(removedPath);
            if (existingWatch != null) {
                logger.debug("Clearing watch on removed directory: {}", removedPath);
                existingWatch.close();
            }
        } catch (IOException ex) {
            logger.error("Error clearing: {} {}", removedPath, ex);
        }
    }

    private List<WatchEvent> startRecursive(Path dir, boolean collectCreates) throws IOException {
        var events = new ArrayList<WatchEvent>();
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                logger.error("We could not visit {} to schedule recursive file watches: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) throws IOException {
                addNewDirectory(subdir);
                if (collectCreates && !dir.equals(subdir)) {
                    events.add(new WatchEvent(WatchEvent.Kind.CREATED, directory, directory.relativize(subdir)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path subdir, IOException exc) throws IOException {
                if (exc != null) {
                    logger.error("Error during directory iteration: {} = {}", subdir, exc);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (collectCreates) {
                    events.add(new WatchEvent(WatchEvent.Kind.CREATED, directory, directory.relativize(file)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return events;
    }

    private void addNewDirectory(Path dir) throws IOException {
        var watcher = new JDKDirectoryWatcher(dir, exec, this::wrappedHandler);
        activeWatches.put(dir, watcher);
        try {
            watcher.start();
        } catch (IOException ex) {
            activeWatches.remove(dir);
            logger.error("Could not register a watch for: {} ({})", dir, ex);
            throw ex;
        }
    }

    @Override
    public void close() throws IOException {
        IOException firstFail = null;
        for (var e : activeWatches.entrySet()) {
            try {
                e.getValue().close();
            } catch (IOException ex) {
                logger.error("Could not close watch", ex);
                if (firstFail == null) {
                    firstFail = ex;
                }
            }
        }
        if (firstFail != null) {
            throw firstFail;
        }
    }
}
