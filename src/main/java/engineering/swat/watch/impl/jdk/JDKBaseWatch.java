package engineering.swat.watch.impl.jdk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

public abstract class JDKBaseWatch implements ActiveWatch {
    private final Logger logger = LogManager.getLogger();

    protected final Path path;
    protected final Executor exec;
    protected final Consumer<WatchEvent> eventHandler;

    protected JDKBaseWatch(Path path, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.path = path;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    public void start() throws IOException {
        try {
            if (!runIfFirstTime()) {
                throw new IllegalStateException("Could not restart already-started watch for: " + path);
            }
            logger.debug("Started watch for: {}", path);
        } catch (Exception e) {
            throw new IOException("Could not start watch for: " + path, e);
        }
    }

    /**
     * Runs this watch if it's the first time. Intended to be called by method
     * `start`.
     *
     * @return `true` iff it's the first time this method is called
     * @throws IOException When an I/O exception of some sort (e.g., a nested
     * watch failed to start) has occurred
     */
    protected abstract boolean runIfFirstTime() throws IOException;
}
