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
 * Optimized zipWith node for double sequences.
 *
 * Combines two sequences element-wise with a binary function.
 * zipWith f [a,b,c] [x,y,z] = [f(a,x), f(b,y), f(c,z)]
 *
 * Uses:
 * - Unboxed double[] throughout
 * - DirectCallNode with inline caching
 */
@NodeInfo(shortName = "seq.zipwith.double")
public abstract class SeqZipWithDoubleNode extends Node {

    public abstract DoubleSeqStorage execute(DoubleSeqStorage a, DoubleSeqStorage b, CallTarget combiner, Object[] closureValues);

    protected static int minSize(DoubleSeqStorage a, DoubleSeqStorage b) {
        return a.size() < b.size() ? a.size() : b.size();
    }

    @Specialization(guards = "a.size() == 0 || b.size() == 0")
    protected DoubleSeqStorage zipWithEmpty(DoubleSeqStorage a, DoubleSeqStorage b, CallTarget combiner, Object[] closureValues) {
        return new DoubleSeqStorage(new double[0]);
    }

    @Specialization(guards = {"a.size() > 0", "b.size() > 0"})
    protected DoubleSeqStorage zipWithNonEmpty(
            DoubleSeqStorage a,
            DoubleSeqStorage b,
            CallTarget combiner,
            Object[] closureValues,
            @Cached("create(combiner)") DirectCallNode callNode) {

        int size = minSize(a, b);
        double[] dataA = a.getArray();
        double[] dataB = b.getArray();
        double[] result = new double[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, dataA[i], dataB[i]);
            result[i] = unboxDouble(callNode.call(args));
        }

        return new DoubleSeqStorage(result);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, double a, double b) {
        int len = closureValues.length;
        Object[] args = new Object[len + 2];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = a;
        args[len + 1] = b;
        return args;
    }

    private static double unboxDouble(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l.doubleValue();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new IllegalStateException("Expected Double but got " + value.getClass());
    }

    public static SeqZipWithDoubleNode create() {
        return SeqZipWithDoubleNodeGen.create();
    }

    public static SeqZipWithDoubleNode getUncached() {
        return SeqZipWithDoubleNodeGen.getUncached();
    }
}
