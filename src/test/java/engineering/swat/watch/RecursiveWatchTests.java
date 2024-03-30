package engineering.swat.watch;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecursiveWatchTests {
    private final Logger logger = LogManager.getLogger();

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
        Awaitility.setDefaultTimeout(4, TimeUnit.SECONDS);
    }


    @Test
    void newDirectoryWithFilesChangesDetected() throws IOException {
        var target = new AtomicReference<Path>();
        var created = new AtomicBoolean(false);
        var changed = new AtomicBoolean(false);
        var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .onEvent(ev -> {
                    logger.debug("Event received: {}", ev);
                    if (ev.calculateFullPath().equals(target.get())) {
                        switch (ev.getKind()) {
                            case CREATED:
                                created.set(true);
                                break;
                            case MODIFIED:
                                changed.set(true);
                                break;
                            default:
                                break;
                        }
                    }
            });

        try (var activeWatch = watchConfig.start() ) {
            var freshFile = Files.createTempDirectory(testDir.getTestDirectory(), "new-dir").resolve("test-file.txt");
            target.set(freshFile);
            logger.debug("Interested in: {}", freshFile);
            Files.writeString(freshFile, "Hello world");
            await().alias("New files should have been seen").until(created::get);
            Files.writeString(freshFile, "Hello world 2");
            await().alias("Fresh file change have been detected").until(changed::get);
        }
    }

    @Test
    void correctRelativePathIsReported() throws IOException {
        Path relative = Path.of("a","b", "c", "d.txt");
        var seen = new AtomicBoolean(false);
        var watcher = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .onEvent(ev -> {
                logger.debug("Seen event: {}", ev);
                if (ev.getRelativePath().equals(relative)) {
                    seen.set(true);
                }
            });

        try (var w = watcher.start()) {
            var targetFile = testDir.getTestDirectory().resolve(relative);
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, "Hello World");
            await().alias("Nested path is seen").until(seen::get);
        }

    }

}
