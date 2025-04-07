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
 * Constants to indicate for which regular files/directories in the scope of the
 * watch an <i>approximation</i> of synthetic events (of kinds
 * {@link WatchEvent.Kind#CREATED}, {@link WatchEvent.Kind#MODIFIED}, and/or
 * {@link WatchEvent.Kind#DELETED}) should be issued when an overflow event
 * happens. These synthetic events, as well as the overflow event itself, are
 * subsequently passed to the user-defined event handler of the watch.
 * Typically, the user-defined event handler can ignore the original overflow
 * event (i.e., handling the synthetic events is sufficient to address the
 * overflow issue), but it doesn't have to (e.g., it may carry out additional
 * overflow bookkeeping).
 */
public enum Approximation {

    /**
     * Synthetic events are issued for <b>no regular files/directories</b> in
     * the scope of the watch. Thus, the user-defined event handler is fully
     * responsible to handle overflow events.
     */
    NONE,

    /**
     * <p>
     * Synthetic events of kinds {@link WatchEvent.Kind#CREATED} and
     * {@link WatchEvent.Kind#MODIFIED}, but not
     * {@link WatchEvent.Kind#DELETED}, are issued for all regular
     * files/directories in the scope of the watch. Specifically, when an
     * overflow event happens:
     *
     * <ul>
     * <li>CREATED events are issued for all regular files/directories
     * (overapproximation).
     * <li>MODIFIED events are issued for all non-empty, regular files
     * (overapproximation) but for no directories (underapproximation).
     * <li>DELETED events are issued for no regular files/directories
     * (underapproximation).
     * </ul>
     *
     * <p>
     * This approach is relatively cheap in terms of memory usage (cf.
     * {@link #DIFF}), but it results in a large over/underapproximation of the
     * actual events (cf. DIFF).
     */
    ALL,


    /**
     * <p>
     * Synthetic events of kinds {@link WatchEvent.Kind#CREATED},
     * {@link WatchEvent.Kind#MODIFIED}, and {@link WatchEvent.Kind#DELETED} are
     * issued for regular files/directories in the scope of the watch, when
     * their current versions are different from their previous versions, as
     * determined using <i>last-modified-times</i>. Specifically, when an
     * overflow event happens:
     *
     * <ul>
     * <li>CREATED events are issued for all regular files/directories when the
     * previous last-modified-time is unknown, but the current
     * last-modified-time is known (i.e., the file started existing).
     * <li>MODIFIED events are issued for all regular files/directories when the
     * previous last-modified-time is before the current last-modified-time.
     * <li>DELETED events are issued for all regular files/directories when the
     * previous last-modified-time is known, but the current
     * last-modified-time is unknown (i.e., the file stopped existing).
     * </ul>
     *
     * <p>
     * To keep track of last-modified-times, an internal <i>index</i> is
     * populated with last-modified-times of all regular files/directories in
     * the scope of the watch when the watch is started. Each time when any
     * event happens, the index is updated accordingly, so when an overflow
     * event happens, last-modified-times can be compared as described above.
     *
     * <p>
     * This approach results in a small overapproximation (cf. {@link #ALL}),
     * but it is relatively expensive in terms of memory usage (cf. ALL), as the
     * watch needs to keep track of last-modified-times.
     */
    DIFF
}
