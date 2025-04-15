package engineering.swat.watch.impl.mac.nio.file;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

public class MacWatchEvent implements WatchEvent<Path> {
    private final Kind<Path> kind;
    private final Path context;

    public MacWatchEvent(Kind<Path> kind, Path context) {
        this.kind = kind;
        this.context = context;
    }

    // -- WatchEvent --

    @Override
    public Kind<Path> kind() {
        return kind;
    }

    @Override
    public int count() {
        return 1; // Simplifying assumption: Each event is unique
    }

    @Override
    public Path context() {
        return context;
    }
}
