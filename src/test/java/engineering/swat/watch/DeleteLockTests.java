package engineering.swat.watch;


import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private static void recursiveDelete(Path target) throws IOException {
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    private void deleteAndVerify(Path target, WatchScope scope) throws IOException {
        try (var watch = Watcher.watch(target, scope).onEvent(ev -> {}).start()) {
            recursiveDelete(target);
            assertFalse(Files.exists(target), "The file/directory shouldn't exist anymore");
        }
    }

    @Test
    void watchedFileCanBeDeleted() throws IOException {
        deleteAndVerify(
            testDir.getTestFiles().get(0),
            WatchScope.SINGLE
        );
    }


    @Test
    void watchedDirectoryCanBeDeleted() throws IOException {
        deleteAndVerify(
            testDir.getTestDirectory(),
            WatchScope.INCLUDING_CHILDREN
        );
    }


    @Test
    void watchedRecursiveDirectoryCanBeDeleted() throws IOException {
        deleteAndVerify(
            testDir.getTestDirectory(),
            WatchScope.INCLUDING_ALL_DESCENDANTS
        );
    }
}
