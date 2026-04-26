package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native implementations for MemoryFS effect operations.
 *
 * Provides an in-memory filesystem for testing, thread-safe via ConcurrentHashMap.
 * All operations return Result types with proper error variants.
 */
public final class MemoryFilesystemNatives {

    private MemoryFilesystemNatives() {}

    // In-memory file storage
    private static final ConcurrentHashMap<String, String> files = new ConcurrentHashMap<>();
    // Directory entries (directory path -> set of child names)
    private static final ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>> directories = new ConcurrentHashMap<>();

    static {
        // Initialize root directory
        directories.put("/", ConcurrentHashMap.newKeySet());
    }

    /**
     * Clear the in-memory filesystem (for test isolation)
     */
    @TruffleBoundary
    public static void clear() {
        files.clear();
        directories.clear();
        directories.put("/", ConcurrentHashMap.newKeySet());
    }

    // ============== Path Utilities ==============

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        // Ensure leading slash
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // Remove trailing slash (unless root)
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String getParent(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "/";
        return path.substring(0, lastSlash);
    }

    private static String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return path;
        return path.substring(lastSlash + 1);
    }

    private static boolean pathExists(String path) {
        return files.containsKey(path) || directories.containsKey(path);
    }

    private static boolean isDirectory(String path) {
        return directories.containsKey(path);
    }

    private static boolean isFile(String path) {
        return files.containsKey(path);
    }

    // ============== Read/Write Operations ==============

    @TruffleBoundary
    public static Object readFile(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (!files.containsKey(path)) {
            return fileNotFound(path);
        }
        return createOk(files.get(path));
    }

    @TruffleBoundary
    public static Object writeFile(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        String content = getString(args, "content");
        String parent = getParent(path);

        if (!isDirectory(parent)) {
            return fileNotFound(path);
        }
        if (isDirectory(path)) {
            return ioError(path, "Is a directory");
        }

        files.put(path, content);
        // Add to parent directory
        var parentChildren = directories.get(parent);
        if (parentChildren != null) {
            parentChildren.add(getFileName(path));
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object appendFile(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        String content = getString(args, "content");

        if (!files.containsKey(path)) {
            return fileNotFound(path);
        }
        if (isDirectory(path)) {
            return ioError(path, "Is a directory");
        }

        files.compute(path, (k, existing) -> (existing == null ? "" : existing) + content);
        return createOk(MezzeUnit.INSTANCE);
    }

    // ============== File Query Operations ==============

    @TruffleBoundary
    public static Object exists(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        return createOk(pathExists(path));
    }

    @TruffleBoundary
    public static Object fileSize(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (!files.containsKey(path)) {
            if (isDirectory(path)) {
                return ioError(path, "Is a directory");
            }
            return fileNotFound(path);
        }
        String content = files.get(path);
        return createOk((long) (content != null ? content.length() : 0));
    }

    @TruffleBoundary
    public static Object isDirectoryOp(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (!pathExists(path)) {
            return fileNotFound(path);
        }
        return createOk(isDirectory(path));
    }

    @TruffleBoundary
    public static Object isFileOp(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        if (!pathExists(path)) {
            return fileNotFound(path);
        }
        return createOk(isFile(path));
    }

    // ============== Directory Operations ==============

    @TruffleBoundary
    public static Object createDirectory(MezzeRecord args) {
        String path = normalize(getString(args, "path"));
        String parent = getParent(path);

        if (pathExists(path)) {
            return alreadyExists(path);
        }
        if (!isDirectory(parent)) {
            return fileNotFound(parent);
        }

        directories.put(path, ConcurrentHashMap.newKeySet());
        var parentChildren = directories.get(parent);
        if (parentChildren != null) {
            parentChildren.add(getFileName(path));
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object createDirectories(MezzeRecord args) {
        String path = normalize(getString(args, "path"));

        if (isDirectory(path)) {
            return createOk(MezzeUnit.INSTANCE); // Already exists
        }
        if (isFile(path)) {
            return alreadyExists(path);
        }

        // Build path components and create from root
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            current.append("/").append(part);
            String currentPath = current.toString();
            if (!isDirectory(currentPath)) {
                if (isFile(currentPath)) {
                    return alreadyExists(currentPath);
                }
                directories.put(currentPath, ConcurrentHashMap.newKeySet());
                String parentPath = getParent(currentPath);
                var parentChildren = directories.get(parentPath);
                if (parentChildren != null) {
                    parentChildren.add(part);
                }
            }
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object listDirectory(MezzeRecord args) {
        String path = normalize(getString(args, "path"));

        if (!pathExists(path)) {
            return fileNotFound(path);
        }
        if (!isDirectory(path)) {
            return ioError(path, "Not a directory");
        }

        var children = directories.get(path);
        List<Object> entries = new ArrayList<>();
        if (children != null) {
            entries.addAll(children);
        }
        return createOk(MezzeList.of(entries.toArray()));
    }

    // ============== Delete Operations ==============

    @TruffleBoundary
    public static Object delete(MezzeRecord args) {
        String path = normalize(getString(args, "path"));

        if (!pathExists(path)) {
            return fileNotFound(path);
        }

        if (isDirectory(path)) {
            var children = directories.get(path);
            if (children != null && !children.isEmpty()) {
                return directoryNotEmpty(path);
            }
            directories.remove(path);
        } else {
            files.remove(path);
        }

        // Remove from parent
        String parent = getParent(path);
        var parentChildren = directories.get(parent);
        if (parentChildren != null) {
            parentChildren.remove(getFileName(path));
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object deleteRecursive(MezzeRecord args) {
        String path = normalize(getString(args, "path"));

        if (!pathExists(path)) {
            return fileNotFound(path);
        }

        deleteRecursively(path);
        return createOk(MezzeUnit.INSTANCE);
    }

    private static void deleteRecursively(String path) {
        if (isDirectory(path)) {
            var children = directories.get(path);
            if (children != null) {
                for (String child : new ArrayList<>(children)) {
                    deleteRecursively(path + "/" + child);
                }
            }
            directories.remove(path);
        } else {
            files.remove(path);
        }

        // Remove from parent
        String parent = getParent(path);
        var parentChildren = directories.get(parent);
        if (parentChildren != null) {
            parentChildren.remove(getFileName(path));
        }
    }

    // ============== Copy/Move Operations ==============

    @TruffleBoundary
    public static Object copyFile(MezzeRecord args) {
        String src = normalize(getString(args, "src"));
        String dest = normalize(getString(args, "dest"));

        if (!files.containsKey(src)) {
            if (isDirectory(src)) {
                return ioError(src, "Is a directory");
            }
            return fileNotFound(src);
        }
        if (pathExists(dest)) {
            return alreadyExists(dest);
        }

        String destParent = getParent(dest);
        if (!isDirectory(destParent)) {
            return fileNotFound(destParent);
        }

        files.put(dest, files.get(src));
        var parentChildren = directories.get(destParent);
        if (parentChildren != null) {
            parentChildren.add(getFileName(dest));
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object moveFile(MezzeRecord args) {
        String src = normalize(getString(args, "src"));
        String dest = normalize(getString(args, "dest"));

        if (!files.containsKey(src)) {
            if (isDirectory(src)) {
                return ioError(src, "Is a directory");
            }
            return fileNotFound(src);
        }
        if (pathExists(dest)) {
            return alreadyExists(dest);
        }

        String destParent = getParent(dest);
        if (!isDirectory(destParent)) {
            return fileNotFound(destParent);
        }

        // Copy content
        String content = files.remove(src);
        files.put(dest, content);

        // Update parent directories
        String srcParent = getParent(src);
        var srcParentChildren = directories.get(srcParent);
        if (srcParentChildren != null) {
            srcParentChildren.remove(getFileName(src));
        }
        var destParentChildren = directories.get(destParent);
        if (destParentChildren != null) {
            destParentChildren.add(getFileName(dest));
        }

        return createOk(MezzeUnit.INSTANCE);
    }

    // ============== Result/Error Helpers ==============

    private static MezzeVariant createOk(Object value) {
        return MezzeVariant.ok(value);
    }

    private static MezzeVariant fileNotFound(String path) {
        return createErr("FileNotFound", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant alreadyExists(String path) {
        return createErr("AlreadyExists", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant directoryNotEmpty(String path) {
        return createErr("DirectoryNotEmpty", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant ioError(String path, String message) {
        return createErr("IOError",
            new String[]{"path", "message"},
            new Object[]{path, message != null ? message : "Unknown error"});
    }

    private static MezzeVariant createErr(String constructor, String[] fields, Object[] values) {
        MezzeRecord payload = MezzeRecord.fromFields(fields, values);
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(constructor);
        MezzeVariant errorVariant = MezzeVariant.singlePayload(
            MezzeVariant.TagRegistry.getGlobal(), tag, payload);
        return MezzeVariant.err(errorVariant);
    }

    // ============== Field Helpers ==============

    private static String getString(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) {
            throw new RuntimeException("Missing field '" + name + "'");
        }
        Object val = r.getField(index);
        if (val instanceof String s) return s;
        throw new RuntimeException("Expected String for field '" + name + "'");
    }
}
