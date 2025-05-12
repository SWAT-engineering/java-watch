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

import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

class MacWatchable implements Watchable {
    private final Path path;
    private final Map<MacWatchService, MacWatchKey> registrations;

    MacWatchable(Path path) {
        this.path = path;
        this.registrations = new ConcurrentHashMap<>();
    }

    Path getPath() {
        return path;
    }

    void unregister(MacWatchService watcher) {
        registrations.remove(watcher);
    }

    // -- Watchable --

    @Override
    public WatchKey register(WatchService watcher, Kind<?>[] events, Modifier... modifiers) throws IOException {
        if (!(watcher instanceof MacWatchService)) {
            throw new IllegalArgumentException("A `MacWatchable` must be registered with a `MacWatchService`");
        }

        // Add `OVERFLOW` to the array (demanded by this method's specification)
        if (Stream.of(events).noneMatch(OVERFLOW::equals)) {
            events = Stream
                .concat(Stream.of(events), Stream.of(OVERFLOW))
                .toArray(Kind<?>[]::new);
        }

        // Wrap any `IOException` thrown by the constructor of `MacWatchKey` in
        // an `UncheckedIOException`. Intended to be used when invoking
        // `computeIfAbsent`.
        Function<MacWatchService, MacWatchKey> newMacWatchKey = service -> {
            try {
                return new MacWatchKey(this, service);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        try {
            return registrations
                .computeIfAbsent((MacWatchService) watcher, newMacWatchKey)
                .initialize(events, modifiers);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public WatchKey register(WatchService watcher, Kind<?>... events) throws IOException {
        return register(watcher, events, new WatchEvent.Modifier[0]);
    }
}
