package engineering.swat.watch;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
        Awaitility.setDefaultTimeout(2, TimeUnit.SECONDS);
    }

    @Test
    void singleFileShouldNotTriggerOnOtherFilesInSameDir() throws IOException, InterruptedException {
        var target = testDir.getTestFiles().get(0);
        var seen = new AtomicBoolean(false);
        var others = new AtomicBoolean(false);
        var watchConfig = Watcher.singleFile(target)
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
            Thread.sleep(1000);
            Files.writeString(target, "Hello world");
            await("Single file does trigger")
                .failFast("No others should be notified", others::get)
                .untilTrue(seen);
        }
    }
}
