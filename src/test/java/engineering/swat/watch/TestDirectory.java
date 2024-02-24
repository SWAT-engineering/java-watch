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

class TestDirectory implements Closeable {
    private final Path testDirectory;
    private final List<Path> testFiles;


    TestDirectory() throws IOException {
        testDirectory = Files.createTempDirectory("smoke-test");
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

    @Override
    public void close() throws IOException {
        Files.walk(testDirectory)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
    }

    public Path getTestDirectory() {
        return testDirectory;
    }
    public List<Path> getTestFiles() {
        return testFiles;
    }
}
