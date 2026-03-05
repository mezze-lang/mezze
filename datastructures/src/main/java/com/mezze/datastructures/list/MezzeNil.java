package com.mezze.datastructures.list;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.util.NoSuchElementException;

/**
 * Singleton empty list (Nil).
 */
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
}
