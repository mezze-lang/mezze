package com.mezze.datastructures.bytes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.Arrays;

/**
 * Zero-copy view into a byte array.
 * Shares underlying data with parent MezzeBytes.
 */
public final class MezzeBytesView implements TruffleObject {

    @CompilationFinal(dimensions = 1)
    private final byte[] data;  // Shared with parent

    @CompilationFinal
    private final int offset;

    @CompilationFinal
    private final int length;

    public MezzeBytesView(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    /**
     * Returns the number of bytes in this view.
     */
    public int size() {
        return length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * Returns the byte at index within this view.
     */
    public byte get(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + length);
        }
        return data[offset + index];
    }

    /**
     * Returns the byte at index as unsigned int (0-255).
     */
    public int getUnsigned(int index) {
        return get(index) & 0xFF;
    }

    /**
     * Returns a sub-view of this view.
     */
    public MezzeBytesView slice(int start, int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice: " + start + " to " + end);
        }
        return new MezzeBytesView(data, offset + start, end - start);
    }

    /**
     * Materializes this view to an independent MezzeBytes.
     */
    public MezzeBytes toBytes() {
        if (length == 0) return MezzeBytes.empty();
        return MezzeBytes.wrap(Arrays.copyOfRange(data, offset, offset + length));
    }

    /**
     * Returns a copy of the bytes in this view.
     */
    public byte[] toArray() {
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    @Override
    public String toString() {
        return "BytesView(" + length + " bytes at offset " + offset + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeBytesView other)) return false;
        if (length != other.length) return false;

        for (int i = 0; i < length; i++) {
            if (data[offset + i] != other.data[other.offset + i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < length; i++) {
            result = 31 * result + data[offset + i];
        }
        return result;
    }
}
