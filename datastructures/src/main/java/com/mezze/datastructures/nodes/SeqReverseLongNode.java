package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * Optimized sequence reverse for longs.
 *
 * Uses loop unrolling for small sequences.
 */
@NodeInfo(shortName = "seq.reverseLong")
public abstract class SeqReverseLongNode extends Node {

    public abstract long[] execute(long[] data);

    @Specialization(guards = "data.length == 0")
    protected long[] reverseEmpty(long[] data) {
        return data;
    }

    @Specialization(guards = "data.length == 1")
    protected long[] reverseSingle(long[] data) {
        return data;
    }

    @Specialization(guards = "data.length == 2")
    protected long[] reverseTwo(long[] data) {
        return new long[] { data[1], data[0] };
    }

    @Specialization(guards = {"data.length > 2", "data.length <= 8"})
    @ExplodeLoop
    protected long[] reverseSmall(long[] data) {
        int len = data.length;
        long[] result = new long[len];
        for (int i = 0; i < len; i++) {
            result[i] = data[len - 1 - i];
        }
        return result;
    }

    @Specialization(guards = "data.length > 8", replaces = {"reverseSmall"})
    @TruffleBoundary
    protected long[] reverseLarge(long[] data) {
        int len = data.length;
        long[] result = new long[len];
        // Vectorizable loop
        for (int i = 0; i < len; i++) {
            result[i] = data[len - 1 - i];
        }
        return result;
    }

    public static SeqReverseLongNode create() {
        return SeqReverseLongNodeGen.create();
    }

    public static SeqReverseLongNode getUncached() {
        return SeqReverseLongNodeGen.getUncached();
    }
}
