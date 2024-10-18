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

import engineering.swat.watch.WatchEvent.Kind;

public class SingleDirectoryTests {
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
    void deleteOfFileInDirectoryShouldBeVisible() throws IOException, InterruptedException {
        var target = testDir.getTestFiles().get(0);
        var seenDelete = new AtomicBoolean(false);
        var seenCreate = new AtomicBoolean(false);
        var watchConfig = Watcher.watch(target.getParent(), WatchScope.PATH_AND_CHILDREN)
            .onEvent(ev -> {
                if (ev.getKind() == Kind.DELETED && ev.calculateFullPath().equals(target)) {
                    seenDelete.set(true);
                }
                if (ev.getKind() == Kind.CREATED && ev.calculateFullPath().equals(target)) {
                    seenCreate.set(true);
                }
            });
        try (var watch = watchConfig.start()) {

            // Delete the file
            Files.delete(target);
            await("File deletion should generate delete event")
                .untilTrue(seenDelete);

            // Re-create it again
            Files.writeString(target, "Hello World");
            await("File creation should generate create event")
                .untilTrue(seenCreate);
        }
    }
}
