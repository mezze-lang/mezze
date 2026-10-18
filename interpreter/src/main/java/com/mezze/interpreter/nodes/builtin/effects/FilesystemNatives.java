package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.builtin.io.BytesNatives.MezzeBytes;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native implementations for LocalDisk FileSystem effect (#82).
 *
 * Thirteen primitive ops mapping to java.nio.file kernel fast paths:
 *   read_bytes   — Files.readAllBytes
 *   write_bytes  — Files.write (create-or-truncate)
 *   delete       — Files.delete (unlink)
 *   list         — Files.newDirectoryStream (names only)
 *   list_with_stat — Files.newDirectoryStream + readAttributes (bundled)
 *   stat         — Files.readAttributes; NotFound is a legal FileKind
 *   create_directory — Files.createDirectory (mkdir)
 *   copy_file    — Files.copy (kernel sendfile on Linux)
 *   move_file    — Files.move (atomic rename on same fs)
 *   open         — FileChannel.open (with mode)
 *   read_chunk   — FileChannel.read (advances position)
 *   write_chunk  — FileChannel.write (advances position)
 *   close        — FileChannel.close (idempotent)
 *
 * All ops return {@code Result 'a FsError}. FsError constructors match
 * the Mezze-side ADT in {@code std::effects::filesystem}.
 */
public final class FilesystemNatives {

    private FilesystemNatives() {}

    // ==================== FileHandle ====================

    /**
     * Handle for streaming file I/O. Wraps a FileChannel opened in a
     * specific mode. Used by open / read_chunk / write_chunk / close.
     *
     * The `closed` flag makes close() idempotent — repeat calls no-op.
     */
    public static final class MezzeFileHandle {
        final FileChannel channel;
        final String path;
        final AtomicBoolean closed = new AtomicBoolean(false);

        MezzeFileHandle(FileChannel channel, String path) {
            this.channel = channel;
            this.path = path;
        }

        boolean isClosed() { return closed.get(); }
    }

    // ==================== Read / Write (whole-file) ====================

    @TruffleBoundary
    public static Object readBytes(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            byte[] data = Files.readAllBytes(Paths.get(path));
            return ok(new MezzeBytes(data));
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "read_bytes");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object writeBytes(MezzeRecord args) {
        String path = getString(args, "path");
        MezzeBytes bytes = getBytes(args, "bytes");
        try {
            Files.write(Paths.get(path), bytes.getData());
            return ok(MezzeRecord.empty());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "write_bytes");
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("No space left")) {
                return errDiskFull(path, bytes.length());
            }
            return errIO(path, msg);
        }
    }

    // ==================== Metadata ====================

    @TruffleBoundary
    public static Object stat(MezzeRecord args) {
        String path = getString(args, "path");
        Path p = Paths.get(path);
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            String kind = attrs.isDirectory() ? "Directory"
                        : attrs.isRegularFile() ? "File"
                        : "Other";
            return ok(fileStatRecord(kind, attrs.size(), attrs.lastModifiedTime().toMillis()));
        } catch (NoSuchFileException e) {
            // NotFound is a legal FileKind, not an FsError — inspection
            // path stays fast for existence checks in tight loops.
            return ok(fileStatRecord("NotFound", 0L, 0L));
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "stat");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object list(MezzeRecord args) {
        String path = getString(args, "path");
        Path p = Paths.get(path);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
            List<Object> names = new ArrayList<>();
            for (Path child : stream) {
                names.add(child.getFileName().toString());
            }
            return ok(MezzeSeq.fromArray(names.toArray()));
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (java.nio.file.NotDirectoryException e) {
            return errNotADirectory(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "list");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object listWithStat(MezzeRecord args) {
        String path = getString(args, "path");
        Path p = Paths.get(path);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
            List<Object> entries = new ArrayList<>();
            for (Path child : stream) {
                try {
                    BasicFileAttributes a = Files.readAttributes(child, BasicFileAttributes.class);
                    String kind = a.isDirectory() ? "Directory"
                                : a.isRegularFile() ? "File"
                                : "Other";
                    Object stat = fileStatRecord(kind, a.size(), a.lastModifiedTime().toMillis());
                    entries.add(dirEntryRecord(child.getFileName().toString(), stat));
                } catch (IOException perEntry) {
                    // Skip entries we can't stat (e.g. broken symlinks) —
                    // real filesystems have transiently-inconsistent
                    // entries and users don't want a full failure.
                }
            }
            return ok(MezzeSeq.fromArray(entries.toArray()));
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (java.nio.file.NotDirectoryException e) {
            return errNotADirectory(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "list_with_stat");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    // ==================== Structural mutation ====================

    @TruffleBoundary
    public static Object delete(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            Files.delete(Paths.get(path));
            return ok(MezzeRecord.empty());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            return errNotEmpty(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "delete");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object createDirectory(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            Files.createDirectory(Paths.get(path));
            return ok(MezzeRecord.empty());
        } catch (FileAlreadyExistsException e) {
            return errAlreadyExists(path);
        } catch (NoSuchFileException e) {
            // Parent doesn't exist.
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "create_directory");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    /**
     * Kernel-level copy. On Linux this uses sendfile() when available,
     * avoiding userspace round-trip for large files. That's why this
     * is an effect primitive, not a derived helper.
     */
    @TruffleBoundary
    public static Object copyFile(MezzeRecord args) {
        String src = getString(args, "src");
        String dest = getString(args, "dest");
        try {
            Files.copy(Paths.get(src), Paths.get(dest));
            return ok(MezzeRecord.empty());
        } catch (NoSuchFileException e) {
            return errNotFound(src);
        } catch (FileAlreadyExistsException e) {
            return errAlreadyExists(dest);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(src, "copy_file");
        } catch (IOException e) {
            return errIO(src, e.getMessage());
        }
    }

    /**
     * Atomic move. On the same filesystem Linux uses rename(2) —
     * atomic; observers never see a half-moved file. Derived
     * copy+delete would break that guarantee.
     */
    @TruffleBoundary
    public static Object moveFile(MezzeRecord args) {
        String src = getString(args, "src");
        String dest = getString(args, "dest");
        try {
            Files.move(Paths.get(src), Paths.get(dest), StandardCopyOption.ATOMIC_MOVE);
            return ok(MezzeRecord.empty());
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Cross-filesystem move — fall back to non-atomic.
            try {
                Files.move(Paths.get(src), Paths.get(dest));
                return ok(MezzeRecord.empty());
            } catch (IOException retry) {
                return errIO(src, retry.getMessage());
            }
        } catch (NoSuchFileException e) {
            return errNotFound(src);
        } catch (FileAlreadyExistsException e) {
            return errAlreadyExists(dest);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(src, "move_file");
        } catch (IOException e) {
            return errIO(src, e.getMessage());
        }
    }

    // ==================== Streaming ====================

    @TruffleBoundary
    public static Object open(MezzeRecord args) {
        String path = getString(args, "path");
        String mode = getVariantTag(args, "mode");
        try {
            FileChannel channel;
            switch (mode) {
                case "Read":
                    channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ);
                    break;
                case "Write":
                    channel = FileChannel.open(Paths.get(path),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                    break;
                case "Append":
                    channel = FileChannel.open(Paths.get(path),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                    break;
                default:
                    return errIO(path, "Unknown OpenMode: " + mode);
            }
            return ok(new MezzeFileHandle(channel, path));
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "open");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object readChunk(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        long maxBytes = getLong(args, "max_bytes");
        if (h.isClosed()) {
            return errClosedHandle(h.path);
        }
        try {
            int cap = (int) Math.min(maxBytes, Integer.MAX_VALUE);
            ByteBuffer buf = ByteBuffer.allocate(cap);
            int n = h.channel.read(buf);
            if (n <= 0) {
                // EOF or nothing read — return empty Bytes.
                return ok(new MezzeBytes(new byte[0]));
            }
            byte[] out = new byte[n];
            buf.flip();
            buf.get(out);
            return ok(new MezzeBytes(out));
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object writeChunk(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        MezzeBytes bytes = getBytes(args, "bytes");
        if (h.isClosed()) {
            return errClosedHandle(h.path);
        }
        try {
            h.channel.write(ByteBuffer.wrap(bytes.getData()));
            return ok(MezzeRecord.empty());
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object close(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        if (h.closed.compareAndSet(false, true)) {
            try {
                h.channel.close();
                return ok(MezzeRecord.empty());
            } catch (IOException e) {
                return errIO(h.path, e.getMessage());
            }
        }
        // Already closed — idempotent, no-op success.
        return ok(MezzeRecord.empty());
    }

    // ==================== Durability ====================

    /**
     * `FileDurability.fsync` — flush this handle's writes to the storage
     * device. `force(true)` syncs file METADATA as well as content: a write
     * whose length change never reached the inode is still a lost write.
     *
     * Separate from `close`, which flushes to the OS but makes no promise the
     * device has the bytes. That distinction is the whole reason this is an
     * effect of its own rather than a flag.
     */
    @TruffleBoundary
    public static Object fsync(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        if (h.isClosed()) {
            return errClosedHandle(h.path);
        }
        try {
            h.channel.force(true);
            return ok(MezzeRecord.empty());
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    /**
     * `FileDurability.fsync_dir` — durably record a directory's own entries,
     * which is what makes a CREATE or RENAME survive power loss. Syncing the
     * file is not enough: the name pointing at it lives in the parent.
     *
     * POSIX-specific, and that is why it is explicit. Opening a directory for
     * READ is legal on Linux and macOS; Windows refuses it, which surfaces as
     * the IOException below rather than as a silent no-op — a fake success
     * here would be a lie about durability.
     */
    @TruffleBoundary
    public static Object fsyncDir(MezzeRecord args) {
        String path = getString(args, "path");
        try (FileChannel dir = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {
            dir.force(true);
            return ok(MezzeRecord.empty());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "fsync_dir");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    // ==================== Record helpers ====================

    private static final String[] FILE_STAT_FIELDS = {"kind", "size", "modified"};
    private static final String[] DIR_ENTRY_FIELDS = {"name", "stat"};

    private static MezzeRecord fileStatRecord(String kind, long size, long modifiedMs) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(kind);
        Object kindVariant = MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        return MezzeRecord.fromFields(FILE_STAT_FIELDS, new Object[]{kindVariant, size, modifiedMs});
    }

    private static MezzeRecord dirEntryRecord(String name, Object stat) {
        return MezzeRecord.fromFields(DIR_ENTRY_FIELDS, new Object[]{name, stat});
    }

    // ==================== Result / FsError helpers ====================

    private static MezzeVariant ok(Object value) {
        return MezzeVariant.ok(value);
    }

    private static MezzeVariant errNotFound(String path) {
        return err("FsNotFound", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant errPermissionDenied(String path, String operation) {
        return err("FsPermissionDenied",
            new String[]{"path", "operation"},
            new Object[]{path, operation});
    }

    private static MezzeVariant errAlreadyExists(String path) {
        return err("FsAlreadyExists", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant errNotEmpty(String path) {
        return err("FsNotEmpty", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant errNotADirectory(String path) {
        return err("FsNotADirectory", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant errIO(String path, String message) {
        return err("FsIOError",
            new String[]{"path", "message"},
            new Object[]{path, message != null ? message : "Unknown error"});
    }

    private static MezzeVariant errDiskFull(String path, long bytesNeeded) {
        return err("FsDiskFull",
            new String[]{"path", "bytes_needed"},
            new Object[]{path, bytesNeeded});
    }

    private static MezzeVariant errClosedHandle(String path) {
        return err("FsClosedHandle", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant err(String constructor, String[] fields, Object[] values) {
        MezzeRecord payload = MezzeRecord.fromFields(fields, values);
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(constructor);
        MezzeVariant errorVariant = MezzeVariant.singlePayload(
            MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    // ==================== Field helpers ====================

    private static String getString(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + name + "'");
    }

    private static long getLong(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return i;
        throw new NativeTypeMismatchException("Expected Int for field '" + name + "'");
    }

    private static MezzeBytes getBytes(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeBytes b) return b;
        throw new NativeTypeMismatchException("Expected Bytes for field '" + name + "'");
    }

    private static MezzeFileHandle getHandle(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeFileHandle h) return h;
        throw new NativeTypeMismatchException("Expected FileHandle for field '" + name + "'");
    }

    /**
     * Extract the tag name of a variant (e.g. "Read" from OpenMode.Read).
     * Used for OpenMode dispatch. Nullary variants only.
     */
    private static String getVariantTag(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeVariant v) {
            return v.getConstructorName();
        }
        throw new NativeTypeMismatchException("Expected variant for field '" + name + "'");
    }
}
