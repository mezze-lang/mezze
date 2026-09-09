package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for {@code iter::flat_map}. Applies the function
 * to each element, yielding elements from the resulting iterators.
 */
public final class FlatMapIterNode extends IterNode {

    @CompilationFinal
    public final IterNode src;

    @CompilationFinal
    public final Object fn;

    public FlatMapIterNode(IterNode src, Object fn) {
        this.src = src;
        this.fn = fn;
    }
}
