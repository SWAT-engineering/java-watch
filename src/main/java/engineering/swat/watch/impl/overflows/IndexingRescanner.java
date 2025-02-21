package engineering.swat.watch.impl.overflows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

public class IndexingRescanner extends MemorylessRescanner {
    private final Logger logger = LogManager.getLogger();
    private final Map<Path, FileTime> index = new ConcurrentHashMap<>();

    public IndexingRescanner(Executor exec, boolean recursive) {
        super(exec, recursive);
    }

    public void indexContent(Path root, boolean recursive) {
        var maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (var content = contentOf(root, maxDepth, logger)) {
            content.forEach(this::index);
        }
    }

    private void index(Path p) {
        try {
            index.put(p, Files.getLastModifiedTime(p));
        } catch (IOException e) {
            logger.error("Could not get modification time of: {} ({})", p, e);
        }
    }

    // -- MemorylessScanner --

    @Override
    protected Stream<WatchEvent> generateEvents(Path path) {
        try {
            var lastModifiedOld = index.get(path);
            var lastModifiedNew = Files.getLastModifiedTime(path);

            // The file isn't indexed yet
            if (lastModifiedOld == null) {
                index.put(path, lastModifiedNew);
                return super.generateEvents(path);
            }

            // The file is already indexed, and the old modification time is
            // strictly before the new modification time
            else if (lastModifiedOld.compareTo(lastModifiedNew) < 0) {
                index.put(path, lastModifiedNew);
                return Stream.of(modified(path));
            }

            // The file is already indexed, but the old modification time isn't
            // strictly before the new modification time
            else {
                return Stream.empty();
            }

        } catch (IOException e) {
            logger.error("Could not generate events (while rescanning) for: {} ({})", path, e);
            return Stream.empty();
        }
    }

    @Override
    public void accept(ActiveWatch watch, WatchEvent event) {
        var kind = event.getKind();
        var fullPath = event.calculateFullPath();

        switch (kind) {
            case MODIFIED:
                // If a modified event happens for a path that's not in the
                // index yet, then a create event might have been missed.
                if (!index.containsKey(fullPath)) {
                    watch.handleEvent(created(fullPath));
                }
                // Fallthrough intended
            case CREATED:
                index(fullPath);
                break;
            case DELETED:
                index.remove(fullPath);
                break;
            case OVERFLOW:
                super.accept(watch, event);
                break;
        }
    }
}
