package engineering.swat.watch.impl.mac.nio.file;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MacWatchable implements Watchable {

    private final Path path;
    private final Map<MacWatchService, MacWatchKey> registrations;

    public MacWatchable(Path path) {
        this.path = path;
        this.registrations = new ConcurrentHashMap<>();;
    }

    public Path getPath() {
        return path;
    }

    // -- Watchable --

    @Override
    public WatchKey register(WatchService watcher, Kind<?>[] events, Modifier... modifiers) throws IOException {
        if (!(watcher instanceof MacWatchService)) {
            throw new IllegalArgumentException();
        }

        var service = (MacWatchService) watcher;

        var newKey = new MacWatchKey(this, service, events, modifiers);
        var oldKey = registrations.putIfAbsent(service, newKey);
        if (oldKey == null) {
            newKey.run();
            return newKey;
        } else {
            oldKey.update(events, modifiers);
            return oldKey;
        }
    }

    @Override
    public WatchKey register(WatchService watcher, Kind<?>... events) throws IOException {
        return register(watcher, events, new WatchEvent.Modifier[0]);
    }
}
