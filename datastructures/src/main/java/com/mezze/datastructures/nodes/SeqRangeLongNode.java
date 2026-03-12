package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * Optimized range creation for long sequences.
 *
 * Uses vectorized fill for large ranges.
 */
@NodeInfo(shortName = "seq.range")
public abstract class SeqRangeLongNode extends Node {

    public abstract long[] execute(long start, long end, long step);

    // Unit step, vectorizable
    @Specialization(guards = "step == 1")
    @TruffleBoundary
    protected long[] rangeUnit(long start, long end, long step) {
        int size = (int)(end - start);
        if (size <= 0) return new long[0];

        long[] result = new long[size];
        // Fill with iota pattern - JIT can vectorize this
        for (int i = 0; i < size; i++) {
            result[i] = start + i;
        }
        return result;
    }

    // General step
    @Specialization(replaces = "rangeUnit")
    @TruffleBoundary
    protected long[] rangeGeneral(long start, long end, long step) {
        if (step == 0) return new long[0];

        int size;
        if (step > 0) {
            if (start >= end) return new long[0];
            size = (int)((end - start + step - 1) / step);
        } else {
            if (start <= end) return new long[0];
            size = (int)((start - end - step - 1) / (-step));
        }

        long[] result = new long[size];
        long value = start;
        for (int i = 0; i < size; i++) {
            result[i] = value;
            value += step;
        }
        return result;
    }

    public static SeqRangeLongNode create() {
        return SeqRangeLongNodeGen.create();
    }

    public static SeqRangeLongNode getUncached() {
        return SeqRangeLongNodeGen.getUncached();
    }
}
