package engineering.swat.watch.impl;

import java.nio.file.Path;
import java.util.Objects;

import org.checkerframework.checker.nullness.qual.Nullable;

public class SubscriptionKey {
    private final Path path;
    private final boolean recursive;

    public SubscriptionKey(Path path, boolean recursive) {
        this.path = path;
        this.recursive = recursive;
    }

    public Path getPath() {
        return path;
    }

    public boolean isRecursive() {
        return recursive;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof SubscriptionKey) {
            var other = (SubscriptionKey)obj;
            return (other.recursive == recursive)
                && other.path.equals(path);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, recursive);
    }

    @Override
    public String toString() {
        return path.toString() + (recursive ? "[recursive]" : "");
    }
}
