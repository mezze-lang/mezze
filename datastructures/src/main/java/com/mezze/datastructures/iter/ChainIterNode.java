package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for {@code iter::chain}. Concatenates two iterators:
 * yields all elements of {@code first}, then all elements of {@code second}.
 */
public final class ChainIterNode extends IterNode {

    @CompilationFinal
    public final IterNode first;

    @CompilationFinal
    public final IterNode second;

    public ChainIterNode(IterNode first, IterNode second) {
        this.first = first;
        this.second = second;
    }
}
