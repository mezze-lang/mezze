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
 * Cons cell with unboxed double head for Float/Frac lists.
 * Eliminates boxing overhead for numeric lists.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeDoubleCons implements MezzeList<Double> {

    @CompilationFinal
    private final double headValue;

    @CompilationFinal
    private final MezzeList<Double> tailList;

    @CompilationFinal
    private int cachedSize = -1;

    public MezzeDoubleCons(double head, MezzeList<Double> tail) {
        this.headValue = head;
        this.tailList = tail;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Double head() {
        return headValue;
    }

    /**
     * Unboxed head access - no boxing overhead.
     */
    public double headDouble() {
        return headValue;
    }

    @Override
    public MezzeList<Double> tail() {
        return tailList;
    }

    @Override
    public int size() {
        if (cachedSize < 0) {
            int count = 0;
            MezzeList<?> current = this;
            while (!current.isEmpty()) {
                count++;
                current = current.tail();
            }
            cachedSize = count;
        }
        return cachedSize;
    }

    /**
     * Prepend another double (stays unboxed).
     */
    public MezzeDoubleCons prependDouble(double element) {
        return new MezzeDoubleCons(element, this);
    }

    // ===== OPTIMIZED NUMERIC OPERATIONS =====

    /**
     * Sum all elements (iterative, no boxing).
     */
    public double sum() {
        double result = 0.0;
        MezzeList<Double> current = this;
        while (current instanceof MezzeDoubleCons dc) {
            result += dc.headValue;
            current = dc.tailList;
        }
        return result;
    }

    /**
     * Product of all elements (iterative, no boxing).
     */
    public double product() {
        double result = 1.0;
        MezzeList<Double> current = this;
        while (current instanceof MezzeDoubleCons dc) {
            result *= dc.headValue;
            current = dc.tailList;
        }
        return result;
    }

    /**
     * Minimum element.
     */
    public double min() {
        double result = headValue;
        MezzeList<Double> current = tailList;
        while (current instanceof MezzeDoubleCons dc) {
            if (dc.headValue < result) {
                result = dc.headValue;
            }
            current = dc.tailList;
        }
        return result;
    }

    /**
     * Maximum element.
     */
    public double max() {
        double result = headValue;
        MezzeList<Double> current = tailList;
        while (current instanceof MezzeDoubleCons dc) {
            if (dc.headValue > result) {
                result = dc.headValue;
            }
            current = dc.tailList;
        }
        return result;
    }

    /**
     * Average of all elements.
     */
    public double average() {
        return sum() / size();
    }

    /**
     * Check if contains value (no boxing).
     */
    public boolean containsDouble(double value) {
        MezzeList<Double> current = this;
        while (current instanceof MezzeDoubleCons dc) {
            if (dc.headValue == value) {
                return true;
            }
            current = dc.tailList;
        }
        return false;
    }

    /**
     * Convert to double array (efficient bulk conversion).
     */
    public double[] toDoubleArray() {
        double[] result = new double[size()];
        int i = 0;
        MezzeList<Double> current = this;
        while (current instanceof MezzeDoubleCons dc) {
            result[i++] = dc.headValue;
            current = dc.tailList;
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("List(");
        MezzeList<Double> current = this;
        boolean first = true;
        while (!current.isEmpty()) {
            if (!first) sb.append(", ");
            first = false;
            if (current instanceof MezzeDoubleCons dc) {
                sb.append(dc.headValue);
                current = dc.tailList;
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
        MezzeList<Double> current = this;
        while (current instanceof MezzeDoubleCons dc) {
            result = 31 * result + Double.hashCode(dc.headValue);
            current = dc.tailList;
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
