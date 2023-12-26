package engineering.swat.watch;

import java.nio.file.Path;

import org.checkerframework.checker.nullness.qual.Nullable;

public class WatchEvent {

    public enum Kind {
        CREATED, MODIFIED, DELETED
    }

    private final Kind kind;
    private final Path rootPath;
    private final Path relativePath;

    public WatchEvent(Kind kind, Path rootPath, @Nullable Path relativePath) {
        this.kind = kind;
        this.rootPath = rootPath;
        this.relativePath = relativePath == null ? Path.of("") : relativePath;
    }

    public Kind getKind() {
        return this.kind;
    }

    public Path getRelativePath() {
        return relativePath;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public Path calculateFullPath() {
        return rootPath.resolve(relativePath);
    }

}
