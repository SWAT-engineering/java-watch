package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class JDKDirectoryWatcher implements Closeable {
    private final Path directory;
    private final Executor exec;
    private final Consumer<WatchEvent<?>> eventHandler;
    private @MonotonicNonNull Closeable activeWatch;

    public JDKDirectoryWatcher(Path directory, Executor exec, Consumer<WatchEvent<?>> eventHandler) {
        this.directory = directory;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }

    public void start() throws IOException {
        try {
            activeWatch = JDKPoller.INSTANCE.register(directory, this::handleChanges);
        } catch (IOException e) {
            throw new IOException("Could not register directory watcher for: " + directory, e);
        }
    }

    private void handleChanges(List<WatchEvent<?>> events) {
    }

    @Override
    public void close() throws IOException {
        if (activeWatch != null) {
            activeWatch.close();
        }
    }


}
