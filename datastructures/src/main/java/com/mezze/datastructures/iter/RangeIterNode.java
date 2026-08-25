package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Leaf descriptor: {@code [start, end)} stepped by {@code step}. All fields
 * primitive-long and {@code @CompilationFinal} so a fold specialization that
 * captures the descriptor can bake the range bounds into the JITted loop.
 */
public final class RangeIterNode extends IterNode {
    @CompilationFinal
    public final long start;
    @CompilationFinal
    public final long end;
    @CompilationFinal
    public final long step;

    public RangeIterNode(long start, long end, long step) {
        this.start = start;
        this.end = end;
        this.step = step;
    }

    /** Element count assuming {@code step != 0}; returns 0 for empty ranges. */
    public long length() {
        if (step > 0) {
            if (start >= end) return 0;
            return (end - start + step - 1) / step;
        } else if (step < 0) {
            if (start <= end) return 0;
            return (start - end + (-step) - 1) / (-step);
        } else {
            return 0;
        }
    }
}
