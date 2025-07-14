package engineering.swat.watch.impl.mac.jni;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import engineering.swat.watch.TestDirectory;
import engineering.swat.watch.TestHelper;

@EnabledOnOs({OS.MAC})
public class Basic {

    private TestDirectory testDir;

    @BeforeEach
    void setup() throws IOException {
        testDir = new TestDirectory();
    }

    @AfterEach
    void cleanup() {
        if (testDir != null) {
            testDir.close();
        }
    }

    @BeforeAll
    static void setupEverything() {
        Awaitility.setDefaultTimeout(TestHelper.NORMAL_WAIT);
    }

    @Test
    void signalsAreSend() throws IOException {
        var signaled = new AtomicBoolean(false);
        try (var watch = new FileSystemEvents(testDir.getTestDirectory(), () -> signaled.set(true))) {
            Files.write(testDir.getTestFiles().get(0), "Hello".getBytes());
            await("Signal received").untilTrue(signaled);
        }

    }

}
