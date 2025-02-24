/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2023, Swat.engineering
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package engineering.swat.watch.impl.jdk;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;

public class JDKRecursiveDirectoryWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final ConcurrentMap<Path, JDKDirectoryWatch> activeWatches = new ConcurrentHashMap<>();

    public JDKRecursiveDirectoryWatch(Path directory, Executor exec, Consumer<WatchEvent> eventHandler) {
        super(directory, exec, eventHandler);
    }

    private void processEvents(WatchEvent ev) {
        logger.trace("Forwarding event: {}", ev);
        eventHandler.accept(ev);
        logger.trace("Unwrapping event: {}", ev);
        switch (ev.getKind()) {
            case CREATED: handleCreate(ev); break;
            case DELETED: handleDeleteDirectory(ev); break;
            case OVERFLOW: handleOverflow(ev); break;
            case MODIFIED: break;
        }
    }

    private void publishExtraEvents(List<WatchEvent> ev) {
        logger.trace("Reporting new nested directories & files: {}", ev);
        ev.forEach(eventHandler);
    }


    private void handleCreate(WatchEvent ev) {
        // between the event and the current state of the file system
        // we might have some nested directories we missed
        // so if we have a new directory, we have to go in and iterate over it
        // we also have to report all nested files & dirs as created paths
        // but we don't want to delay the publication of this
        // create till after the processing is done, so we schedule it in the background
        var fullPath = ev.calculateFullPath();
        if (!activeWatches.containsKey(fullPath)) {
            CompletableFuture
                .completedFuture(fullPath)
                .thenApplyAsync(this::registerForNewDirectory, exec)
                .thenAcceptAsync(this::publishExtraEvents, exec)
                .exceptionally(ex -> {
                    logger.error("Could not locate new sub directories for: {}", ev.calculateFullPath(), ex);
                    return null;
                });
        }
    }

    private void handleOverflow(WatchEvent ev) {
        logger.info("Overflow detected, rescanning to find missed entries in {}", path);
        CompletableFuture
            .completedFuture(ev.calculateFullPath())
            .thenApplyAsync(this::syncAfterOverflow, exec)
            .thenAcceptAsync(this::publishExtraEvents, exec)
            .exceptionally(ex -> {
                logger.error("Could not register new watch for: {} ({})", ev.calculateFullPath(), ex);
                return null;
            });
    }

    private void handleDeleteDirectory(WatchEvent ev) {
        var removedPath = ev.calculateFullPath();
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

    /** Only register a watch for every sub directory */
    private class InitialDirectoryScan extends SimpleFileVisitor<Path> {
        protected final Path subRoot;

        public InitialDirectoryScan(Path root) {
            this.subRoot = root;
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
            var watch = activeWatches.computeIfAbsent(dir, d -> new JDKDirectoryWatch(d, exec, relocater(dir)));
            try {
                if (!watch.startIfFirstTime()) {
                    logger.debug("We lost the race on starting a nested watch, that shouldn't be a problem, but it's a very busy, so we might have lost a few events in {}", dir);
                }
            } catch (IOException ex) {
                activeWatches.remove(dir);
                logger.error("Could not register a watch for: {} ({})", dir, ex);
                throw ex;
            }
        }

        /** Make sure that the events are relative to the actual root of the recursive watch */
        private Consumer<WatchEvent> relocater(Path subRoot) {
            final Path newRelative = path.relativize(subRoot);
            return ev -> {
                var rewritten = new WatchEvent(ev.getKind(), path, newRelative.resolve(ev.getRelativePath()));
                processEvents(rewritten);
            };
        }
    }

    /** register watch for new sub-dir, but also simulate event for every file & subdir found */
    private class NewDirectoryScan extends InitialDirectoryScan {
        protected final List<WatchEvent> events;
        protected final Set<Path> seenFiles;
        protected final Set<Path> seenDirs;
        private boolean hasFiles = false;
        public NewDirectoryScan(Path subRoot, List<WatchEvent> events, Set<Path> seenFiles, Set<Path> seenDirs) {
            super(subRoot);
            this.events = events;
            this.seenFiles = seenFiles;
            this.seenDirs = seenDirs;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path subdir, BasicFileAttributes attrs) throws IOException {
            try {
                hasFiles = false;
                if (!seenDirs.contains(subdir)) {
                    if (!subdir.equals(subRoot)) {
                        events.add(new WatchEvent(WatchEvent.Kind.CREATED, path, path.relativize(subdir)));
                    }
                    return super.preVisitDirectory(subdir, attrs);
                }
                // our children might have newer results
                return FileVisitResult.CONTINUE;
            } finally {
                seenDirs.add(subdir);
            }
        }

        @Override
        public FileVisitResult postVisitDirectory(Path subdir, IOException exc) throws IOException {
            if (hasFiles) {
                events.add(new WatchEvent(WatchEvent.Kind.MODIFIED, path, path.relativize(subdir)));
            }
            return super.postVisitDirectory(subdir, exc);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (!seenFiles.contains(file)) {
                hasFiles = true;

                var relative = path.relativize(file);
                events.add(new WatchEvent(WatchEvent.Kind.CREATED, path, relative));
                if (attrs.size() > 0) {
                    events.add(new WatchEvent(WatchEvent.Kind.MODIFIED, path, relative));
                }
                seenFiles.add(file);
            }
            return FileVisitResult.CONTINUE;
        }
    }

    /** detect directories that aren't tracked yet, and generate events only for new entries */
    private class OverflowSyncScan extends NewDirectoryScan {
        private final Deque<Boolean> isNewDirectory = new ArrayDeque<>();
        public OverflowSyncScan(Path subRoot, List<WatchEvent> events, Set<Path> seenFiles, Set<Path> seenDirs) {
            super(subRoot, events, seenFiles, seenDirs);
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
            if (isNewDirectory.peekLast() == Boolean.TRUE || !seenFiles.contains(file)) {
                return super.visitFile(file, attrs);
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private void registerInitialWatches(Path dir) throws IOException {
        Files.walkFileTree(dir, new InitialDirectoryScan(dir));
    }

    private List<WatchEvent> registerForNewDirectory(Path dir) {
        var events = new ArrayList<WatchEvent>();
        var seenFiles = new HashSet<Path>();
        var seenDirectories = new HashSet<Path>();
        try {
            Files.walkFileTree(dir, new NewDirectoryScan(dir, events, seenFiles, seenDirectories));
            detectedMissingEntries(dir, events, seenFiles, seenDirectories);
            return events;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private List<WatchEvent> syncAfterOverflow(Path dir) {
        var events = new ArrayList<WatchEvent>();
        var seenFiles = new HashSet<Path>();
        var seenDirectories = new HashSet<Path>();
        try {
            Files.walkFileTree(dir, new OverflowSyncScan(dir, events, seenFiles, seenDirectories));
            detectedMissingEntries(dir, events, seenFiles, seenDirectories);
            return events;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void detectedMissingEntries(Path dir, ArrayList<WatchEvent> events, HashSet<Path> seenFiles, HashSet<Path> seenDirectories) throws IOException {
        // why a second round? well there is a race, between iterating the directory (and sending events)
        // and when the watches are active. so after we know all the new watches have been registered
        // we do a second scan and make sure to find paths that weren't visible the first time
        // and emulate events for them (and register new watches)
        // In essence this is the same as when an Overflow happened, so we can reuse that handler.
        int directoryCount = seenDirectories.size() - 1;
        while (directoryCount != seenDirectories.size()) {
            Files.walkFileTree(dir, new OverflowSyncScan(dir, events, seenFiles, seenDirectories));
            directoryCount = seenDirectories.size();
        }
    }

    // -- JDKBaseWatch --

    @Override
    public void handleEvent(WatchEvent event) {
        processEvents(event);
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
            catch (Exception ex) {
                logger.error("Could not close watch", ex);
                if (firstFail == null) {
                    firstFail = new IOException("Unexpected exception when closing", ex);
                }
            }
        }
        if (firstFail != null) {
            throw firstFail;
        }
    }

    @Override
    protected void start() throws IOException {
        logger.debug("Running recursive watch for: {}", path);
        registerInitialWatches(path);
    }
}
