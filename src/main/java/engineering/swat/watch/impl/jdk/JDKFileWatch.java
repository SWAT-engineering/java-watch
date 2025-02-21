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
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import engineering.swat.watch.WatchEvent;

/**
 * It's not possible to monitor a single file (or directory), so we have to find a directory watcher, and connect to that
 *
 * Note that you should take care to call start only once.
 */
public class JDKFileWatch extends JDKBaseWatch {
    private final Logger logger = LogManager.getLogger();
    private final Path parent;
    private final Path fileName;
    private final JDKBaseWatch internal;

    public JDKFileWatch(Path file, Executor exec, Consumer<WatchEvent> eventHandler) {
        super(file, exec, eventHandler);

        var message = "The root path is not a valid path for a file watch";
        var parent = requireNonNull(file.getParent(), message);
        var fileName = requireNonNull(file.getFileName(), message);
        assert !parent.equals(file);

        this.internal = new JDKDirectoryWatch(parent, exec, e -> {
            if (fileName.equals(e.getRelativePath())) {
                eventHandler.accept(e);
            }
        });

        logger.debug("File watch (for: {}) is in reality a directory watch (for: {}) with a filter (for: {})", file, parent, fileName);
    }

    private static Path requireNonNull(@Nullable Path p, String message) {
        if (p == null) {
            throw new IllegalArgumentException(message);
        }
        return p;
    }

    // -- JDKBaseWatch --

    @Override
    public synchronized void close() throws IOException {
        internal.close();
    }

    @Override
    protected synchronized void start() throws IOException {
        internal.open();
    }
}
