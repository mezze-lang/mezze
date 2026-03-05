package com.mezze.datastructures.bytes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Immutable byte sequence.
 * Backed by byte[] for efficient storage.
 */
public final class MezzeBytes implements TruffleObject {

    @CompilationFinal
    private static final MezzeBytes EMPTY = new MezzeBytes(new byte[0]);

    @CompilationFinal(dimensions = 1)
    private final byte[] data;

    private MezzeBytes(byte[] data) {
        this.data = data;
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns empty bytes.
     */
    public static MezzeBytes empty() {
        return EMPTY;
    }

    /**
     * Creates bytes from a byte array.
     */
    public static MezzeBytes of(byte... bytes) {
        if (bytes.length == 0) return EMPTY;
        return new MezzeBytes(bytes.clone());
    }

    /**
     * Creates bytes from a string using UTF-8 encoding.
     */
    @TruffleBoundary
    public static MezzeBytes fromString(String str) {
        return fromString(str, StandardCharsets.UTF_8);
    }

    /**
     * Creates bytes from a string with specified encoding.
     */
    @TruffleBoundary
    public static MezzeBytes fromString(String str, Charset charset) {
        byte[] bytes = str.getBytes(charset);
        if (bytes.length == 0) return EMPTY;
        return new MezzeBytes(bytes);
    }

    /**
     * Creates bytes wrapping an existing array (no copy).
     */
    public static MezzeBytes wrap(byte[] bytes) {
        if (bytes.length == 0) return EMPTY;
        return new MezzeBytes(bytes);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of bytes.
     */
    public int size() {
        return data.length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return data.length == 0;
    }

    /**
     * Returns the byte at index.
     */
    public byte get(int index) {
        return data[index];
    }

    /**
     * Returns the byte at index as unsigned int (0-255).
     */
    public int getUnsigned(int index) {
        return data[index] & 0xFF;
    }

    /**
     * Returns the underlying byte array (for internal use).
     */
    public byte[] getData() {
        return data;
    }

    // ===== SLICING =====

    /**
     * Returns a slice from start (inclusive) to end (exclusive).
     */
    @TruffleBoundary
    public MezzeBytes slice(int start, int end) {
        if (start < 0 || end > data.length || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice: " + start + " to " + end);
        }
        if (start == end) return EMPTY;
        if (start == 0 && end == data.length) return this;
        return new MezzeBytes(Arrays.copyOfRange(data, start, end));
    }

    /**
     * Returns a view of the slice (shares underlying data).
     */
    public MezzeBytesView sliceView(int start, int end) {
        if (start < 0 || end > data.length || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice: " + start + " to " + end);
        }
        return new MezzeBytesView(data, start, end - start);
    }

    /**
     * Returns first n bytes.
     */
    public MezzeBytes take(int n) {
        if (n <= 0) return EMPTY;
        if (n >= data.length) return this;
        return slice(0, n);
    }

    /**
     * Returns all but first n bytes.
     */
    public MezzeBytes drop(int n) {
        if (n <= 0) return this;
        if (n >= data.length) return EMPTY;
        return slice(n, data.length);
    }

    // ===== CONCAT =====

    /**
     * Concatenates with another bytes.
     */
    @TruffleBoundary
    public MezzeBytes concat(MezzeBytes other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        byte[] result = new byte[data.length + other.data.length];
        System.arraycopy(data, 0, result, 0, data.length);
        System.arraycopy(other.data, 0, result, data.length, other.data.length);
        return new MezzeBytes(result);
    }

    /**
     * Concatenates multiple bytes.
     */
    @TruffleBoundary
    public static MezzeBytes concat(MezzeBytes... parts) {
        int totalLength = 0;
        for (MezzeBytes part : parts) {
            totalLength += part.data.length;
        }

        if (totalLength == 0) return EMPTY;

        byte[] result = new byte[totalLength];
        int offset = 0;
        for (MezzeBytes part : parts) {
            System.arraycopy(part.data, 0, result, offset, part.data.length);
            offset += part.data.length;
        }

        return new MezzeBytes(result);
    }

    // ===== SEARCH =====

    /**
     * Finds index of byte, or -1 if not found.
     */
    public int indexOf(byte b) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == b) return i;
        }
        return -1;
    }

    /**
     * Finds index of byte starting from offset.
     */
    public int indexOf(byte b, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < data.length; i++) {
            if (data[i] == b) return i;
        }
        return -1;
    }

    /**
     * Finds index of pattern, or -1 if not found.
     * Uses KMP algorithm for O(n + m) performance.
     */
    @TruffleBoundary
    public int indexOf(MezzeBytes pattern) {
        return indexOf(pattern, 0);
    }

    /**
     * Finds index of pattern starting from offset.
     * Uses KMP (Knuth-Morris-Pratt) algorithm: O(n + m) instead of O(n * m).
     */
    @TruffleBoundary
    public int indexOf(MezzeBytes pattern, int fromIndex) {
        byte[] p = pattern.data;
        int pLen = p.length;

        if (pLen == 0) return Math.max(0, fromIndex);
        if (pLen > data.length - fromIndex) return -1;

        // For very short patterns, use simple search (KMP overhead not worth it)
        if (pLen <= 4) {
            return indexOfSimple(pattern, fromIndex);
        }

        // Build KMP failure function
        int[] failure = buildKMPFailure(p);

        // KMP search
        int j = 0;  // Pattern index
        for (int i = fromIndex; i < data.length; i++) {
            while (j > 0 && data[i] != p[j]) {
                j = failure[j - 1];
            }
            if (data[i] == p[j]) {
                j++;
            }
            if (j == pLen) {
                return i - pLen + 1;
            }
        }

        return -1;
    }

    /**
     * Simple pattern search for short patterns.
     */
    private int indexOfSimple(MezzeBytes pattern, int fromIndex) {
        byte[] p = pattern.data;
        int pLen = p.length;
        byte first = p[0];

        outer:
        for (int i = fromIndex; i <= data.length - pLen; i++) {
            if (data[i] != first) continue;

            for (int j = 1; j < pLen; j++) {
                if (data[i + j] != p[j]) continue outer;
            }
            return i;
        }

        return -1;
    }

    /**
     * Builds KMP failure function (partial match table).
     */
    private static int[] buildKMPFailure(byte[] pattern) {
        int[] failure = new int[pattern.length];
        failure[0] = 0;

        int k = 0;  // Length of previous longest proper prefix suffix
        for (int i = 1; i < pattern.length; i++) {
            while (k > 0 && pattern[i] != pattern[k]) {
                k = failure[k - 1];
            }
            if (pattern[i] == pattern[k]) {
                k++;
            }
            failure[i] = k;
        }

        return failure;
    }

    /**
     * Returns true if contains byte.
     */
    public boolean contains(byte b) {
        return indexOf(b) >= 0;
    }

    /**
     * Returns true if contains pattern.
     */
    public boolean contains(MezzeBytes pattern) {
        return indexOf(pattern) >= 0;
    }

    /**
     * Returns true if starts with pattern.
     * Uses Arrays.mismatch for SIMD-accelerated comparison.
     */
    public boolean startsWith(MezzeBytes pattern) {
        if (pattern.data.length > data.length) return false;
        if (pattern.data.length == 0) return true;

        // Arrays.mismatch uses SIMD and returns -1 if equal up to pattern length
        // We need to check the prefix, so we use the pattern length as limit
        int mismatch = java.util.Arrays.mismatch(
            data, 0, pattern.data.length,
            pattern.data, 0, pattern.data.length
        );
        return mismatch == -1;
    }

    /**
     * Returns true if ends with pattern.
     * Uses Arrays.mismatch for SIMD-accelerated comparison.
     */
    public boolean endsWith(MezzeBytes pattern) {
        if (pattern.data.length > data.length) return false;
        if (pattern.data.length == 0) return true;

        int offset = data.length - pattern.data.length;
        // Arrays.mismatch uses SIMD and returns -1 if equal
        int mismatch = java.util.Arrays.mismatch(
            data, offset, data.length,
            pattern.data, 0, pattern.data.length
        );
        return mismatch == -1;
    }

    // ===== CONVERSION =====

    /**
     * Converts to string using UTF-8 encoding.
     */
    @TruffleBoundary
    public String toString(Charset charset) {
        return new String(data, charset);
    }

    /**
     * Converts to string using UTF-8 encoding.
     */
    @TruffleBoundary
    public String toStringUtf8() {
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Returns a copy of the byte array.
     */
    public byte[] toArray() {
        return data.clone();
    }

    /**
     * Returns hex string representation.
     */
    @TruffleBoundary
    public String toHexString() {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        if (data.length <= 32) {
            return "Bytes(" + toHexString() + ")";
        }
        return "Bytes(" + data.length + " bytes)";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeBytes other)) return false;
        return Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
