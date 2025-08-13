/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2023, Swat.engineering
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package engineering.swat.watch.impl.mac;

import static java.nio.file.attribute.PosixFilePermission.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class NativeLibrary {

    public static native long start(String path, NativeEventHandler handler);
    public static native void stop(long watchId);

    public static boolean isMac() {
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

    private static OutputStream openPrivateStream(Path forFile, OpenOption... flags) throws IOException {
        return Channels.newOutputStream(
            Files.newByteChannel(forFile, Set.of(flags), PRIVATE_FILE)
        );
    }

    private static void loadLibrary(String path) {
        try {
            // in most cases the file is inside of a jar
            // so we have to copy it out and load that file instead
            var localCopy = Files.createTempFile("watch", ".dylib", PRIVATE_FILE);
            localCopy.toFile().deleteOnExit();
            try (var libStream = NativeLibrary.class.getResourceAsStream(path)) {
                if (libStream != null) {
                    try (var writer = openPrivateStream(localCopy, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        libStream.transferTo(writer);
                    }
                    System.load(localCopy.toString());
                }
            }
        }
        catch (Throwable e) {
            throw new IllegalStateException("We could not load: " + path, e);
        }
    }
}
