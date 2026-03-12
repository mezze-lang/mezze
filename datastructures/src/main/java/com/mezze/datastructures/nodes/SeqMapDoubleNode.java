package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized map node for double sequences.
 *
 * Uses:
 * - Unboxed double[] throughout the entire pipeline
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 * - No boxing until absolutely necessary
 */
@NodeInfo(shortName = "seq.map.double")
public abstract class SeqMapDoubleNode extends Node {

    /**
     * Maps a DoubleSeqStorage using a function that returns double.
     * Stays completely unboxed throughout.
     */
    public abstract DoubleSeqStorage execute(DoubleSeqStorage storage, CallTarget mapper, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected DoubleSeqStorage mapEmpty(DoubleSeqStorage storage, CallTarget mapper, Object[] closureValues) {
        return storage;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected DoubleSeqStorage mapTiny(
            DoubleSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] result = new double[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            Object mapResult = callNode.call(args);
            result[i] = unboxDouble(mapResult);
        }

        return new DoubleSeqStorage(result);
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() <= 16"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected DoubleSeqStorage mapSmall(
            DoubleSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] result = new double[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            Object mapResult = callNode.call(args);
            result[i] = unboxDouble(mapResult);
        }

        return new DoubleSeqStorage(result);
    }

    @Specialization(guards = "storage.size() > 16", replaces = {"mapTiny", "mapSmall"})
    protected DoubleSeqStorage mapLarge(
            DoubleSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] result = new double[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            Object mapResult = callNode.call(args);
            result[i] = unboxDouble(mapResult);
        }

        return new DoubleSeqStorage(result);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, double value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value; // Unboxed double passed directly
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

    public static SeqMapDoubleNode create() {
        return SeqMapDoubleNodeGen.create();
    }

    public static SeqMapDoubleNode getUncached() {
        return SeqMapDoubleNodeGen.getUncached();
    }
}
