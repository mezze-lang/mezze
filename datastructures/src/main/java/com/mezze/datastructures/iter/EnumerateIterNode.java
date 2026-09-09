package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Descriptor node for {@code iter::enumerate}. Pairs each element
 * with its 0-based index as { i: Int, val: 'a }.
 */
public final class EnumerateIterNode extends IterNode {

    @CompilationFinal
    public final IterNode src;

    public EnumerateIterNode(IterNode src) {
        this.src = src;
    }
}
