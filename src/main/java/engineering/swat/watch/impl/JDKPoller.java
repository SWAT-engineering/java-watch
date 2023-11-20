package engineering.swat.watch.impl;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

enum JDKPoller {
    INSTANCE;

    private final WatchService service;
    private final Map<WatchKey, Consumer<List<WatchEvent<?>>>> watchers = new ConcurrentHashMap<>();
    private final Thread pollThread;

    private JDKPoller() {
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("Could not start watcher", e);
        }
        pollThread = new Thread(this::poller);
        pollThread.setDaemon(true);
        pollThread.setName("file-watcher poll events thread");
        pollThread.start();
    }

    private void poller() {
        while (true) {
            try {
                WatchKey hit;
                if ((hit = service.poll(1, TimeUnit.MILLISECONDS)) != null) {
                    var watchHandler = watchers.get(hit);
                    if (watchHandler != null) {
                        watchHandler.accept(hit.pollEvents());
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public Closeable register(Path path, Consumer<List<WatchEvent<?>>> changes) throws IOException {
        var key = path.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_MODIFY);
        watchers.put(key, changes);
        return new Closeable() {
            @Override
            public void close() throws IOException {
                key.cancel();
                watchers.remove(key);
            }
        };
    }
}
