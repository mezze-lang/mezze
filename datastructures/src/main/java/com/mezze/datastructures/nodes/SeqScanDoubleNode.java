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
 * Optimized scan/prefix-sum node for double sequences.
 *
 * scan f init [a,b,c] = [init, f(init,a), f(f(init,a),b), f(f(f(init,a),b),c)]
 *
 * Returns all intermediate accumulator values, including initial.
 * Result length = input length + 1
 *
 * Uses:
 * - Unboxed double[] throughout
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 */
@NodeInfo(shortName = "seq.scan.double")
public abstract class SeqScanDoubleNode extends Node {

    public abstract DoubleSeqStorage execute(DoubleSeqStorage storage, double initial, CallTarget folder, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected DoubleSeqStorage scanEmpty(DoubleSeqStorage storage, double initial, CallTarget folder, Object[] closureValues) {
        return new DoubleSeqStorage(new double[] { initial });
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected DoubleSeqStorage scanTiny(
            DoubleSeqStorage storage,
            double initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] result = new double[size + 1];
        result[0] = initial;
        double acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
            result[i + 1] = acc;
        }

        return new DoubleSeqStorage(result);
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() <= 16"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected DoubleSeqStorage scanSmall(
            DoubleSeqStorage storage,
            double initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] result = new double[size + 1];
        result[0] = initial;
        double acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
            result[i + 1] = acc;
        }

        return new DoubleSeqStorage(result);
    }

    @Specialization(guards = "storage.size() > 16", replaces = {"scanTiny", "scanSmall"})
    protected DoubleSeqStorage scanLarge(
            DoubleSeqStorage storage,
            double initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] result = new double[size + 1];
        result[0] = initial;
        double acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
            result[i + 1] = acc;
        }

        return new DoubleSeqStorage(result);
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

    public static SeqScanDoubleNode create() {
        return SeqScanDoubleNodeGen.create();
    }

    public static SeqScanDoubleNode getUncached() {
        return SeqScanDoubleNodeGen.getUncached();
    }
}
