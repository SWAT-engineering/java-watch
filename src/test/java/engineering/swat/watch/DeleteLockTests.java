package engineering.swat.watch;


import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.WatchEvent.Kind;

class DeleteLockTests {

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


    @FunctionalInterface
    private interface Deleter {
        void run(Path target) throws IOException;
    }

    @FunctionalInterface
    private interface Builder {
        Watcher build(Path target) throws IOException;
    }

    private static void recursiveDelete(Path target) throws IOException {
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    private void deleteAndVerify(Path target, Builder setup) throws IOException {
        try (var watch = setup.build(target).onEvent(ev -> {}).start()) {
            recursiveDelete(target);
            assertFalse(Files.exists(target), "The file/directory shouldn't exist anymore");
        }
    }

    @Test
    void watchedFileCanBeDeleted() throws IOException {
        deleteAndVerify(
            testDir.getTestFiles().get(0),
            Watcher::single
        );
    }


    @Test
    void watchedDirectoryCanBeDeleted() throws IOException {
        deleteAndVerify(
            testDir.getTestDirectory(),
            Watcher::singleDirectory
        );
    }


    @Test
    void watchedRecursiveDirectoryCanBeDeleted() throws IOException {
        deleteAndVerify(
            testDir.getTestDirectory(),
            Watcher::recursiveDirectory
        );
    }
}
