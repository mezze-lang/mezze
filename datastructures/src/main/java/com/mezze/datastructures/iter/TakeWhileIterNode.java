package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for {@code iter::take_while}. Yields elements
 * while the predicate holds, then stops.
 */
public final class TakeWhileIterNode extends IterNode {

    @CompilationFinal
    public final IterNode src;

    @CompilationFinal
    public final Object predicate;

    public TakeWhileIterNode(IterNode src, Object predicate) {
        this.src = src;
        this.predicate = predicate;
    }
}
