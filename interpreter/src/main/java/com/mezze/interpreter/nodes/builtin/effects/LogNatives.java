package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native implementations for Log effect operations.
 *
 * Operations:
 * - console_print / console_println: raw output to stdout (the `Console`
 *   handler formats in-guest, then calls these).
 * - jvm_log: emit a record into java.util.logging under a named logger (the
 *   `JvmLogger` handler), so guest logs share the host's logging pipeline.
 */
public final class LogNatives {

    private LogNatives() {}

    @TruffleBoundary
    public static Object consolePrint(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.print(msg);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object consolePrintln(MezzeRecord args) {
        String msg = getString(args, "msg");
        System.out.println(msg);
        return MezzeRecord.empty();
    }

    /**
     * Emit a record into the JVM logger (java.util.logging) under the given
     * logger name, so the guest `Log` effect can share one pipeline with the
     * host and JVM libraries (Helidon &c.) — governed by the host's own
     * logging config (destination, rotation, format). Level names match the
     * Helidon knob's vocabulary; an unknown name falls back to INFO.
     */
    @TruffleBoundary
    public static Object jvmLog(MezzeRecord args) {
        String logger = getString(args, "logger");
        String level = getString(args, "level");
        String msg = getString(args, "msg");
        java.util.logging.Level jul = switch (level) {
            case "error" -> java.util.logging.Level.SEVERE;
            case "warn"  -> java.util.logging.Level.WARNING;
            case "debug" -> java.util.logging.Level.FINE;
            case "trace" -> java.util.logging.Level.FINEST;
            default      -> java.util.logging.Level.INFO;
        };
        java.util.logging.Logger.getLogger(logger).log(jul, msg);
        return MezzeRecord.empty();
    }

    private static String getString(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) {
            throw new NativeTypeMismatchException("Missing field '" + name + "'");
        }
        Object val = r.getField(index);
        if (val instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected String for field '" + name + "'");
    }
}
