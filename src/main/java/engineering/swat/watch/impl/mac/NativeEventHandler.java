package engineering.swat.watch.impl.mac;

import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * <p>
 * Handler for native events, intended to be used for the construction of
 * JDK's {@link WatchEvent}s (and continue downstream consumption).
 * </p>
 *
 * <p>
 * In each call, the types of {@code kind} and {@code context} depend
 * specifically on the given native event: they're {@code Kind<Path>} and
 * {@code Path} for non-overflows, but they're {@code Kind<Object>} and
 * {@code Object} for overflows. This precision is needed to construct
 * {@link WatchEvent}s, where the types of {@code kind} and {@code context}
 * are correlated. Note: {@link java.util.function.BiConsumer} doesn't give
 * the required precision (i.e., its type parameters are initialized only
 * once for all calls).
 * </p>
 */
@FunctionalInterface
public interface NativeEventHandler {
    <T> void handle(Kind<T> kind, @Nullable T context);
}
