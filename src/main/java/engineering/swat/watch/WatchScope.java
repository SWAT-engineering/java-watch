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

/**
 * Configure the depth of the events you want to receive for a given path
 */
public enum WatchScope {
    /**
     * <p>Watch changes to a single file or (metadata of) a single directory. </p>
     *
     * <p>Note, depending on the platform you can receive events for a directory
     * in case of these events: </p>
     * <ul>
     *   <li>a MODIFIED caused by the creation of a nested file/directory </li>
     *   <li>a MODIFIED caused by the deletion of a nested file/directory </li>
     *   <li>a MODIFIED of its own metadata</li>
     * </ul>
     *
     * <p>In most cases when Path is a Directory you're interested in which nested entries changes, in that case use {@link #PATH_AND_CHILDREN} or {@link #PATH_AND_ALL_DESCENDANTS}. </p>
     */
    PATH_ONLY,
    /**
     * Watch changes to (metadata of) a directory and its content,
     * non-recursively. That is, changes to the content of nested directories
     * are not watched.
     */
    PATH_AND_CHILDREN,
    /**
     * Watch changes to (metadata of) a directory and its content, recursively.
     * That is, changes to the content of nested directories are also watched.
     */
    PATH_AND_ALL_DESCENDANTS
}
