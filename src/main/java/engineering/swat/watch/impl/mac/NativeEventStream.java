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
package engineering.swat.watch.impl.mac;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

// Note: This file is designed to be the only place in this package where native
// APIs are invoked. If the need to do so arises outside this file, consider
// extending this file to offer the required services without the native APIs.

/**
 * <p>
 * Stream of native events for a path, issued by macOS. It's a facade-like
 * object that hides the low-level native APIs behind a higher-level interface.
 * </p>
 *
 * <p>
 * Note: Methods {@link #open()} and {@link #close()} synchronize on this object
 * to avoid races. The synchronization overhead is expected to be negligible, as
 * these methods are expected to be rarely invoked.
 * </p>
 */
class NativeEventStream implements Closeable {
    static {
        NativeLibrary.load();
    }

    private final Path path;
    private final NativeEventHandler handler;
    private volatile boolean closed;
    private volatile long nativeWatch;

    public NativeEventStream(Path path, NativeEventHandler handler) throws IOException {
        this.path = path.toRealPath(); // Resolve symbolic links
        this.handler = handler;
        this.closed = true;
    }

    public synchronized void open() {
        if (!closed) {
            return;
        } else {
            closed = false;
        }

        nativeWatch = NativeLibrary.start(path.toString(), handler);
    }

    // -- Closeable --

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        } else {
            closed = true;
        }

        NativeLibrary.stop(nativeWatch);
    }
}
