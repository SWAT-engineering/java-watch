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
package engineering.swat.watch.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import engineering.swat.watch.WatchEvent;

class EventHandlingWatchTests {

    private static EventHandlingWatch emptyWatch(Path path) {
        return new EventHandlingWatch() {
            @Override
            public void handleEvent(WatchEvent event) {
                // Nothing to handle
            }

            @Override
            public void close() throws IOException {
                // Nothing to close
            }

            @Override
            public Path getPath() {
                return path;
            }
        };
    }

    @Test
    void relativizeTest() {
        var e1 = new WatchEvent(WatchEvent.Kind.OVERFLOW, Path.of("foo"), Path.of("bar", "baz.txt"));
        var e2 = new WatchEvent(WatchEvent.Kind.OVERFLOW, Path.of("foo", "bar", "baz.txt"));
        var e3 = emptyWatch(Path.of("foo")).relativize(e2);
        assertEquals(e1.getRootPath(), e3.getRootPath());
        assertEquals(e1.getRelativePath(), e3.getRelativePath());
    }
}
