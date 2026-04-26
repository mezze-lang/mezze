package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Native implementations for Filesystem effect operations.
 *
 * All operations return Result types with proper error variants:
 * - FileNotFound { path }
 * - PermissionDenied { path, operation }
 * - AlreadyExists { path }
 * - DirectoryNotEmpty { path }
 * - IOError { path, message }
 */
public final class FilesystemNatives {

    private FilesystemNatives() {}

    // ============== Read/Write Operations ==============

    @TruffleBoundary
    public static Object readFile(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            String content = Files.readString(Path.of(path));
            return createOk(content);
        } catch (java.nio.file.NoSuchFileException e) {
            return fileNotFound(path);
        } catch (AccessDeniedException e) {
            return permissionDenied(path, "read");
        } catch (IOException e) {
            return ioError(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object writeFile(MezzeRecord args) {
        String path = getString(args, "path");
        String content = getString(args, "content");
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                return fileNotFound(path);
            }
            Files.writeString(Path.of(path), content);
            return createOk(MezzeUnit.INSTANCE);
        } catch (java.nio.file.NoSuchFileException e) {
            return fileNotFound(path);
        } catch (AccessDeniedException e) {
            return permissionDenied(path, "write");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("No space left")) {
                return diskFull(path, content.length());
            }
            return ioError(path, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object appendFile(MezzeRecord args) {
        String path = getString(args, "path");
        String content = getString(args, "content");
        try {
            File file = new File(path);
            if (!file.exists()) {
                return fileNotFound(path);
            }
            Files.writeString(Path.of(path), content, java.nio.file.StandardOpenOption.APPEND);
            return createOk(MezzeUnit.INSTANCE);
        } catch (AccessDeniedException e) {
            return permissionDenied(path, "append");
        } catch (IOException e) {
            return ioError(path, e.getMessage());
        }
    }

    // ============== File Query Operations ==============

    @TruffleBoundary
    public static Object exists(MezzeRecord args) {
        String path = getString(args, "path");
        return createOk(new File(path).exists());
    }

    @TruffleBoundary
    public static Object fileSize(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (!file.exists()) {
            return fileNotFound(path);
        }
        if (!file.canRead()) {
            return permissionDenied(path, "file_size");
        }
        return createOk(file.length());
    }

    @TruffleBoundary
    public static Object isDirectory(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (!file.exists()) {
            return fileNotFound(path);
        }
        return createOk(file.isDirectory());
    }

    @TruffleBoundary
    public static Object isFile(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (!file.exists()) {
            return fileNotFound(path);
        }
        return createOk(file.isFile());
    }

    // ============== Directory Operations ==============

    @TruffleBoundary
    public static Object createDirectory(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (file.exists()) {
            return alreadyExists(path);
        }
        if (!file.mkdirs()) {
            return permissionDenied(path, "create_directory");
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object createDirectories(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (file.exists() && file.isDirectory()) {
            return createOk(MezzeUnit.INSTANCE);  // Already exists, ok
        }
        if (file.exists()) {
            return alreadyExists(path);  // Exists but not a directory
        }
        if (!file.mkdirs()) {
            return permissionDenied(path, "create_directories");
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object listDirectory(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (!file.exists()) {
            return fileNotFound(path);
        }
        if (!file.isDirectory()) {
            return ioError(path, "Not a directory");
        }
        if (!file.canRead()) {
            return permissionDenied(path, "list_directory");
        }
        String[] entries = file.list();
        if (entries == null) {
            entries = new String[0];
        }
        return createOk(MezzeList.of(Arrays.stream(entries).toArray()));
    }

    // ============== Delete Operations ==============

    @TruffleBoundary
    public static Object delete(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (!file.exists()) {
            return fileNotFound(path);
        }
        if (file.isDirectory() && file.list() != null && file.list().length > 0) {
            return directoryNotEmpty(path);
        }
        if (!file.delete()) {
            return permissionDenied(path, "delete");
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    @TruffleBoundary
    public static Object deleteRecursive(MezzeRecord args) {
        String path = getString(args, "path");
        File file = new File(path);
        if (!file.exists()) {
            return fileNotFound(path);
        }
        if (!deleteRecursively(file)) {
            return permissionDenied(path, "delete_recursive");
        }
        return createOk(MezzeUnit.INSTANCE);
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    // ============== Copy/Move Operations ==============

    @TruffleBoundary
    public static Object copyFile(MezzeRecord args) {
        String src = getString(args, "src");
        String dest = getString(args, "dest");
        try {
            File srcFile = new File(src);
            File destFile = new File(dest);
            if (!srcFile.exists()) {
                return fileNotFound(src);
            }
            if (destFile.exists()) {
                return alreadyExists(dest);
            }
            Files.copy(srcFile.toPath(), destFile.toPath());
            return createOk(MezzeUnit.INSTANCE);
        } catch (AccessDeniedException e) {
            return permissionDenied(src, "copy");
        } catch (IOException e) {
            return ioError(src, e.getMessage());
        }
    }

    @TruffleBoundary
    public static Object moveFile(MezzeRecord args) {
        String src = getString(args, "src");
        String dest = getString(args, "dest");
        try {
            File srcFile = new File(src);
            File destFile = new File(dest);
            if (!srcFile.exists()) {
                return fileNotFound(src);
            }
            if (destFile.exists()) {
                return alreadyExists(dest);
            }
            if (!srcFile.renameTo(destFile)) {
                return ioError(src, "Failed to move file");
            }
            return createOk(MezzeUnit.INSTANCE);
        } catch (Exception e) {
            return ioError(src, e.getMessage());
        }
    }

    // ============== Result/Error Helpers ==============

    private static MezzeVariant createOk(Object value) {
        return MezzeVariant.ok(value);
    }

    private static MezzeVariant fileNotFound(String path) {
        return createErr("FileNotFound", new String[]{"path"}, new Object[]{path});
    }

    private static MezzeVariant permissionDenied(String path, String operation) {
        return createErr("PermissionDenied",
            new String[]{"path", "operation"},
            new Object[]{path, operation});
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

    private static MezzeVariant diskFull(String path, long bytesNeeded) {
        return createErr("DiskFull",
            new String[]{"path", "bytes_needed"},
            new Object[]{path, bytesNeeded});
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
