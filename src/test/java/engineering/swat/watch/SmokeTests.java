package engineering.swat.watch;


import static engineering.swat.watch.WatchEvent.Kind.*;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class SmokeTests {
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
    void watchDirectory() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().get(0);
        var watchConfig = Watcher.watch(testDir.getTestDirectory(), WatchScope.PATH_AND_CHILDREN)
            .onEvent(ev -> {if (ev.getKind() == MODIFIED && ev.calculateFullPath().equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await("Target file change").untilTrue(changed);
        }
    }

    @Test
    void watchRecursiveDirectory() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> !p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();
        var watchConfig = Watcher.watch(testDir.getTestDirectory(), WatchScope.PATH_AND_ALL_DESCENDANTS)
            .onEvent(ev -> { if (ev.getKind() == MODIFIED && ev.calculateFullPath().equals(target)) { changed.set(true);}})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await("Nested file change").untilTrue(changed);
        }
    }

    @Test
    void watchSingleFile() throws IOException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();

        var watchConfig = Watcher.watch(target, WatchScope.PATH_ONLY)
            .onEvent(ev -> {
                if (ev.calculateFullPath().equals(target)) {
                    changed.set(true);
                }
            });

        try (var watch = watchConfig.start()) {
            Files.writeString(target, "Hello world");
            await("Single file change").untilTrue(changed);
        }
    }


}
