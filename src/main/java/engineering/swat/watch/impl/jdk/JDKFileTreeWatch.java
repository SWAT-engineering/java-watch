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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.initialization.qual.NotOnlyInitialized;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.impl.EventHandlingWatch;

public class JDKFileTreeWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final Map<Path, JDKFileTreeWatch> childWatches = new ConcurrentHashMap<>();
    private final @NotOnlyInitialized JDKBaseWatch internal;

    public JDKFileTreeWatch(Path root, Executor exec,
            BiConsumer<EventHandlingWatch, WatchEvent> eventHandler) {

        super(root, exec, eventHandler);
        var internalEventHandler = eventHandler.andThen(new ChildWatchesUpdater());
        this.internal = new JDKDirectoryWatch(root, exec, internalEventHandler);
    }

    /**
     * Event handler that updates the child watches according to the following
     * rules: (a) when an overflow happens, it's propagated to each existing
     * child watch; (b) when a subdirectory creation happens, a new child watch
     * is opened for that subdirectory; (c) when a subdirectory deletion
     * happens, an existing child watch is closed for that subdirectory.
     */
    private class ChildWatchesUpdater implements BiConsumer<EventHandlingWatch, WatchEvent> {
        @Override
        public void accept(EventHandlingWatch watch, WatchEvent event) {
            switch (event.getKind()) {
                case OVERFLOW:
                    reportOverflowToChildWatches();
                    break;
                case CREATED:
                    var childWatch = openChildWatch(event.calculateFullPath());
                    // Events in the newly created directory might have been
                    // missed between its creation and setting up its watch. So,
                    // generate an overflow event for the watch.
                    reportOverflowTo(childWatch);
                    break;
                case DELETED:
                    closeChildWatch(event.calculateFullPath());
                    break;
                case MODIFIED:
                    break;
            }
        }

        private void reportOverflowToChildWatches() {
            for (var childWatch : childWatches.values()) {
                reportOverflowTo(childWatch);
            }
        }

        private void reportOverflowTo(EventHandlingWatch childWatch) {
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, childWatch.getPath());
            childWatch.handleEvent(overflow);
        }
    }

    private JDKFileTreeWatch openChildWatch(Path child) {
        Function<Path, JDKFileTreeWatch> newChildWatch = p -> new JDKFileTreeWatch(child, exec, (w, e) ->
            // Same as `eventHandler`, except each event is pre-processed such
            // that the last segment of the root path becomes the first segment
            // of the relative path. For instance, `foo/bar` (root path) and
            // `baz.txt` (relative path) are pre-processed to `foo` (root path)
            // and `bar/baz.txt` (relative path). This is to ensure the parent
            // directory of a child directory is reported as the root directory
            // of the event.
            eventHandler.accept(w, relativize(e))
        );

        var childWatch = childWatches.computeIfAbsent(child, newChildWatch);
        try {
            childWatch.open();
        } catch (IOException e) {
            logger.error("Could not open (nested) file tree watch for: {} ({})", child, e);
        }
        return childWatch;
    }

    private void closeChildWatch(Path child) {
        var childWatch = childWatches.remove(child);
        if (childWatch != null) {
            try {
                childWatch.close();
            } catch (IOException e) {
                logger.error("Could not close (nested) file tree watch for: {} ({})", child, e);
            }
        }
    }

    private void forEachChild(Consumer<Path> action) {
        try (var children = Files.find(path, 1, (p, attrs) -> p != path && attrs.isDirectory())) {
            children.forEach(action);
        } catch (IOException e) {
            logger.error("File tree watch (for: {}) could not iterate over its children ({})", path, e);
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
        forEachChild(this::closeChildWatch);
        internal.close();
    }

    @Override
    protected synchronized void start() throws IOException {
        internal.open();
        forEachChild(this::openChildWatch);
    }
}
