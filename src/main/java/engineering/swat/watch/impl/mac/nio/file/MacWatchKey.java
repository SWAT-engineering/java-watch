package engineering.swat.watch.impl.mac.nio.file;

import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.nio.file.ExtendedWatchEventModifier;

public class MacWatchKey implements Runnable, WatchKey {

    private final MacWatchable watchable;
    private final MacWatchService service;
    private final AtomicReference<Configuration> configuration;
    private final BlockingQueue<WatchEvent<Path>> pendingEvents;

    private volatile JNAFacade facade;
    private volatile State state;

    public MacWatchKey(MacWatchable watchable, MacWatchService service, Kind<?>[] kinds, Modifier[] modifiers) {
        this.watchable = watchable;
        this.service = service;
        this.configuration = new AtomicReference<>(new Configuration(kinds, modifiers, false));
        this.pendingEvents = new LinkedBlockingQueue<>();

        this.state = State.READY;
    }

    public void update(Kind<?>[] kinds, Modifier[] modifiers) {
        configuration.set(new Configuration(kinds, modifiers, true));
    }

    private static enum State {
        READY, SIGNALLED, CANCELLED
    }

    private static class Configuration {
        private final Set<Kind<?>> kinds;
        private final Set<Modifier> modifiers;

        private Configuration(Kind<?>[] kinds, Modifier[] modifiers, boolean addOverflow) {
            this.kinds = new HashSet<>(Arrays.asList(kinds));
            this.modifiers = new HashSet<>(Arrays.asList(modifiers));

            if (addOverflow) {
                this.kinds.add(OVERFLOW);
            }
        }
    }

    // -- Runnable --

    @Override
    public synchronized void run() {
        if (facade == null) {
            facade = new JNAFacade(this::handle, watchable);
        }
    }

    private void handle(MacWatchEvent[] events) {
        switch (state) {

            case READY:
                state = State.SIGNALLED;
                service.offer(this);
                // Fall through, intentionally

            case SIGNALLED:
                var c = configuration.get();
                for (var e : events) {

                    // Ignore `e` when we're not watching its kind
                    if (!c.kinds.contains(e.kind())) {
                        continue;
                    }

                    // Ignore `e` when we're not watching its location (i.e.,
                    // we're watching only direct children of the root, and `e`
                    // is a descendant, but it's not a direct child)
                    if (!c.modifiers.contains(ExtendedWatchEventModifier.FILE_TREE)) {
                        var context = e.context();
                        if (!Objects.equals(context, context.getFileName())) {
                            continue;
                        }
                    }

                    pendingEvents.offer(e);
                }
                service.offer(this);
                break;

            case CANCELLED:
                break;
        }
    }

    // -- WatchKey --

    @Override
    public boolean isValid() {
        // TODO: Also check if "the object is no longer accessible"
        return state != State.CANCELLED && !service.isClosed();
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
        var ret = new ArrayList<WatchEvent<?>>(pendingEvents.size());
        pendingEvents.drainTo(ret);
        return ret;
    }

    @Override
    public boolean reset() {
        switch (state) {
            case READY:
                break;
            case SIGNALLED:
                if (!pendingEvents.isEmpty()) {
                    service.offer(this);
                } else {
                    state = State.SIGNALLED;
                }
                break;
            case CANCELLED:
                break;
        }

        return !isValid();
    }

    @Override
    public synchronized void cancel() {
        state = State.CANCELLED;
        if (facade != null) {
            facade.close();
        }
    }

    @Override
    public Watchable watchable() {
        return watchable;
    }
}
