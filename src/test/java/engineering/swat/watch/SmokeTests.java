package engineering.swat.watch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import static org.awaitility.Awaitility.*;
import static java.time.Duration.*;


public class SmokeTests {
    static Path testDirectory;
    static List<Path> testFiles = new ArrayList<>();

    @BeforeAll
    static void setupTestDirectory() throws IOException {
        testDirectory = Files.createTempDirectory("smoke-test");
        add3Files(testDirectory);
        for (var d: Arrays.asList("d1", "d2", "d3")) {
            Files.createDirectories(testDirectory.resolve(d));
            add3Files(testDirectory.resolve(d));
        }
        Awaitility.setDefaultTimeout(1, TimeUnit.SECONDS);
    }

    private static void add3Files(Path root) throws IOException {
        for (var f : Arrays.asList("a.txt", "b.txt", "c.txt")) {
            testFiles.add(Files.createFile(root.resolve(f)));
        }
    }

    @AfterAll
    static void cleanupDirectory()  throws IOException {
        if (testDirectory != null) {
            Files.walk(testDirectory)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @Test
    void watchDirectory() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testFiles.get(0);
        var watchConfig = Watcher.singleDirectory(testDirectory)
            .onModified(p -> {if (p.equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await().alias("Target file change").until(changed::get);
        }
    }

    @Test
    void watchRecursiveDirectory() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testFiles.stream()
            .filter(p -> !p.getParent().equals(testDirectory))
            .findFirst()
            .orElseThrow();
        var watchConfig = Watcher.recursiveDirectory(testDirectory)
            .onModified(p -> {if (p.equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await().alias("Nested file change").until(changed::get);
        }
    }


}
