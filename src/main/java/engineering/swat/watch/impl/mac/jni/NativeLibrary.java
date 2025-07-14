package engineering.swat.watch.impl.mac.jni;

import static java.nio.file.attribute.PosixFilePermission.*;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

class NativeLibrary {
    private static boolean isMac() {
        var os = System.getProperty("os.name");
        return os != null && (os.toLowerCase().contains("mac") || os.toLowerCase().contains("darwin"));

    }

    private static boolean isAarch64() {
        var arch = System.getProperty("os.arch");
        return arch != null && arch.toLowerCase().equals("aarch64");
    }

    private static volatile boolean loaded = false;
    public static void load() {
        if (loaded) {
            return;
        }
        try {
            if (!isMac()) {
                throw new IllegalStateException("We should not be loading FileSystemEvents api on non mac machines");
            }
            String path = "/engineering/swat/watch/jni/";
            if (isAarch64()) {
                path += "macos-aarch64/";
            }
            else {
                path += "macos-x64/";
            }
            path += "librust_fsevents_jni.dylib";

            loadLibrary(path);
        } finally {
            loaded = true;
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> PRIVATE_FILE = PosixFilePermissions.asFileAttribute(Set.of(OWNER_READ, OWNER_WRITE , OWNER_EXECUTE));

    private static void loadLibrary(String path) {
        try {
            var localFile = FileSystemEvents.class.getResource(path);
            if (localFile.getProtocol().equals("file")) {
                System.load(localFile.getPath());
                return;
            }
            // in most cases the file is inside of a jar
            // so we have to copy it out and load that file instead
            var localCopy = Files.createTempFile("watch", ".dylib"/*  , PRIVATE_FILE*/);
            localCopy.toFile().deleteOnExit();
            try (var libStream = FileSystemEvents.class.getResourceAsStream(path)) {
                try (var writer = Files.newOutputStream(localCopy, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    libStream.transferTo(writer);
                }
                System.load(localCopy.toString());
            }
        }
        catch (Throwable e) {
            throw new IllegalStateException("We could not load: " + path, e);
        }
    }

}
