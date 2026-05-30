package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.runtime.MezzeExitException;
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

    /**
     * MockEnv.get: linear search over the user-supplied vars list for the
     * key. Returns Some value on hit, None on miss. O(n) — adequate for
     * tests, which is the only caller.
     */
    @TruffleBoundary
    public static Object envGetMock(MezzeRecord args) {
        String name = getString(args, "name");
        Object envVars = args.getField(args.getFieldIndex("env_vars"));
        if (envVars instanceof com.mezze.datastructures.seq.MezzeSeq seq) {
            int n = seq.size();
            for (int i = 0; i < n; i++) {
                Object entry = seq.get(i);
                if (entry instanceof MezzeRecord rec) {
                    int keyIdx = rec.getFieldIndex("key");
                    int valIdx = rec.getFieldIndex("value");
                    if (keyIdx >= 0 && valIdx >= 0) {
                        Object key = rec.getField(keyIdx);
                        if (name.equals(key)) {
                            Object val = rec.getField(valIdx);
                            return val == null ? MezzeVariant.none() : MezzeVariant.some(val);
                        }
                    }
                }
            }
        }
        return MezzeVariant.none();
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

    /**
     * Per-run argv override. Set by RuntimeServer at the start of each
     * LOAD when the parent ships argv in the LOAD frame. Null = no
     * override; fall back to {@link ProcessHandle}'s view of the JVM
     * launcher's argv (correct for in-process / built user binaries).
     */
    private static volatile String[] wireArgvOverride = null;

    /** Runtime hook (RuntimeServer): set the per-run argv supplied by the parent. */
    public static void setWireArgv(String[] argv) {
        wireArgvOverride = argv;
    }

    @TruffleBoundary
    public static Object processArgs(MezzeRecord args) {
        String[] override = wireArgvOverride;
        String[] procArgs = (override != null)
            ? override
            : ProcessHandle.current().info().arguments().orElse(new String[0]);
        return MezzeSeq.fromArray(procArgs);
    }

    @TruffleBoundary
    public static Object exit(MezzeRecord args) {
        // Throw a Truffle exit exception instead of calling System.exit
        // directly. Different hosts unwind differently:
        //   - Standalone app jar / native binary: MezzeAppMain catches at
        //     top level and exits with the carried code.
        //   - Wire-protocol RuntimeServer: catches, sends EXITED frame,
        //     stays alive for the next LOAD.
        //   - Tests: caller can catch and assert.
        // Direct System.exit would kill the JVM in all hosts and prevent
        // the wire host from staying alive across runs.
        long code = getLong(args, "code");
        throw new MezzeExitException((int) code);
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
