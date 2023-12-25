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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SmokeTests {
    static Path testDirectory;
    static List<Path> testFiles = new ArrayList<>();

    @BeforeAll
    static void setupTestDirectory() throws IOException {
        testDirectory = Files.createTempDirectory("smoke-test");
        for (var f : Arrays.asList("a.txt", "b.txt", "c.txt")) {
            testFiles.add(Files.createFile(testDirectory.resolve(f)));
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
    void watchSingleFile() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testFiles.get(0);
        var watchConfig = Watcher.singleDirectory(target.getParent())
            .onModified(p -> {if (p.equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            Thread.sleep(1000);
            assertTrue(changed.get(), "The file change should be detected");
        }
    }

}
