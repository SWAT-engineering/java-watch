package engineering.swat.watch.impl.mac.nio.file;

import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MacWatchable implements Watchable {
    private final Path path;
    private final Map<MacWatchService, MacWatchKey> registrations;

    public MacWatchable(Path path) {
        this.path = path;
        this.registrations = new ConcurrentHashMap<>();
    }

    public Path getPath() {
        return path;
    }

    public void unregister(MacWatchService watcher) {
        registrations.remove(watcher);
    }

    // -- Watchable --

    @Override
    public WatchKey register(WatchService watcher, Kind<?>[] events, Modifier... modifiers) throws IOException {
        if (watcher instanceof MacWatchService) {
            var service = (MacWatchService) watcher;
            var key = registrations.computeIfAbsent(service, k -> new MacWatchKey(this, k));

            events = Arrays.copyOf(events, events.length + 1);
            events[events.length - 1] = OVERFLOW; // Always register for `OVERFLOW` events (per this method's docs)
            key.reconfigure(events, modifiers);

            return key;
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public WatchKey register(WatchService watcher, Kind<?>... events) throws IOException {
        return register(watcher, events, new WatchEvent.Modifier[0]);
    }
}
