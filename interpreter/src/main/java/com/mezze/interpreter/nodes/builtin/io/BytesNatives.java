package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Native Bytes operations - ULTRA PERFORMANT.
 *
 * Bytes is represented as byte[] wrapped in MezzeBytes.
 * All operations are immutable (return new byte arrays).
 */
public final class BytesNatives {

    private BytesNatives() {}

    /**
     * Wrapper class for byte array
     */
    public static final class MezzeBytes {
        private final byte[] data;

        public MezzeBytes(byte[] data) {
            this.data = data;
        }

        public byte[] getData() {
            return data;
        }

        public int length() {
            return data.length;
        }

        public boolean isEmpty() {
            return data.length == 0;
        }

        public byte get(int index) {
            return data[index];
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeBytes empty() {
        return new MezzeBytes(new byte[0]);
    }

    @TruffleBoundary
    public static MezzeBytes fromString(String s) {
        return new MezzeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    @TruffleBoundary
    public static MezzeBytes fromArray(Object[] arr) {
        byte[] bytes = new byte[arr.length];
        for (int i = 0; i < arr.length; i++) {
            bytes[i] = ((Long) arr[i]).byteValue();
        }
        return new MezzeBytes(bytes);
    }

    @TruffleBoundary
    public static MezzeVariant fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            return MezzeVariant.none();
        }
        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return MezzeVariant.some(new MezzeBytes(bytes));
        } catch (NumberFormatException e) {
            return MezzeVariant.none();
        }
    }

    @TruffleBoundary
    public static MezzeVariant fromBase64(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return MezzeVariant.some(new MezzeBytes(bytes));
        } catch (IllegalArgumentException e) {
            return MezzeVariant.none();
        }
    }

    // ==================== Basic Operations ====================

    public static long length(MezzeBytes bytes) {
        return bytes.length();
    }

    public static boolean isEmpty(MezzeBytes bytes) {
        return bytes.isEmpty();
    }

    public static MezzeVariant get(MezzeBytes bytes, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= bytes.length()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some((long) (bytes.get(idx) & 0xFF));
    }

    // ==================== Equality & Comparison ====================

    @TruffleBoundary
    public static boolean equals(MezzeBytes b1, MezzeBytes b2) {
        return java.util.Arrays.equals(b1.getData(), b2.getData());
    }

    /**
     * Lexicographic unsigned byte comparison returning -1, 0, or 1.
     */
    @TruffleBoundary
    public static long compare(MezzeBytes b1, MezzeBytes b2) {
        int minLen = Math.min(b1.length(), b2.length());
        for (int i = 0; i < minLen; i++) {
            int cmp = Integer.compare(b1.get(i) & 0xFF, b2.get(i) & 0xFF);
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(b1.length(), b2.length());
    }

    // ==================== Slice Operations ====================

    @TruffleBoundary
    public static MezzeBytes slice(MezzeBytes bytes, long start, long end) {
        int s = Math.max(0, (int) start);
        int e = Math.min(bytes.length(), (int) end);
        if (s >= e) {
            return empty();
        }
        byte[] result = new byte[e - s];
        System.arraycopy(bytes.getData(), s, result, 0, e - s);
        return new MezzeBytes(result);
    }

    @TruffleBoundary
    public static MezzeBytes take(MezzeBytes bytes, long n) {
        int count = Math.min(bytes.length(), Math.max(0, (int) n));
        if (count == 0) return empty();
        byte[] result = new byte[count];
        System.arraycopy(bytes.getData(), 0, result, 0, count);
        return new MezzeBytes(result);
    }

    @TruffleBoundary
    public static MezzeBytes drop(MezzeBytes bytes, long n) {
        int skip = Math.min(bytes.length(), Math.max(0, (int) n));
        if (skip >= bytes.length()) return empty();
        byte[] result = new byte[bytes.length() - skip];
        System.arraycopy(bytes.getData(), skip, result, 0, bytes.length() - skip);
        return new MezzeBytes(result);
    }

    // ==================== Concat Operations ====================

    @TruffleBoundary
    public static MezzeBytes concat(MezzeBytes b1, MezzeBytes b2) {
        byte[] result = new byte[b1.length() + b2.length()];
        System.arraycopy(b1.getData(), 0, result, 0, b1.length());
        System.arraycopy(b2.getData(), 0, result, b1.length(), b2.length());
        return new MezzeBytes(result);
    }

    @TruffleBoundary
    public static MezzeBytes concatAll(Object[] bytesArray) {
        int totalLen = 0;
        for (Object b : bytesArray) {
            totalLen += ((MezzeBytes) b).length();
        }
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (Object b : bytesArray) {
            MezzeBytes bytes = (MezzeBytes) b;
            System.arraycopy(bytes.getData(), 0, result, offset, bytes.length());
            offset += bytes.length();
        }
        return new MezzeBytes(result);
    }

    // ==================== Search Operations ====================

    @TruffleBoundary
    public static boolean contains(MezzeBytes bytes, MezzeBytes pattern) {
        return indexOfPattern(bytes, pattern) >= 0;
    }

    @TruffleBoundary
    public static MezzeVariant indexOf(MezzeBytes bytes, MezzeBytes pattern) {
        int idx = indexOfPattern(bytes, pattern);
        if (idx >= 0) {
            return MezzeVariant.some((long) idx);
        }
        return MezzeVariant.none();
    }

    private static int indexOfPattern(MezzeBytes bytes, MezzeBytes pattern) {
        if (pattern.length() == 0) return 0;
        if (pattern.length() > bytes.length()) return -1;

        int limit = bytes.length() - pattern.length() + 1;
        outer:
        for (int i = 0; i < limit; i++) {
            for (int j = 0; j < pattern.length(); j++) {
                if (bytes.get(i + j) != pattern.get(j)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @TruffleBoundary
    public static boolean startsWith(MezzeBytes bytes, MezzeBytes prefix) {
        if (prefix.length() > bytes.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes.get(i) != prefix.get(i)) return false;
        }
        return true;
    }

    @TruffleBoundary
    public static boolean endsWith(MezzeBytes bytes, MezzeBytes suffix) {
        if (suffix.length() > bytes.length()) return false;
        int offset = bytes.length() - suffix.length();
        for (int i = 0; i < suffix.length(); i++) {
            if (bytes.get(offset + i) != suffix.get(i)) return false;
        }
        return true;
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeBytes reverse(MezzeBytes bytes) {
        byte[] result = new byte[bytes.length()];
        for (int i = 0; i < bytes.length(); i++) {
            result[bytes.length() - 1 - i] = bytes.get(i);
        }
        return new MezzeBytes(result);
    }

    @TruffleBoundary
    public static MezzeBytes repeat(MezzeBytes bytes, long times) {
        if (times <= 0) return empty();
        int n = (int) times;
        byte[] result = new byte[bytes.length() * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(bytes.getData(), 0, result, i * bytes.length(), bytes.length());
        }
        return new MezzeBytes(result);
    }

    /** Companion to {@link #fold}: same bypass, same reasons. */
    public static MezzeBytes map(MezzeBytes bytes, MezzeFunction mapper,
                                                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = mapper.getClosureValues();
        int closureLen = closureValues.length;
        int size = bytes.length();
        byte[] result = new byte[size];

        com.oracle.truffle.api.CallTarget innerTarget =
            com.mezze.interpreter.nodes.builtin.collections.ScalarBypass.tryResolve(mapper);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                args[closureLen] = (long) (bytes.get(i) & 0xFF);
                result[i] = ((Long) callNode.call(innerTarget, args)).byteValue();
            }
            return new MezzeBytes(result);
        }

        com.oracle.truffle.api.CallTarget target = mapper.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < size; i++) {
            args[closureLen] = MezzeRecord.fromFields(
                MAP_FIELDS, new Object[]{(long) (bytes.get(i) & 0xFF)});
            result[i] = ((Long) callNode.call(target, args)).byteValue();
        }
        return new MezzeBytes(result);
    }

    /**
     * Fold the bytes, calling the guest folder per element.
     *
     * NOT {@code @TruffleBoundary}, and that is the point. A boundary on a HOF's
     * loop means the loop never compiles and the callback is never inlined into
     * it — the opposite of what a hot fold wants. {@link
     * com.mezze.interpreter.nodes.builtin.collections.SeqNatives#fold} has never
     * had one.
     *
     * Takes the same scalar-ABI bypass as the seq fold: when the folder is a
     * trampoline with no tail calls and no dict params, call its inner target
     * with a POSITIONAL argument array reused across the whole loop. The old
     * code called `prepareArgsTwo` per element, which allocated four objects
     * every iteration — two `Object[]`, the field-name `String[]`, and a
     * `MezzeRecord` — on top of running interpreted.
     *
     * MEASURED, `bench/cases/fuseall` kernel 4 against kernel 1 at the same
     * size: a bytes fold cost 38 171 ps/elem where an UNFUSED seq fold cost
     * 3 704 and a fused one 910. Fusion accounted for ~4x of that; this path
     * accounted for the remaining 10x.
     */
    public static Object fold(MezzeBytes bytes, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = folder.getClosureValues();
        int closureLen = closureValues.length;
        int size = bytes.length();
        Object acc = init;

        com.oracle.truffle.api.CallTarget innerTarget =
            com.mezze.interpreter.nodes.builtin.collections.ScalarBypass.tryResolve(folder);
        if (innerTarget != null) {
            String[] params = folder.getParameterNames();
            boolean accFirst = params == null || params.length < 2 || "acc".equals(params[0]);
            int accSlot = accFirst ? closureLen : closureLen + 1;
            int elemSlot = accFirst ? closureLen + 1 : closureLen;
            Object[] args = new Object[closureLen + 2];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                args[accSlot] = acc;
                args[elemSlot] = (long) (bytes.get(i) & 0xFF);
                acc = callNode.call(innerTarget, args);
            }
            return acc;
        }

        // Record path for a folder the bypass cannot take. Still hoists the
        // closure copy and the field names out of the loop; only the record
        // itself has to be fresh, because the callee may retain it.
        com.oracle.truffle.api.CallTarget target = folder.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < size; i++) {
            args[closureLen] = MezzeRecord.fromFields(
                FOLD_FIELDS, new Object[]{acc, (long) (bytes.get(i) & 0xFF)});
            acc = callNode.call(target, args);
        }
        return acc;
    }

    /** Hoisted: the folder's argument-record field names never change. */
    private static final String[] FOLD_FIELDS = {"acc", "byte"};
    /** Hoisted: likewise the mapper's. `x`, not `byte` — std::collections::bytes
     *  declares `map`'s callback as `{ x: Int } -> Int` while `fold`'s is
     *  `{ acc, byte }`. The scalar bypass is positional and would not have
     *  noticed the difference. */
    private static final String[] MAP_FIELDS = {"x"};

    // ==================== Conversion ====================

    /**
     * Convert bytes to UTF-8 string.
     * Returns: Result String String (Ok {val = string} or Err {error = "Invalid UTF-8"})
     */
    @TruffleBoundary
    public static MezzeVariant bytesToString(MezzeBytes bytes) {
        try {
            String s = new String(bytes.getData(), StandardCharsets.UTF_8);
            return MezzeVariant.ok(s);
        } catch (Exception e) {
            return MezzeVariant.err("Invalid UTF-8");
        }
    }

    @TruffleBoundary
    public static String toHex(MezzeBytes bytes) {
        StringBuilder sb = new StringBuilder(bytes.length() * 2);
        for (int i = 0; i < bytes.length(); i++) {
            sb.append(String.format("%02x", bytes.get(i) & 0xFF));
        }
        return sb.toString();
    }

    @TruffleBoundary
    public static String toBase64(MezzeBytes bytes) {
        return Base64.getEncoder().encodeToString(bytes.getData());
    }

    @TruffleBoundary
    public static Object[] toArray(MezzeBytes bytes) {
        Object[] result = new Object[bytes.length()];
        for (int i = 0; i < bytes.length(); i++) {
            result[i] = (long) (bytes.get(i) & 0xFF);
        }
        return result;
    }

}
