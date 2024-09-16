package engineering.swat.watch.impl;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

@FunctionalInterface
public interface ISubscribable<A, R> {
    Closeable subscribe(A target, Consumer<R> eventListener) throws IOException;
}
