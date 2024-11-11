package engineering.swat.watch.impl.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

@FunctionalInterface
public interface ISubscribable<Key, Event> {
    Closeable subscribe(Key target, Consumer<Event> eventListener) throws IOException;
}
