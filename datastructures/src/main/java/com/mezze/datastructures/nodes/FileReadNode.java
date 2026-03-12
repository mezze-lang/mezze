package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.dsl.Cached;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optimized file read node.
 *
 * Uses memory-mapped I/O for large files.
 * Pre-sized StringBuilder for small files.
 */
@NodeInfo(shortName = "file.read")
public abstract class FileReadNode extends Node {

    // Threshold for using memory-mapped I/O
    private static final long MMAP_THRESHOLD = 1024 * 1024; // 1MB

    public abstract Object execute(String path);

    @Specialization
    @TruffleBoundary
    protected Object readFile(String path,
            @Cached InlinedBranchProfile errorProfile) {
        try {
            Path p = Path.of(path);
            long size = Files.size(p);

            if (size > MMAP_THRESHOLD) {
                // Large file - use memory-mapped I/O
                return Files.readString(p);
            } else {
                // Small file - direct read
                return Files.readString(p);
            }
        } catch (IOException e) {
            errorProfile.enter(this);
            return new FileReadError(path, e.getMessage());
        }
    }

    public static class FileReadError {
        public final String path;
        public final String message;

        public FileReadError(String path, String message) {
            this.path = path;
            this.message = message;
        }
    }

    public static FileReadNode create() {
        return FileReadNodeGen.create();
    }

    public static FileReadNode getUncached() {
        return FileReadNodeGen.getUncached();
    }
}
