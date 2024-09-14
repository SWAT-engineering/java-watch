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

import engineering.swat.watch.WatchEvent.Kind;

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
            await("New files should have been seen").untilTrue(created);
            Files.writeString(freshFile, "Hello world 2");
            await("Fresh file change have been detected").untilTrue(changed);
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
            await("Nested path is seen").untilTrue(seen);
        }

    }

    @Test
    void deleteOfFileInDirectoryShouldBeVisible() throws IOException, InterruptedException {
        var target = testDir.getTestFiles()
            .stream()
            .filter(p -> !p.getParent().equals(testDir.getTestDirectory()))
            .findAny()
            .orElseThrow();
        var seen = new AtomicBoolean(false);
        var watchConfig = Watcher.singleDirectory(target.getParent())
            .onEvent(ev -> {
                if (ev.getKind() == Kind.DELETED && ev.calculateFullPath().equals(target)) {
                    seen.set(true);
                }
            });
        try (var watch = watchConfig.start()) {
            Files.delete(target);
            await("File deletion should generate delete event")
                .untilTrue(seen);
        }
    }

}
