package com.mezze.interpreter.v2.values;

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
 * MUTABLE byte buffer for Mezze - optimized for I/O and binary data.
 *
 * Key features:
 * 1. Direct byte[] access - no boxing
 * 2. Auto-growing capacity
 * 3. Efficient append operations
 * 4. Read/write position tracking
 * 5. Big-endian multi-byte read/write
 *
 * Use MezzeBytes for immutable byte data.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeByteBuffer implements TruffleObject {

    private static final int DEFAULT_CAPACITY = 256;
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    // Mutable backing array
    private byte[] data;

    // Number of bytes written
    private int size;

    // Read position (for sequential reads)
    private int readPos;

    public MezzeByteBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public MezzeByteBuffer(int initialCapacity) {
        this.data = new byte[initialCapacity];
        this.size = 0;
        this.readPos = 0;
    }

    // ===== FACTORY METHODS =====

    public static MezzeByteBuffer create() {
        return new MezzeByteBuffer();
    }

    public static MezzeByteBuffer withCapacity(int capacity) {
        return new MezzeByteBuffer(capacity);
    }

    public static MezzeByteBuffer fromBytes(MezzeBytes bytes) {
        MezzeByteBuffer buf = new MezzeByteBuffer(bytes.length());
        buf.writeBytes(bytes);
        return buf;
    }

    public static MezzeByteBuffer fromByteArray(byte[] data) {
        MezzeByteBuffer buf = new MezzeByteBuffer(data.length);
        System.arraycopy(data, 0, buf.data, 0, data.length);
        buf.size = data.length;
        return buf;
    }

    // ===== CAPACITY MANAGEMENT =====

    public int size() {
        return size;
    }

    public int capacity() {
        return data.length;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int remaining() {
        return size - readPos;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > data.length) {
            grow(minCapacity);
        }
    }

    private void grow(int minCapacity) {
        int oldCapacity = data.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1);  // 1.5x growth
        if (newCapacity < minCapacity) {
            newCapacity = minCapacity;
        }
        if (newCapacity > MAX_ARRAY_SIZE) {
            newCapacity = MAX_ARRAY_SIZE;
        }
        data = Arrays.copyOf(data, newCapacity);
    }

    public void clear() {
        size = 0;
        readPos = 0;
    }

    public void reset() {
        readPos = 0;
    }

    // ===== SINGLE BYTE WRITE =====

    public void writeByte(byte b) {
        ensureCapacity(size + 1);
        data[size++] = b;
    }

    public void writeByte(int b) {
        writeByte((byte) b);
    }

    // ===== MULTI-BYTE WRITE (BIG ENDIAN) =====

    public void writeShort(short v) {
        ensureCapacity(size + 2);
        data[size++] = (byte) (v >>> 8);
        data[size++] = (byte) v;
    }

    public void writeInt(int v) {
        ensureCapacity(size + 4);
        data[size++] = (byte) (v >>> 24);
        data[size++] = (byte) (v >>> 16);
        data[size++] = (byte) (v >>> 8);
        data[size++] = (byte) v;
    }

    public void writeLong(long v) {
        ensureCapacity(size + 8);
        data[size++] = (byte) (v >>> 56);
        data[size++] = (byte) (v >>> 48);
        data[size++] = (byte) (v >>> 40);
        data[size++] = (byte) (v >>> 32);
        data[size++] = (byte) (v >>> 24);
        data[size++] = (byte) (v >>> 16);
        data[size++] = (byte) (v >>> 8);
        data[size++] = (byte) v;
    }

    public void writeFloat(float v) {
        writeInt(Float.floatToIntBits(v));
    }

    public void writeDouble(double v) {
        writeLong(Double.doubleToLongBits(v));
    }

    // ===== BULK WRITE =====

    public void writeBytes(byte[] bytes) {
        writeBytes(bytes, 0, bytes.length);
    }

    public void writeBytes(byte[] bytes, int offset, int length) {
        ensureCapacity(size + length);
        System.arraycopy(bytes, offset, data, size, length);
        size += length;
    }

    public void writeBytes(MezzeBytes bytes) {
        writeBytes(bytes.getData(), bytes.getOffset(), bytes.length());
    }

    @TruffleBoundary
    public void writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeBytes(bytes);
    }

    // ===== SINGLE BYTE READ =====

    public byte readByte() {
        return data[readPos++];
    }

    public int readUnsignedByte() {
        return data[readPos++] & 0xFF;
    }

    // ===== MULTI-BYTE READ (BIG ENDIAN) =====

    public short readShort() {
        return (short) ((data[readPos++] << 8) | (data[readPos++] & 0xFF));
    }

    public int readInt() {
        return ((data[readPos++] & 0xFF) << 24) |
               ((data[readPos++] & 0xFF) << 16) |
               ((data[readPos++] & 0xFF) << 8) |
               (data[readPos++] & 0xFF);
    }

    public long readLong() {
        return ((long) (data[readPos++] & 0xFF) << 56) |
               ((long) (data[readPos++] & 0xFF) << 48) |
               ((long) (data[readPos++] & 0xFF) << 40) |
               ((long) (data[readPos++] & 0xFF) << 32) |
               ((long) (data[readPos++] & 0xFF) << 24) |
               ((long) (data[readPos++] & 0xFF) << 16) |
               ((long) (data[readPos++] & 0xFF) << 8) |
               ((long) (data[readPos++] & 0xFF));
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    // ===== BULK READ =====

    public byte[] readBytes(int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, readPos, result, 0, length);
        readPos += length;
        return result;
    }

    public void readBytes(byte[] dest, int offset, int length) {
        System.arraycopy(data, readPos, dest, offset, length);
        readPos += length;
    }

    // ===== RANDOM ACCESS =====

    public byte getByte(int index) {
        return data[index];
    }

    public void setByte(int index, byte value) {
        data[index] = value;
    }

    public int getInt(int index) {
        return ((data[index] & 0xFF) << 24) |
               ((data[index + 1] & 0xFF) << 16) |
               ((data[index + 2] & 0xFF) << 8) |
               (data[index + 3] & 0xFF);
    }

    public void setInt(int index, int value) {
        data[index] = (byte) (value >>> 24);
        data[index + 1] = (byte) (value >>> 16);
        data[index + 2] = (byte) (value >>> 8);
        data[index + 3] = (byte) value;
    }

    public long getLong(int index) {
        return ((long) (data[index] & 0xFF) << 56) |
               ((long) (data[index + 1] & 0xFF) << 48) |
               ((long) (data[index + 2] & 0xFF) << 40) |
               ((long) (data[index + 3] & 0xFF) << 32) |
               ((long) (data[index + 4] & 0xFF) << 24) |
               ((long) (data[index + 5] & 0xFF) << 16) |
               ((long) (data[index + 6] & 0xFF) << 8) |
               ((long) (data[index + 7] & 0xFF));
    }

    public void setLong(int index, long value) {
        data[index] = (byte) (value >>> 56);
        data[index + 1] = (byte) (value >>> 48);
        data[index + 2] = (byte) (value >>> 40);
        data[index + 3] = (byte) (value >>> 32);
        data[index + 4] = (byte) (value >>> 24);
        data[index + 5] = (byte) (value >>> 16);
        data[index + 6] = (byte) (value >>> 8);
        data[index + 7] = (byte) value;
    }

    // ===== CONVERSION =====

    public MezzeBytes toBytes() {
        return MezzeBytes.copyOf(data, 0, size);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }

    @TruffleBoundary
    public String toStringUtf8() {
        return new String(data, 0, size, StandardCharsets.UTF_8);
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return size;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < size;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= size) {
            throw InvalidArrayIndexException.create(index);
        }
        return (long) (data[(int) index] & 0xFF);
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return index >= 0 && index < size;
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return index == size;  // Can append
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws InvalidArrayIndexException {
        if (index < 0 || index > size) {
            throw InvalidArrayIndexException.create(index);
        }
        int idx = (int) index;
        if (idx == size) {
            writeByte(((Number) value).intValue());
        } else {
            data[idx] = (byte) ((Number) value).intValue();
        }
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "ByteBuffer[size=" + size + ", capacity=" + data.length + "]";
    }
}
