package engineering.swat.watch.impl.jdk;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import engineering.swat.watch.ActiveWatch;
import engineering.swat.watch.WatchEvent;

public abstract class JDKBaseWatch implements ActiveWatch {

    protected final Path path;
    protected final Executor exec;
    protected final Consumer<WatchEvent> eventHandler;

    protected JDKBaseWatch(Path path, Executor exec, Consumer<WatchEvent> eventHandler) {
        this.path = path;
        this.exec = exec;
        this.eventHandler = eventHandler;
    }
}
