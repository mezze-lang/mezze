package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
    /**
     * Hex-decode. The companion to {@link #toHex} and the same shape of fix:
     * it used to call {@code hex.substring(i*2, i*2+2)} and
     * {@code Integer.parseInt} per byte — a two-character String allocated and
     * parsed for every byte of output.
     *
     * <p>ONE BEHAVIOUR CHANGE, and it is a narrowing. {@code parseInt} accepts
     * a SIGN, so `"-1"` decoded to {@code 0xFF} and `"+f"` to {@code 0x0F};
     * neither is hex. The nibble table rejects both as {@code None}, which is
     * what every other malformed input already did.
     */
    public static MezzeVariant fromHex(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) return MezzeVariant.none();
        byte[] out = new byte[len / 2];
        for (int i = 0, j = 0; j < out.length; j++) {
            int hi = nibble(hex.charAt(i++));
            int lo = nibble(hex.charAt(i++));
            if ((hi | lo) < 0) return MezzeVariant.none();
            out[j] = (byte) ((hi << 4) | lo);
        }
        return MezzeVariant.some(new MezzeBytes(out));
    }

    /** A hex digit's value, or -1. Both cases, as {@code parseInt} accepted. */
    private static int nibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
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

    // ============ map with the mapper RECOGNISED instead of called ============
    //
    // `bytes::map { b, f = { x } -> x * 2 }` does not need a guest call per
    // byte: the mapper is one arithmetic operation against a literal, and
    // `BytesMapArithRewriter` recognises that shape at lowering time and sends
    // the call here instead. SELF-A/B, two builds from identical source with
    // the pass hook the only difference, 1024 bytes, min of 9 at two n:
    //
    //   map `x * 2`   5 540 077 -> 259 413 ps    21.4x    (252 ps/byte)
    //   map `x / 2`   5 390 947 -> 1 479 640      3.64x   (1 452 ps/byte)
    //   bytes::fold / bytes::reverse (controls)   unmoved
    //
    // AT 252 ps/BYTE THE MULTIPLYING MAP BEATS `bytes::reverse`'s 298 — the
    // same shape without a callback, which was taken as the ceiling before this
    // was built. This loop indexes `src[i]` directly where `reverse` calls
    // `bytes.length()` twice per iteration and `bytes.get(i)`.
    //
    // So the whole of the dividing row's residual is ONE INSTRUCTION: 1 200
    // ps/byte, DIV 5.73x MUL, because `k` arrives as a runtime long and cannot
    // be strength-reduced to a shift. A `@ConstantOperand` would fix it and
    // would need a Bytecode DSL operation instead of a native — and
    // BytesSumPrimitiveTest records that routing bytes through the generic
    // fused loop was built, measured, and not worth having.
    //
    // A ROUTE THAT LOOKS LIKE THE ANSWER AND IS NOT: going through a seq
    // (`bytes::to_seq` → `seq::map` → `bytes::from_seq`) so the seq fuser
    // inlines the transform measures 8 431 ps/byte — 1.63x SLOWER than the
    // unfused `map` it was meant to beat, because the boxed `[Int]`
    // intermediate costs more than the callback it removes. bytesurface row 14
    // keeps that measurement.
    //
    // FIVE ENTRY POINTS, NOT ONE WITH AN OP CODE. Each native name resolves to
    // exactly one method, so the operation is dispatched once by the handler
    // lookup and there is no tag to re-interpret per call and no magic number
    // shared across the Kotlin/Java boundary.
    //
    // SEMANTICS ARE THE CALLBACK'S, EXACTLY. `map` hands the mapper
    // `(long) (b & 0xFF)` — the element is the Int in 0..255, not a signed Java
    // byte — and truncates the result with `byteValue()`. Both are reproduced
    // here; the `& 0xFF` and the `(byte)` cast are the whole of it.
    //
    // The rewriter, not these methods, rejects the literals that would behave
    // differently: a zero divisor (the guest `/` raises, this would throw a
    // Java ArithmeticException) and any literal whose arithmetic could overflow
    // a long for an input of 0..255 (Mezze Int overflow RAISES, this would
    // silently wrap). Declining to rewrite leaves the ordinary callback path,
    // which already behaves correctly — there is no fallback inside these.

    @TruffleBoundary
    public static MezzeBytes mapAdd(MezzeBytes bytes, long k) {
        byte[] src = bytes.getData();
        int n = src.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((src[i] & 0xFF) + k);
        return new MezzeBytes(out);
    }

    @TruffleBoundary
    public static MezzeBytes mapSub(MezzeBytes bytes, long k) {
        byte[] src = bytes.getData();
        int n = src.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((src[i] & 0xFF) - k);
        return new MezzeBytes(out);
    }

    @TruffleBoundary
    public static MezzeBytes mapMul(MezzeBytes bytes, long k) {
        byte[] src = bytes.getData();
        int n = src.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((src[i] & 0xFF) * k);
        return new MezzeBytes(out);
    }

    /** The rewriter guarantees {@code k != 0}. */
    @TruffleBoundary
    public static MezzeBytes mapDiv(MezzeBytes bytes, long k) {
        byte[] src = bytes.getData();
        int n = src.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((src[i] & 0xFF) / k);
        return new MezzeBytes(out);
    }

    /** The rewriter guarantees {@code k != 0}. */
    @TruffleBoundary
    public static MezzeBytes mapMod(MezzeBytes bytes, long k) {
        byte[] src = bytes.getData();
        int n = src.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) ((src[i] & 0xFF) % k);
        return new MezzeBytes(out);
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
     * Convert bytes to a UTF-8 string.
     * Returns: Result String String (Ok {val = string} or Err {error = "Invalid UTF-8"}).
     *
     * <p>Strict on purpose: {@code new String(bytes, UTF_8)} never fails, it
     * substitutes U+FFFD, so the `Err` this signature promises was unreachable
     * and a caller testing for it (`net::lines`) could not tell bad bytes from text.
     */
    @TruffleBoundary
    public static MezzeVariant bytesToString(MezzeBytes bytes) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.getData()))
                .toString();
            return MezzeVariant.ok(s);
        } catch (CharacterCodingException e) {
            return MezzeVariant.err("Invalid UTF-8");
        }
    }

    /** Lowercase hex digits, indexed by nibble. */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Hex-encode, two characters a byte.
     *
     * <p>It used to call {@code String.format("%02x", …)} PER BYTE, which
     * parses the format string, allocates a {@code Formatter} and boxes the
     * int every time. Measured on `bench/cases/bytesurface` over a 1 024-byte
     * buffer: <b>49 944 ps a byte</b>, against {@link #toBase64} — the method
     * immediately below, a strictly more complex encoding — at 220. The
     * contrast was the whole diagnosis: base64 hands the buffer to a bulk JDK
     * encoder and hex formatted one byte at a time.
     *
     * <p>Two array writes a byte now, off {@code getData()} rather than
     * {@code get(i)} so the loop reads the array directly.
     *
     * <p>A {@code byte[]} handed over as ISO-8859-1 was tried instead, on the
     * reasoning that {@code new String(char[])} must scan and compress UTF-16
     * down to Latin1. INTERLEAVED, it measured 0.93x — 8% WORSE, seven
     * readings a side with no overlap (char 927-959k, byte 1002-1072k). The
     * all-ASCII {@code char[]} constructor evidently takes a better path than
     * the explicit charset one, so this stays as it is. Measured across two
     * separate runs first, where it looked worse by a different amount and the
     * control had drifted 25% — more than the difference being asked about.
     */
    @TruffleBoundary
    public static String toHex(MezzeBytes bytes) {
        byte[] data = bytes.getData();
        char[] out = new char[data.length * 2];
        for (int i = 0, j = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[j++] = HEX_DIGITS[v >>> 4];
            out[j++] = HEX_DIGITS[v & 0x0F];
        }
        return new String(out);
    }

    @TruffleBoundary
    public static String toBase64(MezzeBytes bytes) {
        return Base64.getEncoder().encodeToString(bytes.getData());
    }

    /**
     * The bytes as a `[Int]`, built straight into a primitive `long[]`.
     *
     * <p>It used to BOX every byte: an `Object[]` filled with
     * `(long) (bytes.get(i) & 0xFF)`, which allocates a `Long` per element and
     * then makes `SeqStorageFactory.fromArray` guess the storage back — three
     * `instanceof` a element to decide it is all `Long`, a second array, and an
     * unboxing pass. 2 496 ps a byte on `bench/cases/bytesurface`, for a
     * conversion that is a widening copy.
     *
     * <p>`MezzeSeq.fromLongs` would CLONE the array, so the storage is built
     * directly instead. Sound because `out` is allocated here and never
     * escapes: the clone exists to stop a caller mutating the sequence it
     * handed over, and there is no such caller.
     */
    @TruffleBoundary
    public static MezzeSeq<Long> toSeq(MezzeBytes bytes) {
        byte[] data = bytes.getData();
        if (data.length == 0) return MezzeSeq.empty();
        long[] out = new long[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i] & 0xFF;
        }
        return MezzeSeq.fromStorage(new LongSeqStorage(out));
    }

}
