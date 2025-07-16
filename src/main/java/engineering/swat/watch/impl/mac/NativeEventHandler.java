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

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * <p>
 * Handler for native events, intended to be used in a {@link NativeEventStream}
 * callback to construct {@link WatchEvent}s (and propagate them for downstream
 * consumption).
 * </p>
 *
 * <p>
 * In each invocation, the types of {@code kind} and {@code context} depend
 * specifically on the given native event: they're {@code Kind<Path>} and
 * {@code Path} for non-overflows, but they're {@code Kind<Object>} and
 * {@code Object} for overflows. This precision is needed to construct
 * {@link WatchEvent}s, where the types of {@code kind} and {@code context} need
 * to be correlated. Note: {@link java.util.function.BiConsumer} doesn't give
 * the required precision (i.e., its type parameters are initialized only once
 * for all invocations).
 * </p>
 */
@FunctionalInterface
interface NativeEventHandler {
    <T> void handle(java.nio.file.WatchEvent.Kind<T> kind, @Nullable T context);

    default void handle(int kindOrdinal, String rootPath, String relativePath) {
        if (kindOrdinal == Kind.OVERFLOW.ordinal()) {
            handle(OVERFLOW, null);
        } else {
            var context = Path.of(rootPath).relativize(Path.of(relativePath));
            var kind =
                kindOrdinal == Kind.CREATE.ordinal() ? ENTRY_CREATE :
                kindOrdinal == Kind.MODIFY.ordinal() ? ENTRY_MODIFY :
                kindOrdinal == Kind.DELETE.ordinal() ? ENTRY_DELETE : null;

            if (kind != null) {
                handle(kind, context);
            }
        }
    }
}

enum Kind {
    OVERFLOW,
    CREATE,
    DELETE,
    MODIFY;
}
