package com.mezze.datastructures.list;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.NoSuchElementException;

/**
 * Singleton empty list (Nil).
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeNil<A> implements MezzeList<A> {

    @CompilationFinal
    @SuppressWarnings("rawtypes")
    public static final MezzeNil INSTANCE = new MezzeNil();

    private MezzeNil() {}

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public A head() {
        throw new NoSuchElementException("head of empty list");
    }

    @Override
    public MezzeList<A> tail() {
        throw new NoSuchElementException("tail of empty list");
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public MezzeList<A> reverse() {
        return this;
    }

    @Override
    public String toString() {
        return "Nil";
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MezzeNil;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return 0;
    }

    @ExportMessage
    boolean isArrayElementReadable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        throw InvalidArrayIndexException.create(index);
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
    Object getIterator() {
        return ListInteropHelper.EMPTY_ITERATOR;
    }
}
