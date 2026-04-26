package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native implementations for Log effect operations.
 *
 * Operations:
 * - console_print: Raw output (no level prefix)
 * - console_println: Raw output with newline
 * - log_info: [INFO] prefix
 * - log_warn: [WARN] prefix
 * - log_error: [ERROR] prefix to stderr
 * - log_debug: [DEBUG] prefix
 * - log_trace: [TRACE] prefix
 */
public final class LogNatives {

    private LogNatives() {}

    @TruffleBoundary
    public static Object consolePrint(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.print(msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object consolePrintln(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.println(msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object logInfo(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.println("[INFO] " + msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object logWarn(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.println("[WARN] " + msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object logError(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.err.println("[ERROR] " + msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object logDebug(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.println("[DEBUG] " + msg);
        return MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static Object logTrace(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.println("[TRACE] " + msg);
        return MezzeUnit.INSTANCE;
    }

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
