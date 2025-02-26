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
package engineering.swat.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.impl.EventHandlingWatch;
import engineering.swat.watch.impl.jdk.JDKDirectoryWatch;
import engineering.swat.watch.impl.jdk.JDKFileWatch;
import engineering.swat.watch.impl.jdk.JDKRecursiveDirectoryWatch;
import engineering.swat.watch.impl.overflows.MemorylessRescanner;

/**
 * <p>Watch a path for changes.</p>
 *
 *
 * <p>It will avoid common errors using the raw apis, and will try to use the most native api where possible.</p>
 * Note, there are differences per platform that cannot be avoided, please review the readme of the library.
 */
public class Watcher {
    private final Logger logger = LogManager.getLogger();
    private final Path path;
    private final WatchScope scope;
    private final OverflowPolicy overflowPolicy;
    private volatile Executor executor = CompletableFuture::runAsync;

    private static final BiConsumer<EventHandlingWatch, WatchEvent> EMPTY_HANDLER = (w, e) -> {};
    private volatile BiConsumer<EventHandlingWatch, WatchEvent> eventHandler = EMPTY_HANDLER;

    private Watcher(Path path, WatchScope scope, OverflowPolicy overflowPolicy) {
        this.path = path;
        this.scope = scope;
        this.overflowPolicy = overflowPolicy;
    }

    /**
     * Equivalent to: `watch(path, scope, OverflowPolicy.MEMORYLESS_RESCANS)`
     */
    public static Watcher watch(Path path, WatchScope scope) {
        return watch(path, scope, OverflowPolicy.MEMORYLESS_RESCANS);
    }

    /**
     * Watch a path for updates, optionally also get events for its children/descendants
     * @param path which absolute path to monitor, can be a file or a directory, but has to be absolute
     * @param scope for directories you can also choose to monitor it's direct children or all it's descendants
     * @param overflowPolicy policy to automatically handle overflow events
     * @throws IllegalArgumentException in case a path is not supported (in relation to the scope)
     */
    public static Watcher watch(Path path, WatchScope scope, OverflowPolicy overflowPolicy) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("We can only watch absolute paths");
        }
        switch (scope) {
            case PATH_AND_CHILDREN: // intended fallthrough
            case PATH_AND_ALL_DESCENDANTS:
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("Only directories are supported for this scope: " + scope);
                }
                break;
            case PATH_ONLY:
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException("Symlinks are not supported");
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported scope: " + scope);
        }
        return new Watcher(path, scope, overflowPolicy);
    }

    /**
     * Callback that gets executed for every event. Can get called quite a bit, so be careful what happens here.
     * Use the {@link #withExecutor(Executor)} function to influence the sequencing of these events.
     * By default they can arrive in parallel.
     * @param eventHandler a callback that handles the watch event, will be called once per event.
     * @return this for optional method chaining
     */
    public Watcher on(Consumer<WatchEvent> eventHandler) {
        if (this.eventHandler != EMPTY_HANDLER) {
            throw new IllegalArgumentException("on handler cannot be set more than once");
        }
        this.eventHandler = (w, e) -> eventHandler.accept(e);
        return this;
    }

    /**
     * Convenience variant of {@link #on(Consumer)}, which allows you to only respond to certain events
     */
    public Watcher on(WatchEventListener listener) {
        if (this.eventHandler != EMPTY_HANDLER) {
            throw new IllegalArgumentException("on handler cannot be set more than once");
        }
        this.eventHandler = (w, ev) -> {
            switch (ev.getKind()) {
                case CREATED:
                    listener.onCreated(ev);
                    break;
                case DELETED:
                    listener.onDeleted(ev);
                    break;
                case MODIFIED:
                    listener.onModified(ev);
                    break;
                case OVERFLOW:
                    listener.onOverflow(ev);
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected kind: " + ev.getKind());
            }
        };
        return this;
    }

    /**
     * Optionally configure the executor in which the {@link #on(Consumer)} callbacks are scheduled.
     * If not defined, every task will be scheduled on the {@link java.util.concurrent.ForkJoinPool#commonPool()}.
     * @param callbackHandler worker pool to use
     * @return this for optional method chaining
     */
    public Watcher withExecutor(Executor callbackHandler) {
        this.executor = callbackHandler;
        return this;
    }

    /**
     * Start watch the path for events.
     * @return a subscription for the watch, when closed, new events will stop being registered to the worker pool.
     * @throws IOException in case the starting of the watcher caused an underlying IO exception
     * @throws IllegalStateException the watchers is not configured correctly (for example, missing {@link #on(Consumer)}, or a watcher is started twice)
     */
    public ActiveWatch start() throws IOException {
        if (this.eventHandler == EMPTY_HANDLER) {
            throw new IllegalStateException("There is no onEvent handler defined");
        }

        var h = overflowEventHandler().andThen(userDefinedEventHandler());

        switch (scope) {
            case PATH_AND_CHILDREN: {
                var result = new JDKDirectoryWatch(path, executor, h);
                result.open();
                return result;
            }
            case PATH_AND_ALL_DESCENDANTS: {
                try {
                    var result = new JDKDirectoryWatch(path, executor, eventHandler, true);
                    result.open();
                    return result;
                } catch (Throwable ex) {
                    // no native support, use the simulation
                    logger.debug("Not possible to register the native watcher, using fallback for {}", path);
                    logger.trace(ex);
                    var result = new JDKRecursiveDirectoryWatch(path, executor, eventHandler);
                    result.open();
                    return result;
                }
            }
            case PATH_ONLY: {
                var result = new JDKFileWatch(path, executor, eventHandler);
                result.open();
                return result;
            }
            default:
                throw new IllegalStateException("Not supported yet");
        }
    }

    private BiConsumer<EventHandlingWatch, WatchEvent> overflowEventHandler() {
        switch (overflowPolicy) {
            case NO_RESCANS:
                return (w, e) -> {};
            case MEMORYLESS_RESCANS:
                return new MemorylessRescanner(executor);
            default:
                throw new UnsupportedOperationException("No event handler has been defined yet for this overflow policy");
        }
    }

    private BiConsumer<EventHandlingWatch, WatchEvent> userDefinedEventHandler() {
        // If overflow events are auto-handled because of the overflow policy,
        // then they should be ignored by the user-defined event handler
        if (overflowPolicy != OverflowPolicy.NO_RESCANS) {
            return (w, e) -> {
                if (e.getKind() != WatchEvent.Kind.OVERFLOW) {
                    eventHandler.accept(w, e);
                }
            };
        }

        return eventHandler;
    }
}
