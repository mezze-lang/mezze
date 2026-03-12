package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized zipWith node for long sequences.
 *
 * Combines two sequences element-wise with a binary function.
 * zipWith f [a,b,c] [x,y,z] = [f(a,x), f(b,y), f(c,z)]
 *
 * Uses:
 * - Unboxed long[] throughout
 * - DirectCallNode with inline caching
 */
@NodeInfo(shortName = "seq.zipwith.long")
public abstract class SeqZipWithLongNode extends Node {

    public abstract LongSeqStorage execute(LongSeqStorage a, LongSeqStorage b, CallTarget combiner, Object[] closureValues);

    protected static int minSize(LongSeqStorage a, LongSeqStorage b) {
        return a.size() < b.size() ? a.size() : b.size();
    }

    @Specialization(guards = "a.size() == 0 || b.size() == 0")
    protected LongSeqStorage zipWithEmpty(LongSeqStorage a, LongSeqStorage b, CallTarget combiner, Object[] closureValues) {
        return new LongSeqStorage(new long[0]);
    }

    @Specialization(guards = {"a.size() > 0", "b.size() > 0"})
    protected LongSeqStorage zipWithNonEmpty(
            LongSeqStorage a,
            LongSeqStorage b,
            CallTarget combiner,
            Object[] closureValues,
            @Cached("create(combiner)") DirectCallNode callNode) {

        int size = minSize(a, b);
        long[] dataA = a.getArray();
        long[] dataB = b.getArray();
        long[] result = new long[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, dataA[i], dataB[i]);
            result[i] = unboxLong(callNode.call(args));
        }

        return new LongSeqStorage(result);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, long a, long b) {
        int len = closureValues.length;
        Object[] args = new Object[len + 2];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = a;
        args[len + 1] = b;
        return args;
    }

    private static long unboxLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new IllegalStateException("Expected Long but got " + value.getClass());
    }

    public static SeqZipWithLongNode create() {
        return SeqZipWithLongNodeGen.create();
    }

    public static SeqZipWithLongNode getUncached() {
        return SeqZipWithLongNodeGen.getUncached();
    }
}
