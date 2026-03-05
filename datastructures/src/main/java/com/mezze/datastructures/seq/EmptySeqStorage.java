package com.mezze.datastructures.seq;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Singleton storage for empty sequences.
 * Zero allocation for empty seqs.
 */
public final class EmptySeqStorage implements SeqStorage {

    @CompilationFinal
    public static final EmptySeqStorage INSTANCE = new EmptySeqStorage();

    private EmptySeqStorage() {}

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Object get(int index) {
        throw new IndexOutOfBoundsException("Empty sequence has no elements");
    }

    @Override
    public SeqStorage update(int index, Object value) {
        throw new IndexOutOfBoundsException("Empty sequence has no elements");
    }

    @Override
    public SeqStorage append(Object value) {
        return SeqStorageFactory.createSingle(value);
    }

    @Override
    public SeqStorage prepend(Object value) {
        return SeqStorageFactory.createSingle(value);
    }

    @Override
    public SeqStorage slice(int start, int end) {
        if (start != 0 || end != 0) {
            throw new IndexOutOfBoundsException("Invalid slice range for empty sequence");
        }
        return this;
    }

    @Override
    public SeqStorage concat(SeqStorage other) {
        return other;
    }

    @Override
    public byte getStrategyTag() {
        return TAG_EMPTY;
    }

    @Override
    public String toString() {
        return "EmptySeqStorage[]";
    }
}
