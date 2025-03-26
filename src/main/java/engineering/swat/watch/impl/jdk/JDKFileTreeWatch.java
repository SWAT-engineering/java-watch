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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class JDKFileTreeWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final Path rootPath;
    private final Path relativePathParent;
    private final Map<Path, JDKFileTreeWatch> childWatches = new ConcurrentHashMap<>();
    private final JDKBaseWatch internal;

    public JDKFileTreeWatch(Path fullPath, Executor exec,
            BiConsumer<EventHandlingWatch, WatchEvent> eventHandler,
            Predicate<WatchEvent> eventFilter) {

        this(fullPath, Path.of(""), exec, eventHandler, eventFilter);
    }

    public JDKFileTreeWatch(Path rootPath, Path relativePathParent, Executor exec,
            BiConsumer<EventHandlingWatch, WatchEvent> eventHandler,
            Predicate<WatchEvent> eventFilter) {

        super(rootPath.resolve(relativePathParent), exec, eventHandler, eventFilter);
        this.rootPath = rootPath;
        this.relativePathParent = relativePathParent;

        var internalEventHandler = eventHandler.andThen(new AsyncChildWatchesUpdater());
        this.internal = new JDKDirectoryWatch(path, exec, internalEventHandler, eventFilter) {

            // Override to ensure that this watch relativizes events wrt
            // `rootPath` (instead of `path`, as is the default behavior)
            @Override
            public WatchEvent relativize(WatchEvent event) {
                var relativePath = relativePathParent;

                // Append a file name to `relativePath` if it exists
                var fullPath = event.calculateFullPath();
                if (!fullPath.equals(path)) {
                    var fileName = fullPath.getFileName();
                    if (fileName != null) {
                        relativePath = relativePath.resolve(fileName);
                    }
                }

                return new WatchEvent(event.getKind(), rootPath, relativePath);
            }

            // Override to ensure that this watch translates JDK events using
            // `rootPath` (instead of `path`, as is the default behavior).
            // Events returned by this method do not need to be relativized.
            @Override
            protected WatchEvent translate(java.nio.file.WatchEvent<?> jdkEvent) {
                var kind = translate(jdkEvent.kind());

                Path relativePath = null;
                if (kind != WatchEvent.Kind.OVERFLOW) {
                    var child = (Path) jdkEvent.context();
                    if (child != null) {
                        relativePath = relativePathParent.resolve(child);
                    }
                }

                var event = new WatchEvent(kind, rootPath, relativePath);
                logger.trace("Translated: {} to {}", jdkEvent, event);
                return event;
            }
        };
    }

    /**
     * Event handler that asynchronously (using {@link JDKBaseWatch#exec})
     * updates the child watches according to the following rules: (a) when an
     * overflow happens, the directory is rescanned, new child watches for
     * created subdirectories are opened, existing child watches for deleted
     * subdirectories are closed, and the overflow is propagated to each child
     * watch; (b) when a subdirectory creation happens, a new child watch is
     * opened for that subdirectory; (c) when a subdirectory deletion happens,
     * an existing child watch is closed for that subdirectory.
     */
    private class AsyncChildWatchesUpdater implements BiConsumer<EventHandlingWatch, WatchEvent> {
        @Override
        public void accept(EventHandlingWatch watch, WatchEvent event) {
            exec.execute(() -> {
                switch (event.getKind()) {
                    case OVERFLOW: acceptOverflow(); break;
                    case CREATED: getFileNameAndThen(event, this::acceptCreated); break;
                    case DELETED: getFileNameAndThen(event, this::acceptDeleted); break;
                    case MODIFIED: break;
                }
            });
        }

        private void getFileNameAndThen(WatchEvent event, Consumer<Path> consumer) {
            var child = event.getFileName();
            if (child != null) {
                consumer.accept(child);
            } else {
                logger.error("Could not get file name of event: {}", event);
            }
        }

        private void acceptOverflow() {
            syncChildWatchesWithFileSystem();
            for (var childWatch : childWatches.values()) {
                reportOverflowTo(childWatch);
            }
        }

        private void acceptCreated(Path child) {
            if (Files.isDirectory(path.resolve(child))) {
                var childWatch = openChildWatch(child);
                // Events in the newly created directory might have been missed
                // between its creation and setting up its watch. So, generate
                // an `OVERFLOW` event for the watch.
                reportOverflowTo(childWatch);
            }
        }

        private void acceptDeleted(Path child) {
            tryCloseChildWatch(child);
        }

        private void reportOverflowTo(JDKFileTreeWatch childWatch) {
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW,
                childWatch.rootPath, childWatch.relativePathParent);
            childWatch.handleEvent(overflow);
        }
    }

    private void syncChildWatchesWithFileSystem() {
        var toBeClosed = new HashSet<>(childWatches.keySet());

        try (var children = Files.find(path, 1, (p, attrs) -> p != path && attrs.isDirectory())) {
            children.forEach(p -> {
                var child = p.getFileName();
                if (child != null) {
                    toBeClosed.remove(child);
                    openChildWatch(child);
                } else {
                    logger.error("File tree watch (for: {}) could not open a child watch for: {}", path, p);
                }
            });
        } catch (IOException e) {
            logger.error("File tree watch (for: {}) could not iterate over its children ({})", path, e);
        }

        for (var child : toBeClosed) {
            tryCloseChildWatch(child);
        }
    }

    private JDKFileTreeWatch openChildWatch(Path child) {
        assert !child.isAbsolute();

        Function<Path, JDKFileTreeWatch> newChildWatch = p -> new JDKFileTreeWatch(
            rootPath, relativePathParent.resolve(child), exec, eventHandler, eventFilter);
        var childWatch = childWatches.computeIfAbsent(child, newChildWatch);
        try {
            childWatch.startIfFirstTime();
        } catch (IOException e) {
            logger.error("Could not open (nested) file tree watch for: {} ({})", child, e);
        }
        return childWatch;
    }

    private void tryCloseChildWatch(Path child) {
        try {
            closeChildWatch(child);
        } catch (IOException e) {
            logger.error("Could not close (nested) file tree watch for: {} ({})", path.resolve(child), e);
        }
    }

    private void closeChildWatch(Path child) throws IOException {
        assert !child.isAbsolute();

        var childWatch = childWatches.remove(child);
        if (childWatch != null) {
            childWatch.close();
        }
    }

    private @Nullable IOException tryClose(Closeable c) {
        try {
            c.close();
            return null;
        } catch (IOException ex) {
            logger.error("Could not close watch", ex);
            return ex;
        } catch (Exception ex) {
            logger.error("Could not close watch", ex);
            return new IOException("Unexpected exception when closing", ex);
        }
    }

    // -- JDKBaseWatch --

    @Override
    public WatchScope getScope() {
        return WatchScope.PATH_AND_ALL_DESCENDANTS;
    }

    @Override
    public void handleEvent(WatchEvent event) {
        internal.handleEvent(event);
    }

    @Override
    public synchronized void close() throws IOException {
        var firstFail = tryClose(internal);
        for (var c : childWatches.values()) {
            var currentFail = tryClose(c);
            if (currentFail != null && firstFail == null) {
                firstFail = currentFail;
            }
        }
        if (firstFail != null) {
            throw firstFail;
        }
    }

   @Override
    protected synchronized void start() throws IOException {
        internal.open();
        syncChildWatchesWithFileSystem();
        // There's no need to report an overflow event, because `internal` was
        // opened *before* the file system was accessed to fetch children. Thus,
        // if a new directory is created while this method is running, then at
        // least one of the following is true: (a) the new directory is already
        // visible by the time the file system is accessed; (b) its `CREATED`
        // event is handled later, which starts a new child watch if needed.
    }
}
