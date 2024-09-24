package engineering.swat.watch.impl;

import java.nio.file.Path;
import java.util.Objects;

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
    public boolean equals(Object obj) {
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
}
