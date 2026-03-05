package com.mezze.datastructures.bytes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Mutable byte buffer for efficient byte sequence construction.
 * Grows automatically as needed.
 */
public final class MezzeByteBuffer implements TruffleObject {

    private static final int DEFAULT_CAPACITY = 256;
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private byte[] data;
    private int position;

    private MezzeByteBuffer(int capacity) {
        this.data = new byte[capacity];
        this.position = 0;
    }

    // ===== CONSTRUCTION =====

    /**
     * Creates a new buffer with default capacity.
     */
    public static MezzeByteBuffer create() {
        return new MezzeByteBuffer(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new buffer with specified initial capacity.
     */
    public static MezzeByteBuffer withCapacity(int capacity) {
        return new MezzeByteBuffer(Math.max(capacity, 16));
    }

    // ===== STATE =====

    /**
     * Returns current position (number of bytes written).
     */
    public int position() {
        return position;
    }

    /**
     * Returns remaining capacity before growth needed.
     */
    public int remaining() {
        return data.length - position;
    }

    /**
     * Returns current capacity.
     */
    public int capacity() {
        return data.length;
    }

    /**
     * Resets position to 0 (reuse buffer).
     */
    public MezzeByteBuffer reset() {
        position = 0;
        return this;
    }

    // ===== WRITE PRIMITIVES =====

    /**
     * Writes a single byte.
     */
    public MezzeByteBuffer writeByte(byte b) {
        ensureCapacity(1);
        data[position++] = b;
        return this;
    }

    /**
     * Writes a single byte from int (uses low 8 bits).
     */
    public MezzeByteBuffer writeByte(int b) {
        return writeByte((byte) b);
    }

    /**
     * Writes a short (big-endian, 2 bytes).
     */
    public MezzeByteBuffer writeShort(short s) {
        ensureCapacity(2);
        data[position++] = (byte) (s >> 8);
        data[position++] = (byte) s;
        return this;
    }

    /**
     * Writes an int (big-endian, 4 bytes).
     */
    public MezzeByteBuffer writeInt(int i) {
        ensureCapacity(4);
        data[position++] = (byte) (i >> 24);
        data[position++] = (byte) (i >> 16);
        data[position++] = (byte) (i >> 8);
        data[position++] = (byte) i;
        return this;
    }

    /**
     * Writes a long (big-endian, 8 bytes).
     */
    public MezzeByteBuffer writeLong(long l) {
        ensureCapacity(8);
        data[position++] = (byte) (l >> 56);
        data[position++] = (byte) (l >> 48);
        data[position++] = (byte) (l >> 40);
        data[position++] = (byte) (l >> 32);
        data[position++] = (byte) (l >> 24);
        data[position++] = (byte) (l >> 16);
        data[position++] = (byte) (l >> 8);
        data[position++] = (byte) l;
        return this;
    }

    /**
     * Writes a float (big-endian, 4 bytes).
     */
    public MezzeByteBuffer writeFloat(float f) {
        return writeInt(Float.floatToIntBits(f));
    }

    /**
     * Writes a double (big-endian, 8 bytes).
     */
    public MezzeByteBuffer writeDouble(double d) {
        return writeLong(Double.doubleToLongBits(d));
    }

    // ===== WRITE BULK =====

    /**
     * Writes a byte array.
     */
    public MezzeByteBuffer writeBytes(byte[] bytes) {
        ensureCapacity(bytes.length);
        System.arraycopy(bytes, 0, data, position, bytes.length);
        position += bytes.length;
        return this;
    }

    /**
     * Writes bytes from MezzeBytes.
     */
    public MezzeByteBuffer writeBytes(MezzeBytes bytes) {
        return writeBytes(bytes.getData());
    }

    /**
     * Writes a string as UTF-8 bytes.
     */
    @TruffleBoundary
    public MezzeByteBuffer writeString(String str) {
        return writeString(str, StandardCharsets.UTF_8);
    }

    /**
     * Writes a string with specified encoding.
     */
    @TruffleBoundary
    public MezzeByteBuffer writeString(String str, Charset charset) {
        return writeBytes(str.getBytes(charset));
    }

    /**
     * Writes a length-prefixed string (4-byte length + UTF-8 data).
     */
    @TruffleBoundary
    public MezzeByteBuffer writeLengthPrefixedString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeInt(bytes.length);
        writeBytes(bytes);
        return this;
    }

    // ===== LITTLE ENDIAN VARIANTS =====

    /**
     * Writes a short (little-endian).
     */
    public MezzeByteBuffer writeShortLE(short s) {
        ensureCapacity(2);
        data[position++] = (byte) s;
        data[position++] = (byte) (s >> 8);
        return this;
    }

    /**
     * Writes an int (little-endian).
     */
    public MezzeByteBuffer writeIntLE(int i) {
        ensureCapacity(4);
        data[position++] = (byte) i;
        data[position++] = (byte) (i >> 8);
        data[position++] = (byte) (i >> 16);
        data[position++] = (byte) (i >> 24);
        return this;
    }

    /**
     * Writes a long (little-endian).
     */
    public MezzeByteBuffer writeLongLE(long l) {
        ensureCapacity(8);
        data[position++] = (byte) l;
        data[position++] = (byte) (l >> 8);
        data[position++] = (byte) (l >> 16);
        data[position++] = (byte) (l >> 24);
        data[position++] = (byte) (l >> 32);
        data[position++] = (byte) (l >> 40);
        data[position++] = (byte) (l >> 48);
        data[position++] = (byte) (l >> 56);
        return this;
    }

    // ===== CAPACITY =====

    private void ensureCapacity(int additional) {
        int required = position + additional;
        if (required > data.length) {
            grow(required);
        }
    }

    @TruffleBoundary
    private void grow(int minCapacity) {
        int newCapacity = data.length + (data.length >> 1);  // 1.5x
        if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
        }
        if (newCapacity > MAX_ARRAY_SIZE) {
            newCapacity = MAX_ARRAY_SIZE;
        }
        data = Arrays.copyOf(data, newCapacity);
    }

    // ===== FINALIZATION =====

    /**
     * Returns the buffer contents as MezzeBytes.
     */
    public MezzeBytes toBytes() {
        if (position == 0) return MezzeBytes.empty();
        return MezzeBytes.wrap(Arrays.copyOf(data, position));
    }

    /**
     * Returns the buffer contents as byte array.
     */
    public byte[] toArray() {
        return Arrays.copyOf(data, position);
    }

    /**
     * Returns the buffer contents as MezzeBytes without copying
     * (only if position equals capacity).
     */
    public MezzeBytes toBytesNoShrink() {
        if (position == 0) return MezzeBytes.empty();
        if (position == data.length) {
            return MezzeBytes.wrap(data);
        }
        return toBytes();
    }

    @Override
    public String toString() {
        return "ByteBuffer(position=" + position + ", capacity=" + data.length + ")";
    }
}
