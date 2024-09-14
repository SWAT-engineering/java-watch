package engineering.swat.watch;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SingleFileTests {
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
    static void setupEverything() {
        Awaitility.setDefaultTimeout(TestHelper.NORMAL_WAIT);
    }

    @Test
    void singleFileShouldNotTriggerOnOtherFilesInSameDir() throws IOException, InterruptedException {
        var target = testDir.getTestFiles().get(0);
        var seen = new AtomicBoolean(false);
        var others = new AtomicBoolean(false);
        var watchConfig = Watcher.single(target)
            .onEvent(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    seen.set(true);
                }
                else {
                    others.set(true);
                }
            });
        try (var watch = watchConfig.start()) {
            for (var f : testDir.getTestFiles()) {
                if (!f.equals(target)) {
                    Files.writeString(f, "Hello");
                }
            }
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());
            Files.writeString(target, "Hello world");
            await("Single file does trigger")
                .during(TestHelper.NORMAL_WAIT)
                .failFast("No others should be notified", others::get)
                .untilTrue(seen);
        }
    }

    @Test
    void singleFileThatMonitorsOnlyADirectory() throws IOException, InterruptedException {
        var target = testDir.getTestDirectory();
        var seen = new AtomicBoolean(false);
        var others = new AtomicBoolean(false);
        var watchConfig = Watcher.single(target)
            .onEvent(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    seen.set(true);
                }
                else {
                    others.set(true);
                }
            });
        try (var watch = watchConfig.start()) {
            for (var f : testDir.getTestFiles()) {
                if (!f.equals(target)) {
                    Files.writeString(f, "Hello");
                }
            }
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());
            Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
            await("Single directory does trigger")
                .during(TestHelper.NORMAL_WAIT)
                .failFast("No others should be notified", others::get)
                .untilTrue(seen);
        }
    }
}
