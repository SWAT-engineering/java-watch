package engineering.swat.watch.impl.overflows;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import engineering.swat.watch.OnOverflow;
import engineering.swat.watch.TestDirectory;
import engineering.swat.watch.TestHelper;
import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;
import engineering.swat.watch.Watcher;
import engineering.swat.watch.impl.EventHandlingWatch;

class IndexingRescannerTests {

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
    void onlyEventsForFilesInScopeAreIssued() throws IOException, InterruptedException {
        var path = testDir.getTestDirectory();

        // Prepare a watch that monitors only the children (not all descendants)
        // of `path`
        var eventsOnlyForChildren = new AtomicBoolean(true);
        var watchConfig = Watcher.watch(path, WatchScope.PATH_AND_CHILDREN)
            .approximate(OnOverflow.NONE) // Disable the auto-handler here; we'll have an explicit one below
            .on(e -> {
                if (e.getRelativePath().getNameCount() > 1) {
                    eventsOnlyForChildren.set(false);
                }
            });

        try (var watch = (EventHandlingWatch) watchConfig.start()) {
            // Create a rescanner that initially indexes all descendants (not
            // only the children) of `path`
            var rescanner = new IndexingRescanner(
                ForkJoinPool.commonPool(), path,
                WatchScope.PATH_AND_ALL_DESCENDANTS);

            // Trigger a rescan. As only the children (not all descendants) of
            // `path` are watched, the rescan should issue events only for those
            // children.
            var overflow = new WatchEvent(WatchEvent.Kind.OVERFLOW, path);
            rescanner.accept(watch, overflow);
            Thread.sleep(TestHelper.SHORT_WAIT.toMillis());

            await("No events for non-children descendants should have been issued")
                .until(eventsOnlyForChildren::get);
        }
    }
}
