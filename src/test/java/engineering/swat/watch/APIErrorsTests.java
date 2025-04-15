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

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.io.IOException;
import java.nio.file.Files;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class APIErrorsTests {

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
    void noDuplicateEvents() {
        assertThrowsExactly(IllegalArgumentException.class, () ->
            Watch
                .build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
                .on(System.out::println)
                .on(System.err::println)
        );
    }

    @Test
    void onlyDirectoryWatchingOnDirectories() {
        assertThrowsExactly(IllegalArgumentException.class, () ->
            Watch
                .build(testDir.getTestFiles().get(0), WatchScope.PATH_AND_CHILDREN)
        );
    }

    @Test
    void doNotStartWithoutEventHandler() {
        assertThrowsExactly(IllegalStateException.class, () ->
            Watch
                .build(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
                .start()
        );
    }

    @Test
    void noRelativePaths() {
        var relativePath = testDir.getTestDirectory().resolve("d1").relativize(testDir.getTestDirectory());

        assertThrowsExactly(IllegalArgumentException.class, () ->
            Watch
                .build(relativePath, WatchScope.PATH_AND_CHILDREN)
                .start()
        );
    }

    @Test
    void nonExistingDirectory() throws IOException {
        var nonExistingDir = testDir.getTestDirectory().resolve("testd1");
        Files.createDirectory(nonExistingDir);
        var w = Watch.build(nonExistingDir, WatchScope.PATH_AND_CHILDREN);
        Files.delete(nonExistingDir);
        assertThrowsExactly(IllegalStateException.class, w::start);
    }


}
