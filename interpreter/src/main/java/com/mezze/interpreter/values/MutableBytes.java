package com.mezze.interpreter.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;

/**
 * Mutable bytes wrapper for safe local mutation.
 *
 * This is the runtime representation of MutBytes - a mutable byte array.
 */
public final class MutableBytes {
    private byte[] data;

    private MutableBytes(byte[] data) {
        this.data = data;
    }

    @TruffleBoundary
    public static MutableBytes fromImmutable(byte[] bytes) {
        return new MutableBytes(Arrays.copyOf(bytes, bytes.length));
    }

    @TruffleBoundary
    public static MutableBytes withCapacity(int capacity) {
        return new MutableBytes(new byte[capacity]);
    }

    @TruffleBoundary
    public byte[] toImmutable() {
        return Arrays.copyOf(data, data.length);
    }

    public int length() {
        return data.length;
    }

    public byte get(int index) {
        return data[index];
    }

    public void set(int index, byte value) {
        data[index] = value;
    }

    public void fill(byte value) {
        Arrays.fill(data, value);
    }

    /**
     * Fill a range with a value.
     */
    public void fillRange(int start, int end, byte value) {
        if (start < 0) start = 0;
        if (end > data.length) end = data.length;
        Arrays.fill(data, start, end, value);
    }

    /**
     * Copy bytes from src to this buffer at destOffset.
     */
    @TruffleBoundary
    public void copyFrom(byte[] src, int srcOffset, int destOffset, int length) {
        System.arraycopy(src, srcOffset, data, destOffset, length);
    }

    /**
     * Copy bytes from another MutableBytes to this buffer.
     */
    @TruffleBoundary
    public void copyFromMutable(MutableBytes src, int srcOffset, int destOffset, int length) {
        System.arraycopy(src.data, srcOffset, data, destOffset, length);
    }

    /**
     * Get a slice as a new immutable byte array.
     */
    @TruffleBoundary
    public byte[] slice(int start, int end) {
        if (start < 0) start = 0;
        if (end > data.length) end = data.length;
        return Arrays.copyOfRange(data, start, end);
    }

    /**
     * Resize the buffer, preserving existing data.
     */
    @TruffleBoundary
    public void resize(int newSize) {
        data = Arrays.copyOf(data, newSize);
    }

    /**
     * Get byte at index, returning -1 if out of bounds.
     */
    public int getSafe(int index) {
        if (index < 0 || index >= data.length) {
            return -1;
        }
        return data[index] & 0xFF;
    }

    /**
     * Set byte at index, returning false if out of bounds.
     */
    public boolean setSafe(int index, int value) {
        if (index < 0 || index >= data.length) {
            return false;
        }
        data[index] = (byte) value;
        return true;
    }

    /**
     * Swap bytes at two indices.
     */
    public boolean swap(int i, int j) {
        if (i < 0 || i >= data.length || j < 0 || j >= data.length) {
            return false;
        }
        byte temp = data[i];
        data[i] = data[j];
        data[j] = temp;
        return true;
    }

    /**
     * Reverse the bytes in place.
     */
    @TruffleBoundary
    public void reverse() {
        int left = 0;
        int right = data.length - 1;
        while (left < right) {
            byte temp = data[left];
            data[left] = data[right];
            data[right] = temp;
            left++;
            right--;
        }
    }

    /**
     * Clear all bytes to zero.
     */
    public void clear() {
        Arrays.fill(data, (byte) 0);
    }
}
