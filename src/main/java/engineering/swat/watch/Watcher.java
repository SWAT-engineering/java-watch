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
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.impl.jdk.JDKBaseWatch;
import engineering.swat.watch.impl.jdk.JDKDirectoryWatch;
import engineering.swat.watch.impl.jdk.JDKFileWatch;
import engineering.swat.watch.impl.jdk.JDKRecursiveDirectoryWatch;

/**
 * <p>Watch a path for changes.</p>
 *
 *
 * <p>It will avoid common errors using the raw apis, and will try to use the most native api where possible.</p>
 * Note, there are differences per platform that cannot be avoided, please review the readme of the library.
 */
public class Watcher {
    private final Logger logger = LogManager.getLogger();
    private final WatchScope scope;
    private final Path path;
    private volatile Executor executor = CompletableFuture::runAsync;

    private static final Consumer<WatchEvent> EMPTY_HANDLER = p -> {};
    private volatile Consumer<WatchEvent> eventHandler = EMPTY_HANDLER;


    private Watcher(WatchScope scope, Path path) {
        this.scope = scope;
        this.path = path;
    }

    /**
     * Watch a path for updates, optionally also get events for its children/descendants
     * @param path which absolute path to monitor, can be a file or a directory, but has to be absolute
     * @param scope for directories you can also choose to monitor it's direct children or all it's descendants
     * @throws IllegalArgumentException in case a path is not supported (in relation to the scope)
     */
    public static Watcher watch(Path path, WatchScope scope) {
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
        return new Watcher(scope, path);
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
        this.eventHandler = eventHandler;
        return this;
    }

    /**
     * Convenience variant of {@link #on(Consumer)}, which allows you to only respond to certain events
     */
    public Watcher on(WatchEventListener listener) {
        if (this.eventHandler != EMPTY_HANDLER) {
            throw new IllegalArgumentException("on handler cannot be set more than once");
        }
        this.eventHandler = ev -> {
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

        JDKBaseWatch result;

        switch (scope) {
            case PATH_AND_CHILDREN: {
                result = new JDKDirectoryWatch(path, executor, eventHandler, false);
                result.start();
                break;
            }
            case PATH_AND_ALL_DESCENDANTS: {
                try {
                    result = new JDKDirectoryWatch(path, executor, eventHandler, true);
                    result.start();
                } catch (Throwable ex) {
                    // no native support, use the simulation
                    logger.debug("Not possible to register the native watcher, using fallback for {}", path);
                    logger.trace(ex);
                    result = new JDKRecursiveDirectoryWatch(path, executor, eventHandler);
                    result.start();
                }
                break;
            }
            case PATH_ONLY: {
                result = new JDKFileWatch(path, executor, eventHandler);
                result.start();
                break;
            }
            default:
                throw new IllegalStateException("Not supported yet");
        }

        return result;
    }
}
