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

public enum OverflowPolicy {

    /**
     * When an overflow event occurs, do nothing (i.e., the user-defined event
     * handler is responsible to handle overflow events).
     */
    NO_RESCANS,

    /**
     * When an overflow event occurs, rescan all files in the scope of the
     * watch, and issue `CREATED` and `MODIFIED` events (not `DELETED` events)
     * for each file. `MODIFIED` events are issued only for non-empty files.
     *
     * Compared to the `INDEXING_RESCANS` policy, the `MEMORYLESS_RESCANS`
     * policy is less expensive in terms of memory usage, but it results in a
     * larger overaproximation of the actual `CREATED` and `MODIFIED` events
     * that happened, while all `DELETED` events remain undetected.
     */
    MEMORYLESS_RESCANS,

    /**
     * When an overflow event occurs, rescan all files in the watch scope,
     * update the internal *index*, and issue `CREATED`, `MODIFIED`, and
     * `DELETED` events accordingly. The index keeps track of the last modified
     * time of each file, such that: (a) `CREATED` events are issued for files
     * that are added to the index; (b) `DELETED` events are issued for files
     * that are removed from the index; (c) `MODIFIED` events are issued for
     * files in the index whose previous last-modified-time is before their
     * current last-modified-time.
     *
     * Compared to the `MEMORYLESS_RESCANS` policy, the `INDEXING_RESCANS`
     * policy results in a smaller overapproximation of the actual `CREATED`,
     * `MODIFIED`, and `DELETED` events that happened, but it's more expensive
     * in terms of memory usage.
     */
    INDEXING_RESCANS
}
