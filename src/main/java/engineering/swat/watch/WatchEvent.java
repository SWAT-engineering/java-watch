package engineering.swat.watch;

import java.nio.file.Path;

public class WatchEvent {

    public enum Kind {
        CREATED, MODIFIED, DELETED
    }

    private final Kind kind;
    private final Path rootPath;
    private final Path relativePath;

    public WatchEvent(Kind kind, Path rootPath, Path relativePath) {
        this.kind = kind;
        this.rootPath = rootPath;
        this.relativePath = relativePath;
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
