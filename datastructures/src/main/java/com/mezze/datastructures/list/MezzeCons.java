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
 * General cons cell with Object head.
 * Used for non-primitive elements or mixed-type lists.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeCons<A> implements MezzeList<A> {

    @CompilationFinal
    private final A headValue;

    @CompilationFinal
    private final MezzeList<A> tailList;

    @CompilationFinal
    private int cachedSize = -1;

    public MezzeCons(A head, MezzeList<A> tail) {
        this.headValue = head;
        this.tailList = tail;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public A head() {
        return headValue;
    }

    @Override
    public MezzeList<A> tail() {
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("List(");
        MezzeList<A> current = this;
        boolean first = true;
        while (!current.isEmpty()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(current.head());
            current = current.tail();
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
        MezzeList<A> current = this;
        while (!current.isEmpty()) {
            result = 31 * result + Objects.hashCode(current.head());
            current = current.tail();
        }
        return result;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    long getArraySize() {
        return size();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < size();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= size()) {
            throw InvalidArrayIndexException.create(index);
        }
        return get((int) index);
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    void writeArrayElement(@SuppressWarnings("unused") long index, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIterator() {
        return new ListInteropHelper.ListIterator(iterator());
    }
}
