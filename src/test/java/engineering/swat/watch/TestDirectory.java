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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TestDirectory implements Closeable {
    private final Path testDirectory;
    private final List<Path> testFiles;

    public TestDirectory() throws IOException {
        testDirectory = Files.createTempDirectory("java-watch-test");
        List<Path> testFiles = new ArrayList<>();
        add3Files(testFiles, testDirectory);
        for (var d: Arrays.asList("d1", "d2", "d3")) {
            Files.createDirectories(testDirectory.resolve(d));
            add3Files(testFiles, testDirectory.resolve(d));
        }
        this.testFiles = Collections.unmodifiableList(testFiles);
    }

    private static void add3Files(List<Path> testFiles, Path root) throws IOException {
        for (var f : Arrays.asList("a.txt", "b.txt", "c.txt")) {
            testFiles.add(Files.createFile(root.resolve(f)));
        }
    }

    public void deleteAllFiles() throws IOException {
        try (var files = Files.walk(testDirectory)) {
            files.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @Override
    public void close() {
        try {
            deleteAllFiles();
        } catch (IOException _ignored) { }
    }

    public Path getTestDirectory() {
        return testDirectory;
    }

    public List<Path> getTestFiles() {
        return testFiles;
    }
}
