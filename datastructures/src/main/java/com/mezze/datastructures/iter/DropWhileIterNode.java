package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for {@code iter::drop_while}. Drops elements
 * while the predicate holds, then yields the rest.
 */
public final class DropWhileIterNode extends IterNode {

    @CompilationFinal
    public final IterNode src;

    @CompilationFinal
    public final Object predicate;

    public DropWhileIterNode(IterNode src, Object predicate) {
        this.src = src;
        this.predicate = predicate;
    }
}
