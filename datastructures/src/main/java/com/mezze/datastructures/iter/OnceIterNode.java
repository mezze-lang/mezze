package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for a single-element iterator ({@code iter::once}).
 */
public final class OnceIterNode extends IterNode {

    @CompilationFinal
    public final Object value;

    public OnceIterNode(Object value) {
        this.value = value;
    }
}
