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

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.WatchKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Warning: This class is experimental, untested, and unused. We don't need
 * blocking operations currently, but it would be nice (?) to have them
 * eventually.
 */
public class MacBlockingWatchService extends MacWatchService {
    private final Set<Thread> blockedThreads = ConcurrentHashMap.newKeySet();

    private void throwIfClosed() {
        if (isClosed()) {
            throw new ClosedWatchServiceException();
        }
    }

    private <K> K throwIfClosedDuring(BlockingSupplier<K> supplier) throws InterruptedException {
        var t = Thread.currentThread();
        blockedThreads.add(t);
        try {
            throwIfClosed();
            return supplier.get();
        } catch (InterruptedException e) {
            throwIfClosed();
            // If this service isn't closed yet, then definitely the interrupt
            // can't have originated from this service. Thus, re-throw it.
            throw e;
        } finally {
            blockedThreads.remove(t);
        }
    }

    @FunctionalInterface
    private static interface BlockingSupplier<T> {
        T get() throws InterruptedException;
    }

    // -- MacWatchService --

    @Override
    public void close() throws IOException {
        super.close();
        for (var t : blockedThreads) {
            t.interrupt();
        }
    }

    @Override
    public @Nullable WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        return this.<@Nullable WatchKey> throwIfClosedDuring(
            () -> pendingKeys.poll(timeout, unit));
    }

    @Override
    public WatchKey take() throws InterruptedException {
        return throwIfClosedDuring(pendingKeys::take);
    }
}
