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
package engineering.swat.watch.impl.overflows;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.OnOverflow;
import engineering.swat.watch.TestDirectory;
import engineering.swat.watch.TestHelper;
import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.Watcher;
import engineering.swat.watch.impl.EventHandlingWatch;

class IndexingRescannerTests {

    private TestDirectory testDir;

    @BeforeEach
    void setup() throws IOException {
        testDir = new TestDirectory();
    }

    @AfterEach
    void cleanup() {
        if (testDir != null) {
            testDir.close();
        }
    }

    @BeforeAll
    static void setupEverything() {
        Awaitility.setDefaultTimeout(TestHelper.NORMAL_WAIT);
    }

    @Test
    void onlyEventsForFilesInScopeAreIssued() throws IOException, InterruptedException {
        var path = testDir.getTestDirectory();

        // Configure a non-recursive directory watch that monitors only the
        // children (not all descendants) of `path`
        var eventsOnlyForChildren = new AtomicBoolean(true);
        var watchConfig = Watcher.watch(path, WatchScope.PATH_AND_CHILDREN)
            .approximate(OnOverflow.NONE) // Disable the auto-handler here; we'll have an explicit one below
            .on(e -> {
                if (e.getRelativePath().getNameCount() > 1) {
                    eventsOnlyForChildren.set(false);
                }
            });

        try (var watch = (EventHandlingWatch) watchConfig.start()) {
            // Create a rescanner that initially indexes all descendants (not
            // only the children) of `path`. The resulting initial index is an
            // overestimation of the files monitored by the watch.
            var rescanner = new IndexingRescanner(
                ForkJoinPool.commonPool(), path,
                WatchScope.PATH_AND_ALL_DESCENDANTS);

            // Trigger a rescan. Because only the children (not all descendants)
            // of `path` are watched, the rescan should issue events only for
            // those children (even though the initial index contains entries
            // for all descendants).
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, path);
            rescanner.accept(watch, overflow);
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());

            await("No events for non-children descendants should have been issued")
                .until(eventsOnlyForChildren::get);
        }
    }
}
