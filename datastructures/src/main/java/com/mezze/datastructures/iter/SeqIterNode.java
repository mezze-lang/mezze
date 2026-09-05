package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.mezze.datastructures.seq.MezzeSeq;

/**
 * Leaf descriptor backed by a materialized {@link MezzeSeq}. Enables any
 * seq to enter the lazy Iter pipeline via {@code iter::from_seq}.
 */
public final class SeqIterNode extends IterNode {
    @CompilationFinal
    public final MezzeSeq<?> seq;

    public SeqIterNode(MezzeSeq<?> seq) {
        this.seq = seq;
    }

    public int size() { return seq.size(); }

    public Object get(int i) { return seq.get(i); }
}
