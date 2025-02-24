package engineering.swat.watch.impl.overflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.impl.EventHandlingWatch;

public class MemorylessRescanner implements BiConsumer<EventHandlingWatch, WatchEvent> {
    private final Logger logger = LogManager.getLogger();
    private final Executor exec;
    private final int maxDepth;

    public MemorylessRescanner(Executor exec, boolean recursive) {
        this.exec = exec;
        this.maxDepth = recursive ? Integer.MAX_VALUE : 1;
    }

    protected void rescan(EventHandlingWatch watch) {
        var root = watch.getPath();
        try (var content = contentOf(root, maxDepth, logger)) {
            content
                .flatMap(this::generateEvents)
                .map(watch::relativize)
                .forEach(watch::handleEvent);
        }
    }

    protected Stream<WatchEvent> generateEvents(Path path) {
        try {
            if (Files.size(path) == 0) {
                return Stream.of(created(path));
            } else {
                return Stream.of(created(path), modified(path));
            }
        } catch (IOException e) {
            logger.error("Could not generate events (while rescanning) for: {} ({})", path, e);
            return Stream.empty();
        }
    }

    protected static WatchEvent created(Path path) {
        return new WatchEvent(WatchEvent.Kind.CREATED, path);
    }

    protected static WatchEvent modified(Path path) {
        return new WatchEvent(WatchEvent.Kind.MODIFIED, path);
    }

    protected static Stream<Path> contentOf(Path path, int maxDepth, Logger logger) {
        try {
            return Files.walk(path, maxDepth).filter(p -> p != path);
        } catch (IOException e) {
            logger.error("Could not walk: {} ({})", path, e);
            return Stream.empty();
        }
    }

    // -- BiConsumer --

    @Override
    public void accept(EventHandlingWatch watch, WatchEvent event) {
        if (event.getKind() == WatchEvent.Kind.OVERFLOW) {
            exec.execute(() -> rescan(watch));
        }
    }
}
