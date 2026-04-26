package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Map;

/**
 * Native implementations for Environment effect operations.
 *
 * Operations:
 * - env_get: Get environment variable (returns Option<String>)
 * - env_set: Set environment variable (uses System.setProperty as workaround)
 * - env_get_all: Get all environment variables
 * - current_dir: Get current working directory
 * - change_dir: Change working directory (uses System.setProperty as workaround)
 * - os_name: Get OS name
 * - os_version: Get OS version
 * - arch: Get system architecture
 * - process_id: Get current process ID
 * - process_args: Get process arguments
 * - exit: Exit the process
 */
public final class EnvironmentNatives {

    private EnvironmentNatives() {}

    @TruffleBoundary
    public static Object envGet(MezzeRecord args) {
        String name = getString(args, "name");
        String value = System.getenv(name);
        if (value != null) {
            return MezzeVariant.some(value);
        }
        return MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object envSet(MezzeRecord args) {
        String name = getString(args, "name");
        String value = getString(args, "value");
        // Note: Java doesn't allow setting environment variables at runtime
        // This uses System.setProperty as a workaround
        System.setProperty(name, value);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object envGetAll(MezzeRecord args) {
        Map<String, String> env = System.getenv();
        Object[] entries = new Object[env.size()];
        int i = 0;
        for (var entry : env.entrySet()) {
            // Create tuple as a record with key/value fields
            entries[i++] = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
        }
        return MezzeSeq.fromArray(entries);
    }

    @TruffleBoundary
    public static String currentDir(MezzeRecord args) {
        return System.getProperty("user.dir");
    }

    @TruffleBoundary
    public static Object changeDir(MezzeRecord args) {
        String path = getString(args, "path");
        // Note: Java doesn't allow changing working directory at runtime
        // This uses System.setProperty as a workaround for testing
        System.setProperty("user.dir", path);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static String osName(MezzeRecord args) {
        return System.getProperty("os.name");
    }

    @TruffleBoundary
    public static String osVersion(MezzeRecord args) {
        return System.getProperty("os.version");
    }

    @TruffleBoundary
    public static String arch(MezzeRecord args) {
        return System.getProperty("os.arch");
    }

    @TruffleBoundary
    public static long processId(MezzeRecord args) {
        return ProcessHandle.current().pid();
    }

    @TruffleBoundary
    public static Object processArgs(MezzeRecord args) {
        String[] procArgs = ProcessHandle.current().info().arguments().orElse(new String[0]);
        return MezzeSeq.fromArray(procArgs);
    }

    @TruffleBoundary
    public static Object exit(MezzeRecord args) {
        long code = getLong(args, "code");
        System.exit((int) code);
        return MezzeUnit.INSTANCE;  // Never reached
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

    private static long getLong(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) {
            throw new RuntimeException("Missing field '" + name + "'");
        }
        Object val = r.getField(index);
        if (val instanceof Long l) return l;
        throw new RuntimeException("Expected Long for field '" + name + "'");
    }
}
