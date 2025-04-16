package engineering.swat.watch.impl.mac.nio.file;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

public class MacWatchEvent<T> implements WatchEvent<T> {
    private final Kind<T> kind;
    private final T context;

    private MacWatchEvent(Kind<T> kind, T context) {
        this.kind = kind;
        this.context = context;
    }

    public static WatchEvent<?> create(Kind<?> kind, Path context) {
        if (kind == ENTRY_CREATE) {
            return new MacWatchEvent<Path>(ENTRY_CREATE, context);
        }
        if (kind == ENTRY_MODIFY) {
            return new MacWatchEvent<Path>(ENTRY_MODIFY, context);
        }
        if (kind == ENTRY_DELETE) {
            return new MacWatchEvent<Path>(ENTRY_DELETE, context);
        }
        if (kind == OVERFLOW) {
            return new MacWatchEvent<Object>(OVERFLOW, null);
        }

        throw new IllegalArgumentException("Unexpected kind: " + kind);
    }

    // -- WatchEvent --

    @Override
    public Kind<T> kind() {
        return kind;
    }

    @Override
    public int count() {
        return 1; // Simplifying assumption: Each event is unique
    }

    @Override
    public T context() {
        return context;
    }
}
