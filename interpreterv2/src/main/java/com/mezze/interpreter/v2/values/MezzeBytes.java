package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * IMMUTABLE byte array for Mezze - ZERO-COPY where possible.
 *
 * Optimized for:
 * 1. Direct byte[] access - no boxing
 * 2. Zero-copy slicing via offset/length views
 * 3. Efficient concatenation
 * 4. Fast equality checks (length first, then content)
 *
 * Use MezzeByteBuffer for mutable byte operations.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeBytes implements TruffleObject {

    // Singleton empty bytes
    private static final MezzeBytes EMPTY = new MezzeBytes(new byte[0], 0, 0);

    // Backing array (may be shared for slices)
    @CompilationFinal(dimensions = 1)
    private final byte[] data;

    // Offset into backing array (for zero-copy slicing)
    @CompilationFinal
    private final int offset;

    // Length of this view
    @CompilationFinal
    private final int length;

    // Cached hash code (-1 means not computed)
    private int hash = -1;

    private MezzeBytes(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    // ===== FACTORY METHODS =====

    public static MezzeBytes empty() {
        return EMPTY;
    }

    public static MezzeBytes wrap(byte[] data) {
        if (data.length == 0) return EMPTY;
        return new MezzeBytes(data, 0, data.length);
    }

    public static MezzeBytes copyOf(byte[] data) {
        if (data.length == 0) return EMPTY;
        return new MezzeBytes(data.clone(), 0, data.length);
    }

    public static MezzeBytes copyOf(byte[] data, int offset, int length) {
        if (length == 0) return EMPTY;
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        return new MezzeBytes(copy, 0, length);
    }

    @TruffleBoundary
    public static MezzeBytes fromString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) return EMPTY;
        return new MezzeBytes(bytes, 0, bytes.length);
    }

    public static MezzeBytes fromLongs(long[] values) {
        byte[] data = new byte[values.length * 8];
        for (int i = 0; i < values.length; i++) {
            long v = values[i];
            int off = i * 8;
            data[off] = (byte) (v >>> 56);
            data[off + 1] = (byte) (v >>> 48);
            data[off + 2] = (byte) (v >>> 40);
            data[off + 3] = (byte) (v >>> 32);
            data[off + 4] = (byte) (v >>> 24);
            data[off + 5] = (byte) (v >>> 16);
            data[off + 6] = (byte) (v >>> 8);
            data[off + 7] = (byte) v;
        }
        return new MezzeBytes(data, 0, data.length);
    }

    // ===== ACCESSORS =====

    public int length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * Get byte at index - NO BOXING.
     */
    public byte getByte(int index) {
        return data[offset + index];
    }

    /**
     * Get byte as unsigned int (0-255).
     */
    public int getUnsignedByte(int index) {
        return data[offset + index] & 0xFF;
    }

    /**
     * Get backing array (may be shared - do not modify!).
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Get offset into backing array.
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Copy to new byte array.
     */
    public byte[] toByteArray() {
        if (length == 0) return new byte[0];
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }

    // ===== MULTI-BYTE READS =====

    public short getShort(int index) {
        int i = offset + index;
        return (short) ((data[i] << 8) | (data[i + 1] & 0xFF));
    }

    public int getInt(int index) {
        int i = offset + index;
        return ((data[i] & 0xFF) << 24) |
               ((data[i + 1] & 0xFF) << 16) |
               ((data[i + 2] & 0xFF) << 8) |
               (data[i + 3] & 0xFF);
    }

    public long getLong(int index) {
        int i = offset + index;
        return ((long) (data[i] & 0xFF) << 56) |
               ((long) (data[i + 1] & 0xFF) << 48) |
               ((long) (data[i + 2] & 0xFF) << 40) |
               ((long) (data[i + 3] & 0xFF) << 32) |
               ((long) (data[i + 4] & 0xFF) << 24) |
               ((long) (data[i + 5] & 0xFF) << 16) |
               ((long) (data[i + 6] & 0xFF) << 8) |
               ((long) (data[i + 7] & 0xFF));
    }

    // ===== SLICING (ZERO-COPY) =====

    /**
     * Create a view of a portion of this bytes - ZERO COPY.
     */
    public MezzeBytes slice(int start, int end) {
        if (start < 0) start = 0;
        if (end > length) end = length;
        if (start >= end) return EMPTY;
        // Share backing array
        return new MezzeBytes(data, offset + start, end - start);
    }

    public MezzeBytes slice(int start) {
        return slice(start, length);
    }

    // ===== CONCATENATION =====

    @TruffleBoundary
    public MezzeBytes concat(MezzeBytes other) {
        if (this.isEmpty()) return other;
        if (other.isEmpty()) return this;

        byte[] result = new byte[this.length + other.length];
        System.arraycopy(this.data, this.offset, result, 0, this.length);
        System.arraycopy(other.data, other.offset, result, this.length, other.length);
        return new MezzeBytes(result, 0, result.length);
    }

    // ===== SEARCH =====

    public int indexOf(byte b) {
        for (int i = 0; i < length; i++) {
            if (data[offset + i] == b) return i;
        }
        return -1;
    }

    public int lastIndexOf(byte b) {
        for (int i = length - 1; i >= 0; i--) {
            if (data[offset + i] == b) return i;
        }
        return -1;
    }

    public boolean contains(byte b) {
        return indexOf(b) >= 0;
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public String toStringUtf8() {
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }

    @TruffleBoundary
    public String toHexString() {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            sb.append(Character.forDigit(b >> 4, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return length;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < length;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= length) {
            throw InvalidArrayIndexException.create(index);
        }
        return (long) (data[offset + (int) index] & 0xFF);  // Return as unsigned
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;  // Immutable
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;  // Immutable
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        return "Bytes[" + length + "]";
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MezzeBytes that)) return false;

        // Length check first (fast rejection)
        if (this.length != that.length) return false;

        // Content comparison
        for (int i = 0; i < length; i++) {
            if (this.data[this.offset + i] != that.data[that.offset + i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == -1) {
            h = 1;
            for (int i = 0; i < length; i++) {
                h = 31 * h + data[offset + i];
            }
            hash = h;
        }
        return h;
    }
}
