package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Truncation descriptor: keeps the first {@code n} elements from the source.
 * Like all {@link IterNode} subclasses, immutable and {@code @CompilationFinal}.
 */
public final class TakeIterNode extends IterNode {
    @CompilationFinal
    public final IterNode src;
    @CompilationFinal
    public final long n;

    public TakeIterNode(IterNode src, long n) {
        this.src = src;
        this.n = n;
    }
}
