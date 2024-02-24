package engineering.swat.watch;


import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


class SmokeTests {
    static @MonotonicNonNull TestDirectory testDir;

    @BeforeAll
    static void setupEverything() throws IOException {
        testDir = new TestDirectory();
        Awaitility.setDefaultTimeout(2, TimeUnit.SECONDS);
    }

    @AfterAll
    static void cleanupDirectory()  throws IOException {
        if (testDir != null) {
            testDir.close();
        }
    }

    @Test
    void watchDirectory() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().get(0);
        var watchConfig = Watcher.singleDirectory(testDir.getTestDirectory())
            .onModified(p -> {if (p.equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await().alias("Target file change").until(changed::get);
        }
    }

    @Test
    void watchRecursiveDirectory() throws IOException, InterruptedException {
        var changed = new AtomicBoolean(false);
        var target = testDir.getTestFiles().stream()
            .filter(p -> !p.getParent().equals(testDir.getTestDirectory()))
            .findFirst()
            .orElseThrow();
        var watchConfig = Watcher.recursiveDirectory(testDir.getTestDirectory())
            .onModified(p -> {if (p.equals(target)) { changed.set(true); }})
            ;

        try (var activeWatch = watchConfig.start() ) {
            Files.writeString(target, "Hello world");
            await().alias("Nested file change").until(changed::get);
        }
    }


}
