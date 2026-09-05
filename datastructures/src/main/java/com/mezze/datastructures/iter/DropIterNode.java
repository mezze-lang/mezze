package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Skip descriptor: drops the first {@code n} elements from the source.
 * Like all {@link IterNode} subclasses, immutable and {@code @CompilationFinal}.
 */
public final class DropIterNode extends IterNode {
    @CompilationFinal
    public final IterNode src;
    @CompilationFinal
    public final long n;

    public DropIterNode(IterNode src, long n) {
        this.src = src;
        this.n = n;
    }
}
