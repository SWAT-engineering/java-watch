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


import static engineering.swat.watch.WatchEvent.Kind.*;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class SmokeTests {
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
    void watchDirectory() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().get(0);
        var watchConfig = Watcher.build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
            .on(ev -> {if (ev.getKind() == MODIFIED && ev.calculateFullPath().equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await("Target file change").untilTrue(changed);
        }
    }

    @Test
    void watchRecursiveDirectory() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> !p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();
        var watchConfig = Watcher.build(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(ev -> { if (ev.getKind() == MODIFIED && ev.calculateFullPath().equals(target)) { changed.set(true);}})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await("Nested file change").untilTrue(changed);
        }
    }

    @Test
    void watchSingleFile() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();

        var watchConfig = Watcher.build(target, WatchScope.PATH_ONLY)
            .on(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    changed.set(true);
                }
            });

        try (var watch = watchConfig.start()) {
            Files.writeString(target, "Hello world");
            await("Single file change").untilTrue(changed);
        }
    }
}
