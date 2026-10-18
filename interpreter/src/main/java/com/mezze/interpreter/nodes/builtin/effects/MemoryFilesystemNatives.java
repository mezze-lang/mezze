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

    /**
     * File entry: content bytes + last-modified epoch millis, plus the
     * POSIX metadata `FilePermissions` reads and writes and the symlink
     * target `FileSymlinks` reads.
     *
     * The metadata is RECORDED, not ENFORCED: `set_mode` stores what you
     * asked for and nothing here checks it before a read. Enforcement is a
     * real kernel's job; this backend exists so a test can assert that the
     * code set what it meant to set.
     *
     * `symlinkTarget` non-null makes this entry a link rather than a file.
     * Its `bytes` stay empty — reading THROUGH a memfs symlink is not
     * implemented, and `read_bytes` on a link reports FsNotSupported rather
     * than silently returning nothing.
     */
    private static final class Entry {
        volatile byte[] bytes;
        volatile long modifiedMs;
        /** Nine permission bits, in MODE_FIELDS order. Defaults to rw-r--r--. */
        volatile boolean[] mode = {true, true, false, true, false, false, true, false, false};
        volatile String ownerUser = "mezze";
        volatile String ownerGroup = "mezze";
        /** Non-null iff this entry is a symlink; the raw, unresolved target. */
        volatile String symlinkTarget;

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

        /**
         * `FileLock` state: path -> who holds it. Per context like the rest,
         * so one program's lockfile is invisible to another — which is what
         * the real backend does across processes.
         *
         * A counter rather than a boolean: a shared lock may be held many
         * times at once, and unlocking one holder must not free the others.
         * `exclusive` and a non-zero `shared` are mutually exclusive.
         */
        final ConcurrentHashMap<String, LockState> locks = new ConcurrentHashMap<>();

        /**
         * `FileWatch` state: watch id -> its pending events.
         *
         * This is the one effect whose state the CORE has to know about: a
         * write through FileSystem must enqueue an event for a watcher, so
         * the mutating ops call {@link #notifyWatchers}. The alternative —
         * pollers diffing `modifiedMs` — needs no coupling but cannot tell
         * Created from Deleted and can never report Overflow, so it could
         * not honestly implement WatchEvent.
         */
        final ConcurrentHashMap<Long, Watch> watches = new ConcurrentHashMap<>();
        final AtomicLong nextWatchId = new AtomicLong(1);

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
    private static ConcurrentHashMap<Long, Watch> watches() {
        return state().watches;
    }

    @TruffleBoundary
    private static ConcurrentHashMap<String, LockState> locks() {
        return state().locks;
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
        boolean existed = isFile(path);
        files().put(path, new Entry(bytes.getData()));
        directories().get(parent).add(basenameOf(path));
        notifyWatchers(path, existed ? "Modified" : "Created");
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
            notifyWatchers(path, "Deleted");
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
        notifyWatchers(path, "Created");
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
        notifyWatchers(dest, "Created");
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
        notifyWatchers(dest, "Created");
        notifyWatchers(src, "Deleted");
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

    /**
     * `FileDurability.fsync` — memfs has no storage device, so there is
     * nothing to force. It is NOT a no-op all the same.
     *
     * A write handle buffers into `writeBuf` and only publishes to `files()`
     * on close, so a handle that had been fsynced but not closed would read
     * back as absent here while LocalDisk would already show the bytes. That
     * divergence is the exact failure the effect split exists to prevent — a
     * backend quietly meaning something different by the same op — so fsync
     * publishes the buffer. The buffer is kept, so later writes still append
     * and close republishes the whole thing.
     */
    @TruffleBoundary
    public static Object fsync(MezzeRecord args) {
        MemFileHandle h = getMemHandle(args, "handle");
        if (h.closed.get()) {
            return errClosedHandle(h.path);
        }
        if (h.writeBuf != null) {
            String parent = parentOf(h.path);
            if (!isDir(parent)) {
                return errNotFound(parent);
            }
            boolean existedBefore = isFile(h.path);
            files().put(h.path, new Entry(h.writeBuf.toByteArray()));
            directories().get(parent).add(basenameOf(h.path));
            notifyWatchers(h.path, existedBefore ? "Modified" : "Created");
        }
        return ok(MezzeRecord.empty());
    }

    /**
     * `FileDurability.fsync_dir` — the directory entries are the map, and the
     * map is already the truth. Nothing to force, but the path must exist:
     * reporting success for a directory that is not there would hide the
     * caller's bug.
     */
    @TruffleBoundary
    public static Object fsyncDir(MezzeRecord args) {
        String path = getString(args, "path");
        if (!isDir(path)) {
            return isFile(path) ? errNotADirectory(path) : errNotFound(path);
        }
        return ok(MezzeRecord.empty());
    }

    // ==================== Watching ====================

    /**
     * One registered watch and its pending events.
     *
     * Bounded, like inotify's queue: past {@link #CAP} the events are
     * dropped and a single `Overflow` is delivered instead. Reporting
     * Overflow is the honest answer — a watcher that silently lost events
     * would have the caller believe it saw everything, and the usual
     * recovery (rescan the tree) is only available if it is told.
     */
    static final class Watch {
        static final int CAP = 1024;
        final String root;
        final boolean recursive;
        final java.util.concurrent.ConcurrentLinkedQueue<String[]> events =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile boolean overflowed;

        Watch(String root, boolean recursive) {
            this.root = root;
            this.recursive = recursive;
        }
    }

    /** Opaque watch handle. Carries only the id; the state lives per context. */
    public static final class MemWatchHandle {
        final long id;
        MemWatchHandle(long id) { this.id = id; }
    }

    /**
     * Called by every mutating op. This is the coupling the design accepted:
     * the core must tell the watchers, because the alternative (pollers
     * diffing modifiedMs) cannot distinguish Created from Deleted.
     *
     * A non-recursive watch sees its DIRECT children only, which is what
     * inotify without IN_ONLYDIR recursion gives you.
     */
    private static void notifyWatchers(String path, String kind) {
        ConcurrentHashMap<Long, Watch> ws = watches();
        if (ws.isEmpty()) return;  // the overwhelmingly common case
        for (Watch w : ws.values()) {
            boolean under = w.recursive
                ? path.equals(w.root) || path.startsWith(w.root.endsWith("/") ? w.root : w.root + "/")
                : w.root.equals(parentOf(path));
            if (!under) continue;
            if (w.events.size() >= Watch.CAP) {
                w.overflowed = true;
                continue;
            }
            w.events.add(new String[]{kind, path});
        }
    }

    @TruffleBoundary
    public static Object watch(MezzeRecord args) {
        String path = getString(args, "path");
        boolean recursive = getBool(args, "recursive");
        if (!isDir(path)) {
            return isFile(path) ? errNotADirectory(path) : errNotFound(path);
        }
        long id = state().nextWatchId.getAndIncrement();
        watches().put(id, new Watch(path, recursive));
        return ok(new MemWatchHandle(id));
    }

    /**
     * Drain the queue. `timeout_ms` is accepted and ignored: there is no
     * other thread to wait for in a single context, so blocking could only
     * delay the caller for nothing. An empty Seq means "nothing happened",
     * which is a normal answer for a poll.
     */
    @TruffleBoundary
    public static Object poll(MezzeRecord args) {
        MemWatchHandle h = getMemWatchHandle(args, "handle");
        Watch w = watches().get(h.id);
        if (w == null) return errIO("<watch>", "watch handle is not registered");
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (w.overflowed) {
            w.overflowed = false;
            w.events.clear();
            out.add(watchEvent("Overflow", null));
            return ok(MezzeSeq.fromArray(out.toArray()));
        }
        String[] ev;
        while ((ev = w.events.poll()) != null) {
            out.add(watchEvent(ev[0], ev[1]));
        }
        return ok(MezzeSeq.fromArray(out.toArray()));
    }

    @TruffleBoundary
    public static Object unwatch(MezzeRecord args) {
        MemWatchHandle h = getMemWatchHandle(args, "handle");
        watches().remove(h.id);
        return ok(MezzeRecord.empty());
    }

    private static MezzeVariant watchEvent(String kind, String path) {
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(kind);
        if (path == null) {
            // Overflow carries no path — there is no single path it is about.
            return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag);
        }
        MezzeRecord payload = MezzeRecord.fromFields(new String[]{"path"}, new Object[]{path});
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
    }

    // ==================== Symlinks ====================

    private static final String[] MODE_FIELDS = {
        "owner_read", "owner_write", "owner_exec",
        "group_read", "group_write", "group_exec",
        "other_read", "other_write", "other_exec",
    };
    private static final String[] OWNER_FIELDS = {"user", "group"};

    @TruffleBoundary
    public static Object createSymlink(MezzeRecord args) {
        String link = getString(args, "link");
        String target = getString(args, "target");
        if (isFile(link) || isDir(link)) return errAlreadyExists(link);
        String parent = parentOf(link);
        if (!isDir(parent)) return errNotFound(parent);
        // The target is NOT required to exist: a dangling symlink is legal,
        // and staging one is how a blue/green swap works.
        Entry e = new Entry(new byte[0]);
        e.symlinkTarget = target;
        files().put(link, e);
        directories().get(parent).add(basenameOf(link));
        notifyWatchers(link, "Created");
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object createHardlink(MezzeRecord args) {
        String link = getString(args, "link");
        String target = getString(args, "target");
        if (isFile(link) || isDir(link)) return errAlreadyExists(link);
        Entry src = files().get(target);
        // Unlike a symlink, a hard link needs a real target: there is no
        // inode to give a second name to otherwise.
        if (src == null) return errNotFound(target);
        if (src.symlinkTarget != null) return errNotSupported(target, "create_hardlink to a symlink");
        String parent = parentOf(link);
        if (!isDir(parent)) return errNotFound(parent);
        // The SAME Entry under both names — that is what a hard link is, and
        // it means a later write through one name is visible through the
        // other, as on a real filesystem.
        files().put(link, src);
        directories().get(parent).add(basenameOf(link));
        notifyWatchers(link, "Created");
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object readSymlink(MezzeRecord args) {
        String path = getString(args, "path");
        Entry e = files().get(path);
        if (e == null) return errNotFound(path);
        if (e.symlinkTarget == null) return errIO(path, "not a symbolic link");
        return ok(e.symlinkTarget);
    }

    @TruffleBoundary
    public static Object isSymlink(MezzeRecord args) {
        String path = getString(args, "path");
        Entry e = files().get(path);
        if (e == null) {
            // A directory is not a symlink; a missing path is an error, which
            // is what distinguishes "no" from "not there".
            return isDir(path) ? ok(false) : errNotFound(path);
        }
        return ok(e.symlinkTarget != null);
    }

    // ==================== Permissions / metadata ====================

    @TruffleBoundary
    public static Object getMode(MezzeRecord args) {
        String path = getString(args, "path");
        Entry e = files().get(path);
        if (e == null) return isDir(path) ? ok(defaultDirMode()) : errNotFound(path);
        Object[] vals = new Object[9];
        boolean[] m = e.mode;
        for (int i = 0; i < 9; i++) vals[i] = m[i];
        return ok(MezzeRecord.fromFields(MODE_FIELDS, vals));
    }

    @TruffleBoundary
    public static Object setMode(MezzeRecord args) {
        String path = getString(args, "path");
        MezzeRecord mode = getRecord(args, "mode");
        Entry e = files().get(path);
        if (e == null) return errNotFound(path);
        boolean[] m = new boolean[9];
        for (int i = 0; i < 9; i++) m[i] = getBool(mode, MODE_FIELDS[i]);
        e.mode = m;
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object getOwner(MezzeRecord args) {
        String path = getString(args, "path");
        Entry e = files().get(path);
        if (e == null) return errNotFound(path);
        return ok(MezzeRecord.fromFields(OWNER_FIELDS,
            new Object[]{e.ownerUser, e.ownerGroup}));
    }

    @TruffleBoundary
    public static Object setOwner(MezzeRecord args) {
        String path = getString(args, "path");
        MezzeRecord owner = getRecord(args, "owner");
        Entry e = files().get(path);
        if (e == null) return errNotFound(path);
        e.ownerUser = getString(owner, "user");
        e.ownerGroup = getString(owner, "group");
        return ok(MezzeRecord.empty());
    }

    @TruffleBoundary
    public static Object setModified(MezzeRecord args) {
        String path = getString(args, "path");
        long millis = getLong(args, "millis");
        Entry e = files().get(path);
        if (e == null) return errNotFound(path);
        e.modifiedMs = millis;
        return ok(MezzeRecord.empty());
    }

    /**
     * memfs paths are already canonical — there is no `..`, no `.` and no
     * mount table — so resolution is only about symlinks. One hop is
     * followed repeatedly with a bound, because a symlink cycle is a thing
     * a test should be able to create without hanging the suite.
     */
    @TruffleBoundary
    public static Object realPath(MezzeRecord args) {
        String path = getString(args, "path");
        boolean follow = getBool(args, "follow_symlinks");
        String cur = path;
        if (follow) {
            for (int hops = 0; hops < 40; hops++) {
                Entry e = files().get(cur);
                if (e == null || e.symlinkTarget == null) break;
                cur = e.symlinkTarget;
                if (hops == 39) return errIO(path, "too many symbolic links");
            }
        }
        if (!isFile(cur) && !isDir(cur)) return errNotFound(cur);
        return ok(cur);
    }

    private static MezzeRecord defaultDirMode() {
        Object[] vals = {true, true, true, true, false, true, true, false, true};
        return MezzeRecord.fromFields(MODE_FIELDS, vals);
    }

    // ==================== Locking ====================

    /** Holders of one path's lock. Guarded by synchronizing on the instance. */
    static final class LockState {
        boolean exclusive;
        int shared;
    }

    /**
     * An acquired memfs lock. Carries what `unlock` needs to undo exactly
     * this acquisition — releasing a shared lock must decrement, not clear.
     */
    public static final class MemLockHandle {
        final String path;
        final boolean shared;
        final AtomicBoolean released = new AtomicBoolean(false);

        MemLockHandle(String path, boolean shared) {
            this.path = path;
            this.shared = shared;
        }
    }

    /**
     * `FileLock.lock` — contention is REAL here, not simulated.
     *
     * memfs could have reported success unconditionally, since one program
     * rarely contends with itself. It does not, because then a test could
     * never show that a lock excludes anyone, and "locking works" would be
     * untestable without a second process. Shared locks stack; an exclusive
     * lock excludes everything; `blocking` is rejected rather than parked,
     * since a blocking wait inside a single-context memfs can only deadlock —
     * there is no other holder to release it.
     */
    @TruffleBoundary
    public static Object lock(MezzeRecord args) {
        String path = getString(args, "path");
        String kind = getVariantTag(args, "kind");
        boolean shared = "Shared".equals(kind);
        boolean blocking = getBool(args, "blocking");
        LockState st = locks().computeIfAbsent(path, p -> new LockState());
        synchronized (st) {
            boolean free = shared ? !st.exclusive : (!st.exclusive && st.shared == 0);
            if (!free) {
                if (blocking) {
                    // Parking here would hang forever: the only holder is this
                    // same context, and it is the thread asking.
                    return errIO(path, "lock is held and blocking would deadlock in MemoryFS");
                }
                return errPermissionDenied(path, "lock (already held)");
            }
            if (shared) st.shared++; else st.exclusive = true;
        }
        return ok(new MemLockHandle(path, shared));
    }

    /** `FileLock.unlock` — idempotent; decrements a shared hold. */
    @TruffleBoundary
    public static Object unlock(MezzeRecord args) {
        MemLockHandle h = getMemLockHandle(args, "handle");
        if (!h.released.compareAndSet(false, true)) {
            return ok(MezzeRecord.empty());
        }
        LockState st = locks().get(h.path);
        if (st != null) {
            synchronized (st) {
                if (h.shared) {
                    if (st.shared > 0) st.shared--;
                } else {
                    st.exclusive = false;
                }
            }
        }
        return ok(MezzeRecord.empty());
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

    /** Lock contention surfaces as PermissionDenied — see the note on
     *  `lock` for why FsError does not grow an FsLockHeld variant. */
    private static MezzeVariant errPermissionDenied(String path, String operation) {
        return err("FsPermissionDenied",
                   new String[]{"path", "operation"},
                   new Object[]{path, operation});
    }

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

    private static MemLockHandle getMemLockHandle(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MemLockHandle h) return h;
        throw new NativeTypeMismatchException("Expected LockHandle for field '" + name + "'");
    }

    private static MemWatchHandle getMemWatchHandle(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MemWatchHandle h) return h;
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

    private static String getVariantTag(MezzeRecord r, String name) {
        int i = r.getFieldIndex(name);
        if (i < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object v = r.getField(i);
        if (v instanceof MezzeVariant vv) return vv.getConstructorName();
        throw new NativeTypeMismatchException("Expected variant for field '" + name + "'");
    }
}
