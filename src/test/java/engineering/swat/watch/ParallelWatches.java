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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParallelWatches {
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
    void directoryAndFile() throws IOException {
        var dirTriggered = new AtomicBoolean();
        var fileTriggered = new AtomicBoolean();
        var file = testDir.getTestFiles().get(0);
        var file2 = testDir.getTestFiles().get(1);
        assertEquals(testDir.getTestDirectory(), file.getParent(), "Test file should be a direct child of the test dir");
        assertEquals(testDir.getTestDirectory(), file2.getParent(), "Test file should be a direct child of the test dir");
        try (var dirWatch = Watch.build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
            .on(e -> dirTriggered.set(true))
            .start()) {
            try (var fileWatch = Watch.build(file, WatchScope.PATH_ONLY).on(e -> fileTriggered.set(true)).start())  {
                Files.write(file, "test".getBytes());
                Awaitility.await()
                    .alias("Directory should have picked up the file")
                    .untilTrue(dirTriggered);
                Awaitility.await()
                    .alias("File should have picked up the file")
                    .untilTrue(fileTriggered);

                dirTriggered.set(false);
                fileTriggered.set(false);

                Awaitility.await()
                    .alias("should remain false")
                    .pollDelay(TestHelper.SHORT_WAIT.minus(Duration.ofMillis(100)))
                    .untilFalse(fileTriggered);

                assertFalse(dirTriggered.get(), "Directory should also have not seen new events");

                Files.write(file2, "test2".getBytes());
                Awaitility.await()
                    .alias("Directory should have picked up the file")
                    .untilTrue(dirTriggered);
                Awaitility.await()
                    .alias("File should not have picked up the file")
                    .during(TestHelper.SHORT_WAIT)
                    .untilFalse(fileTriggered);
            }
        }
    }

    @Test
    void nestedDirectory() throws IOException {
        var dirTriggered = new AtomicBoolean();
        var nestedDirTriggered = new AtomicBoolean();
        var dir1 = testDir.getTestDirectory();
        var dir2 = dir1.resolve("nested");
        Files.createDirectories(dir2);
        try (var dirWatch = Watch.build(dir1, WatchScope.PATH_AND_ALL_DESCENDANTS)
            .on(e -> dirTriggered.set(true))
            .start()) {
            try (var nestedDirWatch = Watch.build(dir2, WatchScope.PATH_AND_CHILDREN).on(e -> nestedDirTriggered.set(true)).start())  {
                Files.write(dir1.resolve("a1.txt"), "1".getBytes());
                Awaitility.await().alias("Directory should have picked up the file")
                    .untilTrue(dirTriggered);
                Awaitility.await().alias("Nested dir should not have picked up the file")
                    .during(TestHelper.SHORT_WAIT)
                    .untilFalse(nestedDirTriggered);

                dirTriggered.set(false);

                Awaitility.await()
                    .alias("should remain false")
                    .pollDelay(TestHelper.SHORT_WAIT.minus(Duration.ofMillis(100)))
                    .untilFalse(dirTriggered);

                assertFalse(nestedDirTriggered.get(), "Nested directory should also have not seen new events");

                Files.write(dir2.resolve("a2.txt"), "test2".getBytes());
                Awaitility.await()
                    .alias("Directory should have picked up the file")
                    .untilTrue(dirTriggered);
                Awaitility.await()
                    .alias("Nested directory should have picked up the file")
                    .untilTrue(nestedDirTriggered);
            }
        }
    }


}
