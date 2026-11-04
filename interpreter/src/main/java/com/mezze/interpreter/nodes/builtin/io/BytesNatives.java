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

    /**
     * Companion to {@link #fold}: same helper, same reasons.
     *
     * The callback field is `x`, NOT `byte` — `std::collections::bytes` declares
     * `map`'s as `{ x: Int } -> Int` while `fold`'s is `{ acc, byte }`. Writing
     * `byte` for both is invisible on the scalar path, which is positional, and
     * `map` turns out not to take that path here at all, so the name is
     * load-bearing: both of `BytesHofScalarBypassTest`'s map cases fail when it
     * is wrong, and neither of its fold cases does.
     */
    public static MezzeBytes map(MezzeBytes bytes, MezzeFunction mapper,
                                                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = com.mezze.interpreter.nodes.builtin.collections.ScalarCall
            .prepare1(mapper, "x");
        int size = bytes.length();
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            result[i] = ((Long) ctx.call1(callNode, (long) (bytes.get(i) & 0xFF))).byteValue();
        }
        return new MezzeBytes(result);
    }

    /**
     * Fold the bytes, calling the guest folder per element.
     *
     * NOT {@code @TruffleBoundary}, and that is the point. A boundary on a HOF's
     * loop means the loop never compiles and the callback is never inlined into
     * it — the opposite of what a hot fold wants. The seq, list, iter and
     * nonemptylist folds have never had one.
     *
     * {@link com.mezze.interpreter.nodes.builtin.collections.ScalarCall} is the
     * shared way to do this: it resolves the scalar-ABI bypass ONCE, reuses one
     * positional args array for the whole loop, and falls back to a record built
     * from the declared field names — prepending closure values, which the
     * hand-rolled code in these natives used to get wrong. The old loop called a
     * local `prepareArgsTwo` per element, which allocated four objects every
     * iteration: two `Object[]`, the field-name `String[]`, and a
     * `MezzeRecord`.
     *
     * MEASURED, `bench/cases/fuseall` kernel 4 against kernel 1 at n=2M:
     * 41 076 ps/elem to 8 089, with the control steady at 385 to 383.
     *
     * (An earlier version of this note split that gap into "~4x fusion, 10x
     * this path" on the strength of running the seq fold with
     * `MEZZE_CHAIN_CANONICALIZE=0`. That split is WITHDRAWN: the canonicalizer
     * is a LOWERING pass, so the variable acts when lowering runs — build time
     * under `mez build` — and setting it at run time changed nothing. Both
     * columns were the same code and the difference was noise at n=500k, a size
     * where the control itself swung 910 to 6 600.)
     *
     * The field names are `acc` and `byte`, per `std::collections::bytes` — and
     * see {@link #map}, whose callback is named differently.
     */
    public static Object fold(MezzeBytes bytes, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = com.mezze.interpreter.nodes.builtin.collections.ScalarCall
            .prepare2(folder, "acc", "byte");
        int size = bytes.length();
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = ctx.call2(callNode, acc, (long) (bytes.get(i) & 0xFF));
        }
        return acc;
    }

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
