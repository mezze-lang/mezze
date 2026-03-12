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
 * Extremely optimized reduce (foldl1) node for double sequences.
 *
 * Unlike fold, reduce doesn't take an initial value - uses first element.
 * Returns the index as indicator: -1 means empty/None.
 *
 * Uses:
 * - Unboxed double accumulator throughout
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 */
@NodeInfo(shortName = "seq.reduce.double")
public abstract class SeqReduceDoubleNode extends Node {

    // Result holder to avoid boxing
    public static final class ReduceResult {
        public final boolean hasValue;
        public final double value;

        private ReduceResult(boolean hasValue, double value) {
            this.hasValue = hasValue;
            this.value = value;
        }

        public static final ReduceResult NONE = new ReduceResult(false, Double.NaN);

        public static ReduceResult some(double value) {
            return new ReduceResult(true, value);
        }
    }

    public abstract ReduceResult execute(DoubleSeqStorage storage, CallTarget reducer, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected ReduceResult reduceEmpty(DoubleSeqStorage storage, CallTarget reducer, Object[] closureValues) {
        return ReduceResult.NONE;
    }

    @Specialization(guards = "storage.size() == 1")
    protected ReduceResult reduceSingle(DoubleSeqStorage storage, CallTarget reducer, Object[] closureValues) {
        return ReduceResult.some(storage.getArray()[0]);
    }

    @Specialization(guards = {"storage.size() > 1", "storage.size() <= 8"})
    @ExplodeLoop
    protected ReduceResult reduceSmall(
            DoubleSeqStorage storage,
            CallTarget reducer,
            Object[] closureValues,
            @Cached("create(reducer)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double acc = data[0];

        for (int i = 1; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
        }

        return ReduceResult.some(acc);
    }

    @Specialization(guards = "storage.size() > 8", replaces = "reduceSmall")
    protected ReduceResult reduceLarge(
            DoubleSeqStorage storage,
            CallTarget reducer,
            Object[] closureValues,
            @Cached("create(reducer)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double acc = data[0];

        for (int i = 1; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxDouble(callNode.call(args));
        }

        return ReduceResult.some(acc);
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

    public static SeqReduceDoubleNode create() {
        return SeqReduceDoubleNodeGen.create();
    }

    public static SeqReduceDoubleNode getUncached() {
        return SeqReduceDoubleNodeGen.getUncached();
    }
}
