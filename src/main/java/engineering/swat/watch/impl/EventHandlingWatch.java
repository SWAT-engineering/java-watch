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

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

public interface EventHandlingWatch extends ActiveWatch {

    /**
     * Handles `event`. The purpose of this method is to trigger the event
     * handler of this watch "from the outside" (in addition to having native
     * file system libraries trigger the event handler "from the inside"). This
     * is useful to report synthetic events (e.g., while handling overflows).
     */
    void handleEvent(WatchEvent event);

    /**
     * Relativizes the full path of `event` against the path watched by this
     * watch (as per `getPath()`). Returns a new event whose root path and
     * relative path are set in accordance with the relativization.
     */
    default WatchEvent relativize(WatchEvent event) {
        var fullPath = event.calculateFullPath();

        var kind = event.getKind();
        var rootPath = getPath();
        var relativePath = rootPath.relativize(fullPath);
        return new WatchEvent(kind, rootPath, relativePath);
    }
}
