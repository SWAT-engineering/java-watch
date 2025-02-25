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

import java.nio.file.Path;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The library publishes these events to all subscribers, they are immutable and safe to share around.
 */
public class WatchEvent {

    /**
     * What happened with the file or directory
     */
    public enum Kind {
        /**
         *  A path entry was created. Be careful not to assume that when the event arrives, the path still exists.
         **/
        CREATED,
        /**
         * The path entry was saved. It is platform specific if this relates to flushes or other events.
         *  a single user action can generate multiple of these events.
         */
        MODIFIED,
        /**
         * The path entry was deleted.
         * Note that if the path entry was the watched item (aka the root of the watch),
         * there is no guarantee if you will receive this event (depending on the level and on the platform).
         * The watch will be invalid after that, even if a new item is created afterwards with the same name.
         * In some cases this can be fixed/detected by also watching the parent, but that is only valid if they are on the same mountpoint.
         */
        DELETED,
        /**
         * Rare event where there were so many file events, that the kernel lost a few.
         * In that case you'll have to consider the whole directory (and its sub directories) as modified.
         * The library will try and send events for new and deleted files, but it won't be able to detect modified files.
         */
        OVERFLOW
    }

    private final Kind kind;
    private final Path rootPath;
    private final Path relativePath;

    public WatchEvent(Kind kind, Path rootPath, @Nullable Path relativePath) {
        this.kind = kind;
        this.rootPath = rootPath;
        this.relativePath = relativePath == null ? Path.of("") : relativePath;
    }

    public Kind getKind() {
        return this.kind;
    }

    /**
     *
     * @return the path relative to the monitored root, it can be empty path if it's the root.
     */
    public Path getRelativePath() {
        return relativePath;
    }

    /**
     *
     * @return A copy of the root path that this event belongs to.
     */
    public Path getRootPath() {
        return rootPath;
    }

    /**
     * @return utility function that resolves the relative path to the full path.
     */
    public Path calculateFullPath() {
        return rootPath.resolve(relativePath);
    }

    @Override
    public String toString() {
        return String.format("WatchEvent[%s, %s, %s]", this.rootPath, this.kind, this.relativePath);
    }

}
