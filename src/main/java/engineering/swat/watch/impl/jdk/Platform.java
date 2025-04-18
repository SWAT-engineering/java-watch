package engineering.swat.watch.impl.jdk;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.nio.file.Watchable;

import engineering.swat.watch.impl.mac.MacWatchService;
import engineering.swat.watch.impl.mac.MacWatchable;

public interface Platform {
    WatchService newWatchService() throws IOException;
    Watchable newWatchable(Path path);

    public static Platform get() {

        if (com.sun.jna.Platform.isMac()) {
            return new Platform() {
                @Override
                public WatchService newWatchService() throws IOException {
                    return new MacWatchService();
                }
                @Override
                public Watchable newWatchable(Path path) {
                    return new MacWatchable(path);
                }
            };
        }

        else {
            return new Platform() {
                @Override
                public WatchService newWatchService() throws IOException {
                    return FileSystems.getDefault().newWatchService();
                }
                @Override
                public Watchable newWatchable(Path path) {
                    return path;
                }
            };
        }
    }
}
