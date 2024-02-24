package engineering.swat.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;

class RecursiveWatchTests {

    static @MonotonicNonNull TestDirectory testDir;

    @BeforeAll
    static void setupEverything() throws IOException {
        testDir = new TestDirectory();
        Awaitility.setDefaultTimeout(2, TimeUnit.SECONDS);
    }

    @AfterAll
    static void cleanupDirectory()  throws IOException {
        if (testDir != null) {
            testDir.close();
        }
    }

    @Test
    void newDirectoryWithFilesChangesDetected() throws IOException {
        var target = new AtomicReference<Path>();
        var created = new AtomicBoolean(false);
        var changed = new AtomicBoolean(false);
        var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .onCreate(p -> {if (p.equals(target.get())) { created.set(true); }})
            .onModified(p -> {if (p.equals(target.get())) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            var freshFile = Files.createTempDirectory(testDir.getTestDirectory(), "new-dir").resolve("test-file.txt");
            target.set(freshFile);
            Files.writeString(freshFile, "Hello world");
            await().alias("New files should have been seen").until(created::get);
            Files.writeString(freshFile, "Hello world 2");
            await().alias("Fresh file change have been detected").until(changed::get);
        }
    }

}
