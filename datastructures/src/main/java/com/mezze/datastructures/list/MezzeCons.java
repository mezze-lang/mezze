package com.mezze.datastructures.list;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.util.Objects;

/**
 * General cons cell with Object head.
 * Used for non-primitive elements or mixed-type lists.
 */
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
            cachedSize = 1 + tailList.size();
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
}
