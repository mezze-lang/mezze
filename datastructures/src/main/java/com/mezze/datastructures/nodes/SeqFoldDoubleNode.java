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
 * Extremely optimized fold/reduce node for double sequences.
 *
 * Uses:
 * - Unboxed double accumulator throughout
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 * - No boxing until final result
 */
@NodeInfo(shortName = "seq.fold.double")
public abstract class SeqFoldDoubleNode extends Node {

    /**
     * Folds a DoubleSeqStorage using a function (acc, elem) -> acc.
     * The accumulator and all elements stay unboxed.
     */
    public abstract double execute(DoubleSeqStorage storage, double initial, CallTarget folder, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected double foldEmpty(DoubleSeqStorage storage, double initial, CallTarget folder, Object[] closureValues) {
        return initial;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected double foldTiny(
            DoubleSeqStorage storage,
            double initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
        }

        return acc;
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() <= 16"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected double foldSmall(
            DoubleSeqStorage storage,
            double initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
        }

        return acc;
    }

    @Specialization(guards = "storage.size() > 16", replaces = {"foldTiny", "foldSmall"})
    protected double foldLarge(
            DoubleSeqStorage storage,
            double initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
        }

        return acc;
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, double acc, double elem) {
        int len = closureValues.length;
        Object[] args = new Object[len + 2];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = acc;
        args[len + 1] = elem;
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

    public static SeqFoldDoubleNode create() {
        return SeqFoldDoubleNodeGen.create();
    }

    public static SeqFoldDoubleNode getUncached() {
        return SeqFoldDoubleNodeGen.getUncached();
    }
}
