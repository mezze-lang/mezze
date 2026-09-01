package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native implementations for `impl Stdin for MockStdin { lines: [Str] }`.
 *
 * Mezze handler config is immutable, but stdin reads need to advance through
 * the provided lines. We track a cursor per MockStdin instance, keyed on the
 * identity of the `lines` MezzeSeq passed in. The WeakHashMap lets cursor
 * entries get reclaimed when the MockStdin handler goes out of scope.
 *
 * The cursor is shared across read_line / read_char / read_bytes — reading 3
 * chars then a line consumes the next 3 chars and then the rest-of-line +
 * following lines as expected.
 *
 * The flattened view: lines joined with "\n" (no trailing newline). read_line
 * returns the next un-consumed line; read_char and read_bytes consume from
 * the flattened byte buffer at the cursor's position.
 */
public final class MockStdinNatives {

    private MockStdinNatives() {}

    /** Cursor position is a byte offset into the flattened buffer. */
    private static final java.util.Map<MezzeSeq, Cursor> CURSORS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Test hook: drop all cursors. JUnit-only; call in @BeforeEach so
     *  test ordering can't leak cursor positions across tests that
     *  happen to reuse a `lines` value that the runtime has interned. */
    public static void clearCursors() {
        CURSORS.clear();
    }

    private static final class Cursor {
        final byte[] flat;
        final AtomicInteger pos = new AtomicInteger(0);

        Cursor(MezzeSeq lines) {
            StringBuilder sb = new StringBuilder();
            int n = lines.size();
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append('\n');
                Object v = lines.get(i);
                sb.append(v == null ? "" : v.toString());
            }
            this.flat = sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static Cursor cursorFor(MezzeSeq lines) {
        return CURSORS.computeIfAbsent(lines, Cursor::new);
    }

    @TruffleBoundary
    public static Object readLine(MezzeRecord args) {
        Cursor cur = cursorFor(getSeq(args, "lines"));
        int p = cur.pos.get();
        if (p >= cur.flat.length) return MezzeVariant.none();
        int end = p;
        while (end < cur.flat.length && cur.flat[end] != '\n') end++;
        String line = new String(cur.flat, p, end - p, StandardCharsets.UTF_8);
        cur.pos.set(end < cur.flat.length ? end + 1 : end);  // skip the \n if present
        return MezzeVariant.some(line);
    }

    @TruffleBoundary
    public static Object readChar(MezzeRecord args) {
        Cursor cur = cursorFor(getSeq(args, "lines"));
        int p = cur.pos.get();
        if (p >= cur.flat.length) return MezzeVariant.none();
        // Decode one UTF-8 code point starting at p.
        int b = cur.flat[p] & 0xFF;
        int len = utf8Len(b);
        if (len < 0 || p + len > cur.flat.length) {
            cur.pos.set(cur.flat.length);
            return MezzeVariant.none();
        }
        String ch = new String(cur.flat, p, len, StandardCharsets.UTF_8);
        cur.pos.set(p + len);
        return MezzeVariant.some(ch);
    }

    @TruffleBoundary
    public static BytesNatives.MezzeBytes readBytes(MezzeRecord args) {
        Cursor cur = cursorFor(getSeq(args, "lines"));
        long max = getLong(args, "max");
        if (max <= 0) return BytesNatives.empty();
        int p = cur.pos.get();
        int remaining = cur.flat.length - p;
        if (remaining <= 0) return BytesNatives.empty();
        int n = (int) Math.min(max, remaining);
        byte[] out = new byte[n];
        System.arraycopy(cur.flat, p, out, 0, n);
        cur.pos.set(p + n);
        return new BytesNatives.MezzeBytes(out);
    }

    @TruffleBoundary
    public static Object readPassword(MezzeRecord args) {
        return readLine(args);
    }

    /** Mock terminals have no termios state — set/unset are no-ops. */
    @TruffleBoundary
    public static com.mezze.interpreter.values.MezzeUnit setRawMode(MezzeRecord args) {
        return com.mezze.interpreter.values.MezzeUnit.INSTANCE;
    }

    @TruffleBoundary
    public static com.mezze.interpreter.values.MezzeUnit unsetRawMode(MezzeRecord args) {
        return com.mezze.interpreter.values.MezzeUnit.INSTANCE;
    }

    /** UTF-8 byte-length from the leading byte. -1 on invalid. */
    private static int utf8Len(int b) {
        if ((b & 0x80) == 0) return 1;
        if ((b & 0xE0) == 0xC0) return 2;
        if ((b & 0xF0) == 0xE0) return 3;
        if ((b & 0xF8) == 0xF0) return 4;
        return -1;
    }

    private static MezzeSeq getSeq(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof MezzeSeq seq) return seq;
        if (val instanceof Object[] arr) return MezzeSeq.fromArray(arr);
        throw new NativeTypeMismatchException("Expected MezzeSeq for field '" + name + "'");
    }

    private static long getLong(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) throw new NativeTypeMismatchException("Missing field '" + name + "'");
        Object val = r.getField(index);
        if (val instanceof Long l) return l;
        throw new NativeTypeMismatchException("Expected Long for field '" + name + "'");
    }
}
