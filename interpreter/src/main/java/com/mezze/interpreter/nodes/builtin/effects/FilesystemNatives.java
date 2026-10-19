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
 * Native implementations for the seven filesystem effects' Local performers
 * (#82), mapping to java.nio.file kernel fast paths.
 *
 * `FileSystem` (9, path-keyed): read_bytes → Files.readAllBytes, write_bytes →
 * Files.write, delete → Files.delete, list / list_with_stat →
 * Files.newDirectoryStream (± readAttributes), stat → Files.readAttributes
 * (NotFound is a legal FileKind), create_directory → Files.createDirectory,
 * copy_file → Files.copy (sendfile on Linux), move_file → Files.move (atomic
 * rename on the same fs).
 *
 * `FileStreaming` (8, handle-keyed) over one FileChannel: open, read_chunk /
 * write_chunk (advance the position), read_at / write_at (do NOT), seek,
 * truncate, close. Plus `FileDurability` (fsync, fsync_dir), `FileLock`,
 * `FileSymlinks`, `FilePermissions` and `FileWatch`.
 *
 * All ops return {@code Result 'a FsError}. FsError constructors match
 * the Mezze-side ADT in {@code std::effects::filesystem}.
 */
public final class FilesystemNatives {

    private FilesystemNatives() {}

    // ==================== Per-context open resources ====================

    /**
     * Every OS resource this backend has handed to guest code and not got
     * back, so that disposing the context releases it.
     *
     * Without this, forgetting `close` / `unlock` / `unwatch` leaks for the
     * life of the PROCESS, not of the program. A `FileChannel`'s fd is at
     * least reclaimed eventually by FileDescriptor's Cleaner, but a
     * `WatchService` runs a daemon thread that holds a strong reference back
     * to itself, so it is never unreachable and never collected: one missed
     * `unwatch` costs a thread and an inotify instance permanently. An
     * unreleased `FileLock` is worse than a leak — it keeps excluding other
     * processes.
     *
     * Registered through {@code nativeState}, which enrolls an AutoCloseable
     * for dispose, so {@code MezzeLanguage.disposeContext} drives it.
     * Deliberately a registry of handles rather than an fd count: dispose has
     * to actually close them, and the guest may still be holding the handle
     * value when the context goes.
     */
    static final class OpenResources implements AutoCloseable {
        final java.util.Set<MezzeFileHandle> files = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.Set<MezzeLockHandle> locks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.Set<MezzeWatchHandle> watches = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * Watches first — they own threads, and a thread can still be running
         * when the rest goes. Then locks, because releasing one unblocks
         * another process and the channel close that follows must not be what
         * does it. Files last; they hold nothing but an fd.
         *
         * Errors are swallowed: this runs during dispose, there is no caller
         * to return a Result to, and a failure to close one handle must not
         * abandon the others.
         */
        @Override
        public void close() {
            for (MezzeWatchHandle h : watches) {
                if (h.closed.compareAndSet(false, true)) {
                    try { h.service.close(); } catch (IOException ignored) { /* disposing */ }
                }
            }
            watches.clear();
            for (MezzeLockHandle h : locks) {
                if (h.released.compareAndSet(false, true)) {
                    try { h.lock.release(); } catch (IOException ignored) { /* disposing */ }
                    closeQuietly(h.channel);
                }
            }
            locks.clear();
            for (MezzeFileHandle h : files) {
                if (h.closed.compareAndSet(false, true)) closeQuietly(h.channel);
            }
            files.clear();
        }
    }

    @TruffleBoundary
    private static OpenResources openResources() {
        return com.mezze.interpreter.runtime.MezzeContext.get(null)
            .nativeState(OpenResources.class, OpenResources::new);
    }

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
            MezzeFileHandle handle = new MezzeFileHandle(channel, path);
            openResources().files.add(handle);
            return ok(handle);
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
            // Drop it from the dispose registry whether or not the close
            // succeeds: the fd is gone either way, and holding a reference to
            // a dead handle until dispose is the leak this registry exists to
            // prevent.
            openResources().files.remove(h);
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

    // ==================== Random access ====================

    /**
     * Positional read. Uses the two-arg `read(buf, position)` overload,
     * which does NOT move the channel position — so an index lookup cannot
     * disturb a sequential scan sharing the handle. That property is the
     * reason this exists rather than being seek-then-read.
     */
    @TruffleBoundary
    public static Object readAt(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        long offset = getLong(args, "offset");
        long maxBytes = getLong(args, "max_bytes");
        if (h.isClosed()) return errClosedHandle(h.path);
        if (offset < 0) return errIO(h.path, "negative offset: " + offset);
        try {
            int cap = (int) Math.min(maxBytes, Integer.MAX_VALUE);
            ByteBuffer buf = ByteBuffer.allocate(cap);
            int n = h.channel.read(buf, offset);
            if (n <= 0) return ok(new MezzeBytes(new byte[0]));
            byte[] out = new byte[n];
            buf.flip();
            buf.get(out);
            return ok(new MezzeBytes(out));
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (java.nio.channels.NonReadableChannelException e) {
            return errIO(h.path, "handle not opened for reading");
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    /** Positional write; also leaves the channel position alone. */
    @TruffleBoundary
    public static Object writeAt(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        long offset = getLong(args, "offset");
        MezzeBytes bytes = getBytes(args, "bytes");
        if (h.isClosed()) return errClosedHandle(h.path);
        if (offset < 0) return errIO(h.path, "negative offset: " + offset);
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes.getData());
            // A single write may be partial, so loop until the buffer drains
            // or the channel stops accepting — a short write that reported
            // success would silently truncate the caller's data.
            long pos = offset;
            while (buf.hasRemaining()) {
                int n = h.channel.write(buf, pos);
                if (n <= 0) return errIO(h.path, "write made no progress at offset " + pos);
                pos += n;
            }
            return ok(MezzeRecord.empty());
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (java.nio.channels.NonWritableChannelException e) {
            return errIO(h.path, "handle not opened for writing");
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    /**
     * Move the position. Seeking PAST the end is legal and does not extend
     * the file — a later write there creates a hole, which is how sparse
     * files happen. Rejecting it would make that impossible to express.
     */
    @TruffleBoundary
    public static Object seek(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        long offset = getLong(args, "offset");
        if (h.isClosed()) return errClosedHandle(h.path);
        if (offset < 0) return errIO(h.path, "negative offset: " + offset);
        try {
            h.channel.position(offset);
            return ok(MezzeRecord.empty());
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    /**
     * Cut to `size`. Note FileChannel.truncate does NOT grow a short file —
     * it is a no-op in that direction, and this preserves that rather than
     * emulating an extend, because a caller who wants growth wants a write
     * and should say so.
     */
    @TruffleBoundary
    public static Object truncate(MezzeRecord args) {
        MezzeFileHandle h = getHandle(args, "handle");
        long size = getLong(args, "size");
        if (h.isClosed()) return errClosedHandle(h.path);
        if (size < 0) return errIO(h.path, "negative size: " + size);
        try {
            h.channel.truncate(size);
            return ok(MezzeRecord.empty());
        } catch (ClosedChannelException e) {
            return errClosedHandle(h.path);
        } catch (java.nio.channels.NonWritableChannelException e) {
            return errIO(h.path, "handle not opened for writing");
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
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

    // ==================== Locking ====================

    /**
     * An acquired lock. Holds the channel as well as the {@link FileLock},
     * because releasing the lock without closing the channel leaks a file
     * descriptor per lock/unlock cycle — a lockfile loop would exhaust the
     * process. Opaque to the guest, which only passes it back to `unlock`.
     */
    public static final class MezzeLockHandle {
        final java.nio.channels.FileLock lock;
        final FileChannel channel;
        final String path;
        final java.util.concurrent.atomic.AtomicBoolean released =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        MezzeLockHandle(java.nio.channels.FileLock lock, FileChannel channel, String path) {
            this.lock = lock;
            this.channel = channel;
            this.path = path;
        }
    }

    /**
     * `FileLock.lock` — an advisory, process-wide lock on a whole file.
     *
     * A shared lock still needs a readable channel and an exclusive one a
     * writable channel, so the open mode follows the requested kind. The file
     * is CREATEd rather than required to exist, because the overwhelmingly
     * common use is a lockfile that the winner creates.
     *
     * Non-blocking mode returns `FsPermissionDenied` rather than an "already
     * held" variant: adding `FsLockHeld` to FsError would widen a type every
     * FileSystem caller matches on, for one op's benefit. Revisit if a real
     * caller needs to tell the two apart.
     *
     * Advisory is the honest word. On a network filesystem the lock may be a
     * no-op that reports success; that is the backend lying, not us, and it
     * is why this is a separate effect a backend can decline to implement.
     */
    @TruffleBoundary
    public static Object lock(MezzeRecord args) {
        String path = getString(args, "path");
        String kind = getVariantTag(args, "kind");
        boolean shared = "Shared".equals(kind);
        boolean blocking = getBool(args, "blocking");
        FileChannel channel = null;
        try {
            channel = shared
                ? FileChannel.open(Paths.get(path),
                    StandardOpenOption.READ, StandardOpenOption.CREATE)
                : FileChannel.open(Paths.get(path),
                    StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);
            java.nio.channels.FileLock l = blocking
                ? channel.lock(0L, Long.MAX_VALUE, shared)
                : channel.tryLock(0L, Long.MAX_VALUE, shared);
            if (l == null) {
                channel.close();
                return errPermissionDenied(path, "lock (already held)");
            }
            MezzeLockHandle handle = new MezzeLockHandle(l, channel, path);
            openResources().locks.add(handle);
            return ok(handle);
        } catch (java.nio.channels.OverlappingFileLockException e) {
            closeQuietly(channel);
            // Same JVM already holds an overlapping lock. Not an OS-level
            // contention failure, but indistinguishable to the caller.
            return errPermissionDenied(path, "lock (held by this process)");
        } catch (AccessDeniedException e) {
            closeQuietly(channel);
            return errPermissionDenied(path, "lock");
        } catch (NoSuchFileException e) {
            closeQuietly(channel);
            return errNotFound(path);
        } catch (IOException e) {
            closeQuietly(channel);
            return errIO(path, e.getMessage());
        }
    }

    /** `FileLock.unlock` — release and close. Idempotent, like `close`. */
    @TruffleBoundary
    public static Object unlock(MezzeRecord args) {
        MezzeLockHandle h = getLockHandle(args, "handle");
        if (!h.released.compareAndSet(false, true)) {
            return ok(MezzeRecord.empty());
        }
        openResources().locks.remove(h);
        try {
            h.lock.release();
            h.channel.close();
            return ok(MezzeRecord.empty());
        } catch (java.nio.channels.ClosedChannelException e) {
            return ok(MezzeRecord.empty());
        } catch (IOException e) {
            return errIO(h.path, e.getMessage());
        }
    }

    private static void closeQuietly(FileChannel c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do: we are already returning an error for the
            // reason we got here, and that reason is the one worth reporting.
        }
    }

    // ==================== Watching ====================

    /**
     * A registered WatchService and the key(s) it holds.
     *
     * Non-recursive is one key. Recursive registers every existing
     * subdirectory at `watch` time — the JDK has no recursive mode, and
     * directories created LATER are not auto-registered, which is a real
     * limitation of this implementation rather than of the design. A caller
     * that needs it re-watches after a Created event for a directory; said
     * here because discovering it from behaviour would be unpleasant.
     */
    public static final class MezzeWatchHandle {
        final java.nio.file.WatchService service;
        final java.nio.file.Path root;
        final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        MezzeWatchHandle(java.nio.file.WatchService service, java.nio.file.Path root) {
            this.service = service;
            this.root = root;
        }
    }

    @TruffleBoundary
    public static Object watch(MezzeRecord args) {
        String path = getString(args, "path");
        boolean recursive = getBool(args, "recursive");
        java.nio.file.Path root = Paths.get(path);
        if (!Files.isDirectory(root)) {
            return Files.exists(root) ? errNotADirectory(path) : errNotFound(path);
        }
        try {
            java.nio.file.WatchService svc = root.getFileSystem().newWatchService();
            registerWatch(root, svc);
            if (recursive) {
                try (java.util.stream.Stream<java.nio.file.Path> tree = Files.walk(root)) {
                    for (java.nio.file.Path dir : tree.filter(Files::isDirectory).toList()) {
                        if (!dir.equals(root)) registerWatch(dir, svc);
                    }
                }
            }
            MezzeWatchHandle handle = new MezzeWatchHandle(svc, root);
            openResources().watches.add(handle);
            return ok(handle);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "watch");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    private static void registerWatch(java.nio.file.Path dir, java.nio.file.WatchService svc)
            throws IOException {
        dir.register(svc,
            java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
            java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
            java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
    }

    /**
     * Drain whatever has arrived, waiting at most `timeout_ms`.
     *
     * `poll` returns a BATCH because that is what the OS delivers: one
     * WatchKey carries many events, and handing them over one at a time
     * would both lose that grouping and multiply the syscalls.
     */
    @TruffleBoundary
    public static Object poll(MezzeRecord args) {
        MezzeWatchHandle h = getWatchHandle(args, "handle");
        long timeoutMs = getLong(args, "timeout_ms");
        if (h.closed.get()) {
            return errClosedHandle(h.root.toString());
        }
        java.util.List<Object> out = new java.util.ArrayList<>();
        try {
            java.nio.file.WatchKey key = timeoutMs <= 0
                ? h.service.poll()
                : h.service.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            while (key != null) {
                java.nio.file.Path dir = (java.nio.file.Path) key.watchable();
                for (java.nio.file.WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == java.nio.file.StandardWatchEventKinds.OVERFLOW) {
                        // The OS dropped events. Say so instead of implying
                        // the batch was complete.
                        out.clear();
                        out.add(watchEvent("Overflow", null));
                        key.reset();
                        return ok(MezzeSeq.fromArray(out.toArray()));
                    }
                    String kind =
                        ev.kind() == java.nio.file.StandardWatchEventKinds.ENTRY_CREATE ? "Created"
                      : ev.kind() == java.nio.file.StandardWatchEventKinds.ENTRY_DELETE ? "Deleted"
                      : "Modified";
                    java.nio.file.Path ctx = (java.nio.file.Path) ev.context();
                    out.add(watchEvent(kind, dir.resolve(ctx).toString()));
                }
                key.reset();
                // Keep draining without waiting again: the caller asked for
                // everything available, and a second timeout would stall.
                key = h.service.poll();
            }
            return ok(MezzeSeq.fromArray(out.toArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ok(MezzeSeq.fromArray(out.toArray()));
        } catch (java.nio.file.ClosedWatchServiceException e) {
            return errClosedHandle(h.root.toString());
        }
    }

    @TruffleBoundary
    public static Object unwatch(MezzeRecord args) {
        MezzeWatchHandle h = getWatchHandle(args, "handle");
        if (!h.closed.compareAndSet(false, true)) {
            return ok(MezzeRecord.empty());
        }
        openResources().watches.remove(h);
        try {
            h.service.close();
            return ok(MezzeRecord.empty());
        } catch (IOException e) {
            return errIO(h.root.toString(), e.getMessage());
        }
    }

    private static MezzeVariant watchEvent(String kind, String path) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(kind);
        if (path == null) {
            return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        }
        MezzeRecord payload = MezzeRecord.fromFields(new String[]{"path"}, new Object[]{path});
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
    }

    // ==================== Symlinks ====================

    @TruffleBoundary
    public static Object createSymlink(MezzeRecord args) {
        String link = getString(args, "link");
        String target = getString(args, "target");
        try {
            // Paths.get(target), not toRealPath: a dangling symlink is legal
            // and is how a blue/green swap stages the next release.
            Files.createSymbolicLink(Paths.get(link), Paths.get(target));
            return ok(MezzeRecord.empty());
        } catch (FileAlreadyExistsException e) {
            return errAlreadyExists(link);
        } catch (AccessDeniedException e) {
            // Windows without developer mode lands here, not on an
            // UnsupportedOperationException.
            return errPermissionDenied(link, "create_symlink");
        } catch (UnsupportedOperationException e) {
            return errNotSupported(link, "create_symlink");
        } catch (IOException e) {
            return errIO(link, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object createHardlink(MezzeRecord args) {
        String link = getString(args, "link");
        String target = getString(args, "target");
        try {
            Files.createLink(Paths.get(link), Paths.get(target));
            return ok(MezzeRecord.empty());
        } catch (FileAlreadyExistsException e) {
            return errAlreadyExists(link);
        } catch (NoSuchFileException e) {
            // Unlike a symlink, a hard link REQUIRES an existing target —
            // there is no inode to point a second name at otherwise.
            return errNotFound(target);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(link, "create_hardlink");
        } catch (UnsupportedOperationException e) {
            return errNotSupported(link, "create_hardlink");
        } catch (IOException e) {
            return errIO(link, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object readSymlink(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            return ok(Files.readSymbolicLink(Paths.get(path)).toString());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (java.nio.file.NotLinkException e) {
            return errIO(path, "not a symbolic link");
        } catch (UnsupportedOperationException e) {
            return errNotSupported(path, "read_symlink");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object isSymlink(MezzeRecord args) {
        String path = getString(args, "path");
        // isSymbolicLink answers false for a missing path as well as for a
        // regular file. Distinguishing them needs an explicit existence
        // check, and NOFOLLOW is what makes "does the LINK exist" right.
        java.nio.file.Path p = Paths.get(path);
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return errNotFound(path);
        }
        return ok(Files.isSymbolicLink(p));
    }

    // ==================== Permissions / metadata ====================

    @TruffleBoundary
    public static Object getMode(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                Files.getPosixFilePermissions(Paths.get(path));
            return ok(modeRecord(perms));
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX volume: say so rather than inventing bits.
            return errNotSupported(path, "get_mode");
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "get_mode");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object setMode(MezzeRecord args) {
        String path = getString(args, "path");
        MezzeRecord mode = getRecord(args, "mode");
        try {
            Files.setPosixFilePermissions(Paths.get(path), permsFromRecord(mode));
            return ok(MezzeRecord.empty());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (UnsupportedOperationException e) {
            return errNotSupported(path, "set_mode");
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "set_mode");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object getOwner(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            java.nio.file.attribute.PosixFileAttributes attrs = Files.readAttributes(
                Paths.get(path), java.nio.file.attribute.PosixFileAttributes.class);
            return ok(MezzeRecord.fromFields(
                OWNER_FIELDS,
                new Object[]{attrs.owner().getName(), attrs.group().getName()}));
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (UnsupportedOperationException e) {
            return errNotSupported(path, "get_owner");
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "get_owner");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object setOwner(MezzeRecord args) {
        String path = getString(args, "path");
        MezzeRecord owner = getRecord(args, "owner");
        String user = getString(owner, "user");
        String group = getString(owner, "group");
        try {
            java.nio.file.attribute.UserPrincipalLookupService svc =
                Paths.get(path).getFileSystem().getUserPrincipalLookupService();
            java.nio.file.attribute.PosixFileAttributeView view = Files.getFileAttributeView(
                Paths.get(path), java.nio.file.attribute.PosixFileAttributeView.class);
            if (view == null) {
                return errNotSupported(path, "set_owner");
            }
            view.setOwner(svc.lookupPrincipalByName(user));
            view.setGroup(svc.lookupPrincipalByGroupName(group));
            return ok(MezzeRecord.empty());
        } catch (java.nio.file.attribute.UserPrincipalNotFoundException e) {
            return errIO(path, "no such user or group: " + e.getName());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (UnsupportedOperationException e) {
            return errNotSupported(path, "set_owner");
        } catch (AccessDeniedException e) {
            // Changing an owner needs privilege; an unprivileged process
            // gets here, which is the common case and not a bug.
            return errPermissionDenied(path, "set_owner");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object setModified(MezzeRecord args) {
        String path = getString(args, "path");
        long millis = getLong(args, "millis");
        try {
            Files.setLastModifiedTime(Paths.get(path),
                java.nio.file.attribute.FileTime.fromMillis(millis));
            return ok(MezzeRecord.empty());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "set_modified");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object realPath(MezzeRecord args) {
        String path = getString(args, "path");
        boolean follow = getBool(args, "follow_symlinks");
        try {
            java.nio.file.Path resolved = follow
                ? Paths.get(path).toRealPath()
                : Paths.get(path).toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return ok(resolved.toString());
        } catch (NoSuchFileException e) {
            return errNotFound(path);
        } catch (AccessDeniedException e) {
            return errPermissionDenied(path, "real_path");
        } catch (IOException e) {
            return errIO(path, e.getMessage());
        }
    }

    // ==================== Mode conversion ====================

    private static final String[] MODE_FIELDS = {
        "owner_read", "owner_write", "owner_exec",
        "group_read", "group_write", "group_exec",
        "other_read", "other_write", "other_exec",
    };
    private static final String[] OWNER_FIELDS = {"user", "group"};

    private static MezzeRecord modeRecord(
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms) {
        java.nio.file.attribute.PosixFilePermission[] order = {
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
        };
        Object[] vals = new Object[order.length];
        for (int i = 0; i < order.length; i++) {
            vals[i] = perms.contains(order[i]);
        }
        return MezzeRecord.fromFields(MODE_FIELDS, vals);
    }

    private static java.util.Set<java.nio.file.attribute.PosixFilePermission>
            permsFromRecord(MezzeRecord mode) {
        java.util.EnumSet<java.nio.file.attribute.PosixFilePermission> out =
            java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class);
        java.nio.file.attribute.PosixFilePermission[] order = {
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
        };
        for (int i = 0; i < MODE_FIELDS.length; i++) {
            if (getBool(mode, MODE_FIELDS[i])) out.add(order[i]);
        }
        return out;
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

    /** The backend cannot do this at all — a non-POSIX volume asked for a
     *  mode, Windows asked for an owner. Honest refusal, not a fake success. */
    private static MezzeVariant errNotSupported(String path, String operation) {
        return err("FsNotSupported",
                   new String[]{"path", "operation"},
                   new Object[]{path, operation});
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

    private static MezzeLockHandle getLockHandle(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeLockHandle h) return h;
        throw new NativeTypeMismatchException("Expected LockHandle for field '" + name + "'");
    }

    private static MezzeWatchHandle getWatchHandle(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeWatchHandle h) return h;
        throw new NativeTypeMismatchException("Expected WatchHandle for field '" + name + "'");
    }

    private static boolean getBool(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof Boolean b) return b;
        throw new NativeTypeMismatchException("Expected Bool for field '" + name + "'");
    }

    private static MezzeRecord getRecord(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeRecord rec) return rec;
        throw new NativeTypeMismatchException("Expected record for field '" + name + "'");
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
