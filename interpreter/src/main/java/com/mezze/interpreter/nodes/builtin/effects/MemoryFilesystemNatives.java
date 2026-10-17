package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.builtin.io.BytesNatives.MezzeBytes;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory FileSystem impl for tests (#82).
 *
 * Same 13-op surface as LocalDisk, backed by a ConcurrentHashMap of
 * path → { bytes, modifiedMs }. Directories tracked as a separate map.
 * Streaming handles carry their own position and refer back to the map.
 */
public final class MemoryFilesystemNatives {

    private MemoryFilesystemNatives() {}

    /** File entry: content bytes + last-modified epoch millis. */
    private static final class Entry {
        volatile byte[] bytes;
        volatile long modifiedMs;
        Entry(byte[] bytes) {
            this.bytes = bytes;
            this.modifiedMs = System.currentTimeMillis();
        }
    }

    /**
     * One in-memory filesystem PER CONTEXT.
     *
     * These were static, so every Context on the Engine shared one filesystem:
     * two programs that never heard of each other saw each other's writes, and
     * nothing ever freed the bytes. A filesystem belongs to the program that
     * writes to it.
     */
    private static final class State {
        final ConcurrentHashMap<String, Entry> files = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>> directories =
            new ConcurrentHashMap<>();
        /** Mock clock, also per context — a test that advances time must not
         *  move another program's clock. */
        final AtomicLong nowMs = new AtomicLong(0);

        State() {
            // `directories`, not `directories()`: this IS the state being built,
            // and the accessor would come straight back here for it.
            directories.put("/", ConcurrentHashMap.newKeySet());
        }
    }

    @TruffleBoundary
    private static State state() {
        return com.mezze.interpreter.runtime.MezzeContext.get(null)
            .nativeState(State.class, State::new);
    }

    @TruffleBoundary
    private static ConcurrentHashMap<String, Entry> files() {
        return state().files;
    }

    @TruffleBoundary
    private static ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>> directories() {
        return state().directories;
    }

    @TruffleBoundary
    private static AtomicLong nowMs() {
        return state().nowMs;
    }

    /**
     * Clear THIS context's in-memory filesystem. Registered as
     * {@code __native_memfs_clear} in NativeDispatch.
     *
     * Tests used to call this from their `setUp`, straight to the Java method,
     * because the filesystem was static and one test's files were every test's
     * files. The state is per context now, so a fresh program starts empty and
     * that call is both unnecessary and impossible — there is no context to
     * clear from JUnit's own thread, and asking for one there fails loudly
     * rather than silently clearing nothing.
     */
    @TruffleBoundary
    public static void clear() {
        files().clear();
        directories().clear();
        directories().put("/", ConcurrentHashMap.newKeySet());
    }

    // ==================== Path normalization ====================

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        if (i <= 0) return "/";
        return path.substring(0, i);
    }

    private static String basenameOf(String path) {
        int i = path.lastIndexOf('/');
        if (i < 0) return path;
        return path.substring(i + 1);
    }

    private static boolean isFile(String p) { return files().containsKey(p); }
    private static boolean isDir(String p)  { return directories().containsKey(p); }

    // ==================== FileHandle (in-memory) ====================

    /**
     * Streaming handle for MemoryFS. Reads/writes byte-array pages
     * held by the file's {@link Entry}. Position advances on each op.
     *
     * write handles accumulate bytes in a ByteArrayOutputStream; on
     * close(), the accumulated bytes replace or extend the file.
     * append writes seed the stream from the existing content.
     */
    public static final class MemFileHandle {
        final String path;
        final String mode;               // "Read" | "Write" | "Append"
        int pos;                          // read position
        ByteArrayOutputStream writeBuf;   // null for read handles
        final AtomicBoolean closed = new AtomicBoolean(false);

        MemFileHandle(String path, String mode) {
            this.path = path;
            this.mode = mode;
        }
    }

    // ==================== Read / Write (whole-file) ====================

    @TruffleBoundary
    public static Object readBytes(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        Entry e = files().get(path);
        if (e == null) {
            return isDir(path) ? errIO(path, "Is a directory") : errNotFound(path);
        }
        return ok(new MezzeBytes(e.bytes));
    }

    @TruffleBoundary
    public static Object writeBytes(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        MezzeBytes bytes = getBytes(args, "bytes");
        String parent = parentOf(path);
        if (!isDir(parent)) return errNotFound(parent);
        if (isDir(path)) return errIO(path, "Is a directory");
        files().put(path, new Entry(bytes.getData()));
        directories().get(parent).add(basenameOf(path));
        return ok(MezzeRecord.empty());
    }

    // ==================== Metadata ====================

    @TruffleBoundary
    public static Object stat(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (isDir(path)) {
            return ok(fileStatRecord("Directory", 0L, 0L));
        }
        Entry e = files().get(path);
        if (e == null) {
            return ok(fileStatRecord("NotFound", 0L, 0L));
        }
        return ok(fileStatRecord("File", (long) e.bytes.length, e.modifiedMs));
    }

    @TruffleBoundary
    public static Object list(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        var children = directories().get(path);
        if (children == null) {
            return isFile(path) ? errNotADirectory(path) : errNotFound(path);
        }
        List<Object> names = new ArrayList<>(children);
        return ok(MezzeSeq.fromArray(names.toArray()));
    }

    @TruffleBoundary
    public static Object listWithStat(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        var children = directories().get(path);
        if (children == null) {
            return isFile(path) ? errNotADirectory(path) : errNotFound(path);
        }
        List<Object> entries = new ArrayList<>();
        for (String name : children) {
            String childPath = path.equals("/") ? "/" + name : path + "/" + name;
            Object stat;
            if (isDir(childPath)) {
                stat = fileStatRecord("Directory", 0L, 0L);
            } else {
                Entry e = files().get(childPath);
                if (e == null) continue;  // race with delete; skip
                stat = fileStatRecord("File", (long) e.bytes.length, e.modifiedMs);
            }
            entries.add(dirEntryRecord(name, stat));
        }
        return ok(MezzeSeq.fromArray(entries.toArray()));
    }

    // ==================== Structural mutation ====================

    @TruffleBoundary
    public static Object delete(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (isDir(path)) {
            var children = directories().get(path);
            if (children != null && !children.isEmpty()) return errNotEmpty(path);
            directories().remove(path);
        } else if (isFile(path)) {
            files().remove(path);
        } else {
            return errNotFound(path);
        }
        String parent = parentOf(path);
        var parentChildren = directories().get(parent);
        if (parentChildren != null) {
            parentChildren.remove(basenameOf(path));
        }
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object createDirectory(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (isDir(path) || isFile(path)) return errAlreadyExists(path);
        String parent = parentOf(path);
        if (!isDir(parent)) return errNotFound(parent);
        directories().put(path, ConcurrentHashMap.newKeySet());
        directories().get(parent).add(basenameOf(path));
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object copyFile(MezzeRecord args) {
        String src = normalize(getString(args, "src"));
        String dest = normalize(getString(args, "dest"));
        Entry srcEntry = files().get(src);
        if (srcEntry == null) {
            return isDir(src) ? errIO(src, "Is a directory") : errNotFound(src);
        }
        if (isFile(dest) || isDir(dest)) return errAlreadyExists(dest);
        String destParent = parentOf(dest);
        if (!isDir(destParent)) return errNotFound(destParent);
        // MemoryFS is a byte-array copy — no kernel path, but still
        // faster than deriving read+write because we skip the
        // intermediate MezzeBytes allocation.
        files().put(dest, new Entry(srcEntry.bytes.clone()));
        directories().get(destParent).add(basenameOf(dest));
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object moveFile(MezzeRecord args) {
        String src = normalize(getString(args, "src"));
        String dest = normalize(getString(args, "dest"));
        Entry srcEntry = files().get(src);
        if (srcEntry == null) {
            return isDir(src) ? errIO(src, "Is a directory") : errNotFound(src);
        }
        if (isFile(dest) || isDir(dest)) return errAlreadyExists(dest);
        String destParent = parentOf(dest);
        if (!isDir(destParent)) return errNotFound(destParent);
        files().put(dest, srcEntry);
        files().remove(src);
        String srcParent = parentOf(src);
        directories().get(srcParent).remove(basenameOf(src));
        directories().get(destParent).add(basenameOf(dest));
        return ok(MezzeRecord.empty());
    }

    // ==================== Streaming ====================

    @TruffleBoundary
    public static Object open(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        String mode = getVariantTag(args, "mode");
        MemFileHandle h = new MemFileHandle(path, mode);
        switch (mode) {
            case "Read":
                if (!isFile(path)) return errNotFound(path);
                h.pos = 0;
                return ok(h);
            case "Write":
                if (isDir(path)) return errIO(path, "Is a directory");
                String parentW = parentOf(path);
                if (!isDir(parentW)) return errNotFound(parentW);
                h.writeBuf = new ByteArrayOutputStream();
                return ok(h);
            case "Append":
                if (isDir(path)) return errIO(path, "Is a directory");
                String parentA = parentOf(path);
                if (!isDir(parentA)) return errNotFound(parentA);
                h.writeBuf = new ByteArrayOutputStream();
                Entry existing = files().get(path);
                if (existing != null) {
                    try {
                        h.writeBuf.write(existing.bytes);
                    } catch (Exception e) {
                        return errIO(path, e.getMessage());
                    }
                }
                return ok(h);
            default:
                return errIO(path, "Unknown OpenMode: " + mode);
        }
    }

    @TruffleBoundary
    public static Object readChunk(MezzeRecord args) {
        MemFileHandle h = getMemHandle(args, "handle");
        long maxBytes = getLong(args, "max_bytes");
        if (h.closed.get()) return errClosedHandle(h.path);
        if (!"Read".equals(h.mode)) return errIO(h.path, "Handle not opened for reading");
        Entry e = files().get(h.path);
        if (e == null) return errNotFound(h.path);
        if (h.pos >= e.bytes.length) return ok(new MezzeBytes(new byte[0]));
        int cap = (int) Math.min(maxBytes, e.bytes.length - h.pos);
        byte[] chunk = new byte[cap];
        System.arraycopy(e.bytes, h.pos, chunk, 0, cap);
        h.pos += cap;
        return ok(new MezzeBytes(chunk));
    }

    @TruffleBoundary
    public static Object writeChunk(MezzeRecord args) {
        MemFileHandle h = getMemHandle(args, "handle");
        MezzeBytes bytes = getBytes(args, "bytes");
        if (h.closed.get()) return errClosedHandle(h.path);
        if (h.writeBuf == null) return errIO(h.path, "Handle not opened for writing");
        try {
            h.writeBuf.write(bytes.getData());
            return ok(MezzeRecord.empty());
        } catch (Exception e) {
            return errIO(h.path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object close(MezzeRecord args) {
        MemFileHandle h = getMemHandle(args, "handle");
        if (!h.closed.compareAndSet(false, true)) {
            return ok(MezzeRecord.empty());  // idempotent
        }
        if (h.writeBuf != null) {
            byte[] finalBytes = h.writeBuf.toByteArray();
            String parent = parentOf(h.path);
            if (isDir(parent)) {
                files().put(h.path, new Entry(finalBytes));
                directories().get(parent).add(basenameOf(h.path));
            }
        }
        return ok(MezzeRecord.empty());
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

    private static MezzeVariant ok(Object value) { return MezzeVariant.ok(value); }

    private static MezzeVariant errNotFound(String path) {
        return err("FsNotFound", new String[]{"path"}, new Object[]{path});
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
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object v = r.getField(i);
        if (v instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + name + "'");
    }

    private static long getLong(MezzeRecord r, String name) {
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object v = r.getField(i);
        if (v instanceof Long l) return l;
        if (v instanceof Integer x) return x;
        throw new NativeTypeMismatchException("Expected Int for field '" + name + "'");
    }

    private static MezzeBytes getBytes(MezzeRecord r, String name) {
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object v = r.getField(i);
        if (v instanceof MezzeBytes b) return b;
        throw new NativeTypeMismatchException("Expected Bytes for field '" + name + "'");
    }

    private static MemFileHandle getMemHandle(MezzeRecord r, String name) {
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object v = r.getField(i);
        if (v instanceof MemFileHandle h) return h;
        throw new NativeTypeMismatchException("Expected MemFileHandle for field '" + name + "'");
    }

    private static String getVariantTag(MezzeRecord r, String name) {
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object v = r.getField(i);
        if (v instanceof MezzeVariant vv) return vv.getConstructorName();
        throw new NativeTypeMismatchException("Expected variant for field '" + name + "'");
    }
}
