package com.mezze.datastructures.list;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.Objects;

/**
 * Cons cell with unboxed long head for Int/Nat lists.
 * Eliminates boxing overhead for numeric lists.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeLongCons implements MezzeList<Long> {

    @CompilationFinal
    private final long headValue;

    @CompilationFinal
    private final MezzeList<Long> tailList;

    @CompilationFinal
    private int cachedSize = -1;

    public MezzeLongCons(long head, MezzeList<Long> tail) {
        this.headValue = head;
        this.tailList = tail;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Long head() {
        return headValue;
    }

    /**
     * Unboxed head access - no boxing overhead.
     */
    public long headLong() {
        return headValue;
    }

    @Override
    public MezzeList<Long> tail() {
        return tailList;
    }

    @Override
    public int size() {
        if (cachedSize < 0) {
            cachedSize = 1 + tailList.size();
        }
        return cachedSize;
    }

    /**
     * Prepend another long (stays unboxed).
     */
    public MezzeLongCons prependLong(long element) {
        return new MezzeLongCons(element, this);
    }

    // ===== OPTIMIZED NUMERIC OPERATIONS =====

    /**
     * Sum all elements (iterative, no boxing).
     */
    public long sum() {
        long result = 0;
        MezzeList<Long> current = this;
        while (current instanceof MezzeLongCons lc) {
            result += lc.headValue;
            current = lc.tailList;
        }
        return result;
    }

    /**
     * Product of all elements (iterative, no boxing).
     */
    public long product() {
        long result = 1;
        MezzeList<Long> current = this;
        while (current instanceof MezzeLongCons lc) {
            result *= lc.headValue;
            current = lc.tailList;
        }
        return result;
    }

    /**
     * Minimum element.
     */
    public long min() {
        long result = headValue;
        MezzeList<Long> current = tailList;
        while (current instanceof MezzeLongCons lc) {
            if (lc.headValue < result) {
                result = lc.headValue;
            }
            current = lc.tailList;
        }
        return result;
    }

    /**
     * Maximum element.
     */
    public long max() {
        long result = headValue;
        MezzeList<Long> current = tailList;
        while (current instanceof MezzeLongCons lc) {
            if (lc.headValue > result) {
                result = lc.headValue;
            }
            current = lc.tailList;
        }
        return result;
    }

    /**
     * Check if contains value (no boxing).
     */
    public boolean containsLong(long value) {
        MezzeList<Long> current = this;
        while (current instanceof MezzeLongCons lc) {
            if (lc.headValue == value) {
                return true;
            }
            current = lc.tailList;
        }
        return false;
    }

    /**
     * Convert to long array (efficient bulk conversion).
     */
    public long[] toLongArray() {
        long[] result = new long[size()];
        int i = 0;
        MezzeList<Long> current = this;
        while (current instanceof MezzeLongCons lc) {
            result[i++] = lc.headValue;
            current = lc.tailList;
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("List(");
        MezzeList<Long> current = this;
        boolean first = true;
        while (!current.isEmpty()) {
            if (!first) sb.append(", ");
            first = false;
            if (current instanceof MezzeLongCons lc) {
                sb.append(lc.headValue);
                current = lc.tailList;
            } else {
                sb.append(current.head());
                current = current.tail();
            }
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeList<?> other)) return false;
        if (other.isEmpty()) return false;

        MezzeList<?> a = this;
        MezzeList<?> b = other;

        while (!a.isEmpty() && !b.isEmpty()) {
            if (!Objects.equals(a.head(), b.head())) {
                return false;
            }
            a = a.tail();
            b = b.tail();
        }

        return a.isEmpty() && b.isEmpty();
    }

    @Override
    public int hashCode() {
        int result = 1;
        MezzeList<Long> current = this;
        while (current instanceof MezzeLongCons lc) {
            result = 31 * result + Long.hashCode(lc.headValue);
            current = lc.tailList;
        }
        return result;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return size();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < size();
    }

    @ExportMessage
    @TruffleBoundary
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= size()) {
            throw InvalidArrayIndexException.create(index);
        }
        return get((int) index);
    }

    @ExportMessage
    boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
    void writeArrayElement(@SuppressWarnings("unused") long index, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new ListInteropHelper.ListIterator(iterator());
    }
}
