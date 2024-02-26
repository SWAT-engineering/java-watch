package engineering.swat.watch;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecursiveWatchTests {

    private TestDirectory testDir;

    @BeforeEach
    void setup() throws IOException {
        testDir = new TestDirectory();
    }

    @AfterEach
    void cleanup() throws IOException {
        if (testDir != null) {
            testDir.close();
        }
    }

    @BeforeAll
    static void setupEverything() throws IOException {
        Awaitility.setDefaultTimeout(2, TimeUnit.SECONDS);
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
