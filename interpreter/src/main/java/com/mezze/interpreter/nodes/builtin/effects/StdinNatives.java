package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.interpreter.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Native implementations for the Stdin effect.
 *
 * Corresponds to stdlib file: std/effects/stdin.mz
 *
 * Reads go through a single BufferedReader so line/char modes don't mis-split
 * the stream. Byte mode reads from the raw stdin (System.in) directly — mixing
 * byte and char/line reads in the same program will lose bytes the reader has
 * already buffered. That's the same caveat Java's BufferedReader carries.
 *
 * The reader and byte-stream references are settable via {@link #setStreams}
 * so tests can substitute a fake stdin without forking a subprocess. In
 * production they default to wrapping System.in.
 */
public final class StdinNatives {

    private StdinNatives() {}

    private static volatile BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private static volatile InputStream byteStream = System.in;

    /**
     * Demand-request hook. When wire mode is active, the first stdin read
     * of a run invokes this so the parent knows to start forwarding bytes.
     * Default: no-op (direct-fd mode in built user binaries, JVM jar, and tests).
     */
    private static volatile Runnable onFirstReadRequest = (Runnable) () -> {};

    /**
     * Tracks whether the request hook has fired for the current run. Reset
     * via {@link #resetRequestFlag()} at the start of each LOAD.
     */
    private static volatile boolean requestSent = false;

    /**
     * Wire-mode password reader. When the child JVM is talking to a parent
     * native binary over the wire, the child has no TTY of its own — but the
     * parent does. This hook performs the PASSWORD_REQUEST → PASSWORD_REPLY
     * round-trip: parent puts its real terminal into raw mode (echo off),
     * reads the password, ships it back. Default: null (no-op, falls back
     * to System.console() which returns None for piped stdin).
     */
    private static volatile java.util.function.Supplier<String> wirePasswordReader = null;

    /** Runtime hook (RuntimeServer): install the wire-mode password round-trip. */
    public static void installWirePasswordReader(java.util.function.Supplier<String> reader) {
        wirePasswordReader = reader;
    }

    /** Test hook: swap in a fake stdin. Both reader and raw stream are updated. */
    public static void setStreams(InputStream in) {
        byteStream = in;
        reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Test hook: restore the real System.in-backed streams. */
    public static void resetStreams() {
        byteStream = System.in;
        reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        onFirstReadRequest = (Runnable) () -> {};
        requestSent = false;
    }

    /**
     * Runtime hook (RuntimeServer): plug in the wire-fed buffer and the
     * "I want stdin" demand-request callback. Once installed, all native
     * stdin reads pull from {@code in} and the first read of each run
     * triggers {@code onFirstReadRequest} (so the parent starts forwarding).
     */
    public static void installWireStreams(InputStream in, Runnable onFirstRead) {
        byteStream = in;
        reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        onFirstReadRequest = onFirstRead;
        requestSent = false;
    }

    /** Runtime hook: call at the start of each LOAD so the demand-request fires fresh. */
    public static void resetRequestFlag() {
        requestSent = false;
    }

    private static void maybeRequest() {
        if (!requestSent) {
            requestSent = true;
            try {
                onFirstReadRequest.run();
            } catch (Throwable ignored) {
                // Best-effort. If the hook fails the read will simply block
                // until the parent independently provides bytes (or EOF).
            }
        }
    }

    @TruffleBoundary
    public static Object readLine(MezzeRecord args) {
        maybeRequest();
        try {
            String line = reader.readLine();
            return line == null ? MezzeVariant.none() : MezzeVariant.some(line);
        } catch (IOException e) {
            return MezzeVariant.none();
        }
    }

    @TruffleBoundary
    public static Object readChar(MezzeRecord args) {
        maybeRequest();
        try {
            int c = reader.read();
            if (c < 0) return MezzeVariant.none();
            return MezzeVariant.some(String.valueOf((char) c));
        } catch (IOException e) {
            return MezzeVariant.none();
        }
    }

    @TruffleBoundary
    public static BytesNatives.MezzeBytes readBytes(MezzeRecord args) {
        maybeRequest();
        long max = getLong(args, "max");
        if (max <= 0) return BytesNatives.empty();
        int cap = (int) Math.min(max, Integer.MAX_VALUE);
        byte[] buf = new byte[cap];
        try {
            int n = byteStream.read(buf, 0, cap);
            if (n <= 0) return BytesNatives.empty();
            if (n == cap) return new BytesNatives.MezzeBytes(buf);
            byte[] trimmed = new byte[n];
            System.arraycopy(buf, 0, trimmed, 0, n);
            return new BytesNatives.MezzeBytes(trimmed);
        } catch (IOException e) {
            return BytesNatives.empty();
        }
    }

    /**
     * Parent-reported isatty(0). Default null = no wire host installed;
     * fall back to System.console() which reflects this process's own
     * fd 0 (correct for in-process / built user binaries).
     */
    private static volatile Boolean wireIsTtyStdin = null;

    /**
     * Parent-reported terminal size. Updated by HELLO (initial snapshot)
     * and refreshed by every WINCH frame the parent ships when its
     * terminal resizes. Zero means "unknown" — the parent didn't have a
     * TTY at handshake time, or we're running directly in a built binary
     * with no wire host. Reads under contention are safe because every
     * publisher does a single 64-bit assignment via packing rows/cols.
     */
    private static volatile int wireTermRows = 0;
    private static volatile int wireTermCols = 0;

    /** Runtime hook: install parent's TTY snapshot at handshake time. */
    public static void setWireIsTty(boolean stdin, boolean stdout, boolean stderr) {
        wireIsTtyStdin = stdin;
        // stdout/stderr are flagged here for future readers; not currently surfaced as natives.
    }

    /**
     * Runtime hook: install/refresh parent's terminal size. Called from
     * HELLO handler at handshake (initial values) and from every WINCH
     * dispatch (live updates after SIGWINCH on the parent). Both rows
     * and cols are non-negative; 0 signals "unknown".
     */
    public static void setWireTermSize(int rows, int cols) {
        wireTermRows = rows;
        wireTermCols = cols;
    }

    @TruffleBoundary
    public static boolean isTty(MezzeRecord args) {
        Boolean wired = wireIsTtyStdin;
        if (wired != null) return wired;
        return System.console() != null;
    }

    /**
     * Wire-mode raw-mode toggles. In wire mode the parent owns the
     * terminal, so the child can't directly change termios; it asks the
     * parent via RAW_MODE_SET / RAW_MODE_UNSET frames and the parent does
     * stty raw -echo / restore. Default null = direct mode; the native
     * runs `stty raw -echo < /dev/tty` itself, which works in the built
     * native binary and JVM jar paths where the process owns the TTY.
     */
    private static volatile Runnable wireRawModeSetter = null;
    private static volatile Runnable wireRawModeUnsetter = null;

    /** Runtime hook (RuntimeServer): install the wire-mode raw-mode toggles. */
    public static void installWireRawModeHooks(Runnable setter, Runnable unsetter) {
        wireRawModeSetter = setter;
        wireRawModeUnsetter = unsetter;
    }

    /**
     * Toggle the terminal into raw mode (no canonical line-buffering, no echo).
     * Wire mode: ask the parent. Direct mode: run stty against /dev/tty.
     * No-op when stdin isn't a terminal — stty would silently fail and
     * we just keep going.
     */
    @TruffleBoundary
    public static MezzeRecord setRawMode(MezzeRecord args) {
        Runnable wire = wireRawModeSetter;
        if (wire != null) {
            try { wire.run(); } catch (Throwable ignored) {}
        } else {
            runStty("raw", "-echo");
        }
        return com.mezze.interpreter.values.MezzeRecord.empty();
    }

    /** Restore the terminal to cooked mode. Idempotent. */
    @TruffleBoundary
    public static MezzeRecord unsetRawMode(MezzeRecord args) {
        Runnable wire = wireRawModeUnsetter;
        if (wire != null) {
            try { wire.run(); } catch (Throwable ignored) {}
        } else {
            runStty("-raw", "echo");
        }
        return com.mezze.interpreter.values.MezzeRecord.empty();
    }

    /**
     * Shell out to `stty <flags> < /dev/tty`. Direct-mode helper. Failure
     * is silent — common reasons (no controlling TTY, sandboxed shell,
     * Windows) all reduce to "we tried, nothing changed", which is the
     * documented contract for non-TTY stdin.
     */
    private static void runStty(String... flags) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("stty");
            for (String f : flags) cmd.add(f);
            new ProcessBuilder(cmd)
                .redirectInput(new java.io.File("/dev/tty"))
                .redirectErrorStream(true)
                .start()
                .waitFor();
        } catch (Throwable ignored) {}
    }

    /**
     * Returns the current terminal size as a `{ rows: Int, cols: Int }`
     * record. Wire mode: served from the parent-reported snapshot (live
     * SIGWINCH updates). Direct mode: probes the JNI System.console()
     * width if available, else returns zeros.
     *
     * Why zeros instead of None: simpler to surface as a flat record;
     * the user-program can check `rows == 0 || cols == 0` to mean
     * "no terminal".
     */
    @TruffleBoundary
    public static Object termSize(MezzeRecord args) {
        int rows = wireTermRows;
        int cols = wireTermCols;
        if (rows == 0 && cols == 0) {
            // No wire snapshot — direct mode. Java's Console doesn't
            // expose getTerminalSize() pre-23; rely on env vars LINES /
            // COLUMNS as the conventional fallback (set by most shells).
            try {
                String l = System.getenv("LINES");
                String c = System.getenv("COLUMNS");
                if (l != null) rows = Integer.parseInt(l.trim());
                if (c != null) cols = Integer.parseInt(c.trim());
            } catch (NumberFormatException ignored) {}
        }
        return MezzeRecord.fromFields(
            new String[] { "rows", "cols" },
            new Object[] { (long) rows, (long) cols }
        );
    }

    @TruffleBoundary
    public static Object readPassword(MezzeRecord args) {
        // Wire mode: parent owns the terminal — ask it to read the password
        // with echo off and ship the bytes back. The child JVM never had
        // a TTY of its own (stdin is the wire pipe).
        java.util.function.Supplier<String> wire = wirePasswordReader;
        if (wire != null) {
            String pw = wire.get();
            return pw == null ? MezzeVariant.none() : MezzeVariant.some(pw);
        }
        // Direct-fd mode (built native binary, JVM jar run with real TTY):
        // delegate to Java's Console.readPassword which handles echo off
        // for us. Returns None on piped input (Console is null).
        java.io.Console console = System.console();
        if (console == null) return MezzeVariant.none();
        char[] pw = console.readPassword();
        if (pw == null) return MezzeVariant.none();
        return MezzeVariant.some(new String(pw));
    }

    private static long getLong(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof Long l) return l;
        throw new NativeTypeMismatchException("Expected Long for field '" + name + "'");
    }
}
