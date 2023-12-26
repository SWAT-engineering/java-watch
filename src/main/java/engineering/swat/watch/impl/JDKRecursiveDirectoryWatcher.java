package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
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

    public void start(WatchEvent.Kind... eventKinds) throws IOException {
        try {
            var hadCreate = contains(eventKinds, Kind.CREATED);
            var hadModify = contains(eventKinds, Kind.MODIFIED);
            var hadDelete = contains(eventKinds, Kind.DELETED);

            var modifiedKinds = new Kind[hadModify ? 3 : 2];
            modifiedKinds[0] = Kind.CREATED;
            modifiedKinds[1] = Kind.DELETED;
            if (hadModify) {
                modifiedKinds[2] = Kind.MODIFIED;
            }
            logger.debug("Starting recursive watch for: {} watching {}", directory, modifiedKinds);
            startRecursive(directory, wrappedHandler(hadCreate, hadModify, hadDelete, modifiedKinds), modifiedKinds);
        } catch (IOException e) {
            throw new IOException("Could not register directory watcher for: " + directory, e);
        }
    }

    private Consumer<WatchEvent> wrappedHandler(boolean hadCreate, boolean hadModify, boolean hadDelete, Kind[] kinds) {
        return ev -> {
            logger.trace("Unwrapping event: {}", ev);
            switch (ev.getKind()) {
                case CREATED:
                    addNewDirectoryWatch(hadCreate, hadModify, hadDelete, kinds, ev);
                    if (hadCreate) {
                        eventHandler.accept(ev);
                    }
                    break;
                case DELETED:
                    handleDeleteDirectory(ev);
                    if (hadDelete) {
                        eventHandler.accept(ev);
                    }
                    break;
                case MODIFIED:
                    if (hadModify) {
                        eventHandler.accept(ev);
                    }
                    break;
            }
        };
    }

    private void handleDeleteDirectory(WatchEvent ev) {
        var removedPath = ev.calculateFullPath();
        var existingWatch = activeWatches.get(removedPath);
        try {
            if (existingWatch != null) {
                logger.debug("Clearing watch on removed directory: {}", removedPath);
                existingWatch.close();
            }
        } catch (IOException ex) {
            logger.error("Error clearing: {} {}", removedPath, ex);
        }
    }

    private void addNewDirectoryWatch(boolean hadCreate, boolean hadModify, boolean hadDelete, Kind[] kinds, WatchEvent ev) {
        var newPath = ev.calculateFullPath();
        if (Files.isDirectory(newPath)) {
            try {
                logger.debug("New directory found, adding a watch for it: {}", newPath);
                startRecursive(newPath, wrappedHandler(hadCreate, hadModify, hadDelete, kinds), kinds);
            } catch (IOException ex) {
                logger.error("Error adding watch for: {} {}", newPath, ex);
            }
        }
    }

    private void startRecursive(Path root, Consumer<WatchEvent> handler, Kind[] modifiedKinds) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                logger.error("We could not visit {} to schedule recursive file watches: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                var watcher = new JDKDirectoryWatcher(dir, exec, handler);
                activeWatches.put(dir, watcher);
                try {
                    watcher.start(modifiedKinds);
                    return FileVisitResult.CONTINUE;
                } catch (IOException ex) {
                    activeWatches.remove(dir);
                    throw ex;
                }
            }


            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    logger.error("Error during directory iteration: {} = {}", dir, exc);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }




    private boolean contains(Kind[] eventKinds, Kind needle) {
        for (var k : eventKinds) {
            if (k == needle) {
                return true;
            }
        }
        return false;
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
