package com.mezze.interpreter.v2.values;

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
}
