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
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class JDKRecursiveDirectoryWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final ConcurrentMap<Path, JDKDirectoryWatch> activeWatches = new ConcurrentHashMap<>();

    public JDKRecursiveDirectoryWatch(Path directory, Executor exec, BiConsumer<EventHandlingWatch, WatchEvent> eventHandler) {
        super(directory, exec, eventHandler);
    }

    private void processEvents(EventHandlingWatch w, WatchEvent ev) {
        logger.trace("Forwarding event: {}", ev);
        eventHandler.accept(w, ev);
        logger.trace("Unwrapping event: {}", ev);
        switch (ev.getKind()) {
            case CREATED: handleCreate(ev); break;
            case DELETED: handleDeleteDirectory(ev); break;
            case OVERFLOW: handleOverflow(ev); break;
            case MODIFIED: break;
        }
    }

    private void handleCreate(WatchEvent ev) {
        // between the event and the current state of the file system
        // we might have some nested directories we missed
        // so if we have a new directory, we have to go in and iterate over it
        // we also have to report all nested files & dirs as created paths
        // but we don't want to delay the publication of this
        // create till after the processing is done, so we schedule it in the background
        var fullPath = ev.calculateFullPath();
        if (!activeWatches.containsKey(fullPath) && Files.isDirectory(fullPath)) {
            CompletableFuture
                .runAsync(() -> {
                    try {
                        addNewDirectory(fullPath);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, exec)
                .thenRunAsync(() -> reportOverflow(fullPath), exec)
                .exceptionally(ex -> {
                    logger.error("Could not locate new sub directories for: {}", fullPath, ex);
                    return null;
                });
        }
    }

    private void handleOverflow(WatchEvent ev) {
        var fullPath = ev.calculateFullPath();
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return Files.find(fullPath, 1, (p, attrs) -> p != fullPath && attrs.isDirectory());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, exec)
            .whenCompleteAsync((children, ex) -> {
                try {
                    if (ex == null) {
                        children.forEach(JDKRecursiveDirectoryWatch.this::reportOverflow);
                    } else {
                        logger.error("Could not handle overflow for: {} ({})", fullPath, ex);
                    }
                } finally {
                    children.close();
                }
            }, exec);
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
    private BiConsumer<EventHandlingWatch, WatchEvent> relocater(Path subRoot) {
        final Path newRelative = path.relativize(subRoot);
        return (w, ev) -> {
            var rewritten = new WatchEvent(ev.getKind(), path, newRelative.resolve(ev.getRelativePath()));
            processEvents(w, rewritten);
        };
    }

    private void registerInitialWatches(Path dir) throws IOException {
        Files.walkFileTree(dir, new InitialDirectoryScan(dir));
        reportOverflow(dir);
    }

    private void reportOverflow(Path p) {
        var w = activeWatches.get(p);
        if (w != null) {
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, p);
            w.handleEvent(overflow);
        }
    }

    // -- JDKBaseWatch --

    @Override
    public WatchScope getScope() {
        return WatchScope.PATH_AND_ALL_DESCENDANTS;
    }

    @Override
    public void handleEvent(WatchEvent event) {
        processEvents(this, event);
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
