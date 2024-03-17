package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

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
            registerInitialWatches(directory);
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
                    var newEvents = registerForNewDirectory(ev.calculateFullPath());
                    logger.trace("Reporting new nested directories & files: {}", newEvents);
                    exec.execute(() -> newEvents.forEach(eventHandler));
                } catch (IOException e) {
                    logger.error("Could not register new watch for: {} ({})", ev.calculateFullPath(), e);
                }
            }
            else if (ev.getKind() == Kind.DELETED) {
                handleDeleteDirectory(ev.calculateFullPath());
            }
            else if (ev.getKind() == Kind.OVERFLOW) {
                try {
                    logger.debug("Overflow detected, rescanning to find missed entries in {}", directory);
                    // we have to rescan everything, and at least make sure to add new entries to that recursive watcher
                    var newEntries = syncAfterOverflow(directory);
                    logger.trace("Reporting new nested directories & files: {}", newEntries);
                    exec.execute(() -> newEntries.forEach(eventHandler));
                } catch (IOException e) {
                    logger.error("Could not register new watch for: {} ({})", ev.calculateFullPath(), e);
                }
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

    /** Only register a watched for every sub directory */
    private class InitialDirectoryScan extends SimpleFileVisitor<Path> {
        protected final Path root;

        public InitialDirectoryScan(Path root) {
            this.root = root;
        }
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            logger.error("We could not visit {} to schedule recursive file watches: {}", file, exc);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) throws IOException {
            addNewDirectory(subdir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path subdir, IOException exc) throws IOException {
            if (exc != null) {
                logger.error("Error during directory iteration: {} = {}", subdir, exc);
            }
            return FileVisitResult.CONTINUE;
        }
        private void addNewDirectory(Path dir) throws IOException {
            var watcher = new JDKDirectoryWatcher(dir, exec, JDKRecursiveDirectoryWatcher.this::wrappedHandler);
            var oldEntry = activeWatches.put(dir, watcher);
            cleanupOld(dir, oldEntry);
            try {
                watcher.start();
            } catch (IOException ex) {
                activeWatches.remove(dir);
                logger.error("Could not register a watch for: {} ({})", dir, ex);
                throw ex;
            }
        }

        private void cleanupOld(Path dir, @Nullable Closeable oldEntry) {
            if (oldEntry != null) {
                logger.error("Registered a watch for a directory that was already watched: {}", dir);
                try {
                    oldEntry.close();
                } catch (IOException ex) {
                    logger.error("Could not close old watch for: {} ({})", dir, ex);
                }
            }
        }
    }

    /** register watch for new sub-dir, but also simulate event for every file & subdir found */
    private class NewDirectoryScan extends InitialDirectoryScan {
        protected final List<WatchEvent> events;
        public NewDirectoryScan(Path root, List<WatchEvent> events) {
            super(root);
            this.events = events;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) throws IOException {
            if (!subdir.equals(root)) {
                events.add(new WatchEvent(WatchEvent.Kind.CREATED, root, root.relativize(subdir)));
            }
            return super.preVisitDirectory(subdir, attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            events.add(new WatchEvent(WatchEvent.Kind.CREATED, root, root.relativize(file)));
            return FileVisitResult.CONTINUE;
        }
    }

    /** detect directories that aren't tracked yet, and generate events only for new entries */
    private class OverflowSyncScan extends NewDirectoryScan {
        private final Deque<Boolean> isNewDirectory = new ArrayDeque<>();
        public OverflowSyncScan(Path root, List<WatchEvent> events) {
            super(root, events);
        }
        @Override
        public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) throws IOException {
            if (!activeWatches.containsKey(subdir)) {
                isNewDirectory.addLast(true);
                return super.preVisitDirectory(subdir, attrs);
            }
            isNewDirectory.addLast(false);
            return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult postVisitDirectory(Path subdir, IOException exc) throws IOException {
            isNewDirectory.removeLast();
            return super.postVisitDirectory(subdir, exc);
        }
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (isNewDirectory.peekLast() == Boolean.TRUE) {
                return super.visitFile(file, attrs);
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private void registerInitialWatches(Path dir) throws IOException {
        Files.walkFileTree(dir, new InitialDirectoryScan(dir));
    }

    private List<WatchEvent> registerForNewDirectory(Path dir) throws IOException {
        var events = new ArrayList<WatchEvent>();
        Files.walkFileTree(dir, new NewDirectoryScan(dir, events));
        return events;
    }

    private List<WatchEvent> syncAfterOverflow(Path dir) throws IOException {
        var events = new ArrayList<WatchEvent>();
        Files.walkFileTree(dir, new OverflowSyncScan(dir, events));
        return events;
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
