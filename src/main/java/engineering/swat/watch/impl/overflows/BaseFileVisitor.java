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
package engineering.swat.watch.impl.overflows;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.EnumSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import engineering.swat.watch.WatchEvent;
import engineering.swat.watch.WatchScope;

/**
 * Base extension of {@link SimpleFileVisitor}, intended to be further
 * specialized by subclasses to auto-handle {@link WatchEvent.Kind#OVERFLOW}
 * events. In particular, method {@link #walkFileTree} of this class internally
 * calls {@link Files#walkFileTree} to visit the file tree that starts at
 * {@link #path}, with a maximum depth inferred from {@link #scope}. Subclasses
 * can be specialized, for instance, to generate synthetic events or index a
 * file tree.
 */
public class BaseFileVisitor extends SimpleFileVisitor<Path> {
    private final Logger logger = LogManager.getLogger();

    protected final Path path;
    protected final WatchScope scope;

    public BaseFileVisitor(Path path, WatchScope scope) {
        this.path = path;
        this.scope = scope;
    }

    public void walkFileTree() {
        var options = EnumSet.noneOf(FileVisitOption.class);
        var maxDepth = scope == WatchScope.PATH_AND_ALL_DESCENDANTS ? Integer.MAX_VALUE : 1;
        try {
            Files.walkFileTree(path, options, maxDepth, this);
        } catch (IOException e) {
            logger.error("Could not walk: {} ({})", path, e);
        }
    }

    // -- SimpleFileVisitor --

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        logger.error("Could not walk regular file: {} ({})", file, exc);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        if (exc != null) {
            logger.error("Could not walk directory: {} ({})", dir, exc);
        }
        return FileVisitResult.CONTINUE;
    }
}
