package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for {@code iter::zip}. Pairs elements from two
 * iterators as { fst: 'a, snd: 'b }. Stops at the shorter.
 */
public final class ZipIterNode extends IterNode {

    @CompilationFinal
    public final IterNode first;

    @CompilationFinal
    public final IterNode second;

    public ZipIterNode(IterNode first, IterNode second) {
        this.first = first;
        this.second = second;
    }
}
