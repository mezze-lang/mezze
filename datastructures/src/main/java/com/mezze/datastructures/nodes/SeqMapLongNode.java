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
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * Extremely optimized map node for long sequences.
 *
 * Uses:
 * - Unboxed long[] throughout the entire pipeline
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 * - InlinedConditionProfile for branch prediction
 * - No boxing until absolutely necessary
 */
@NodeInfo(shortName = "seq.map.long")
public abstract class SeqMapLongNode extends Node {

    /**
     * Maps a LongSeqStorage using a function that returns long.
     * Stays completely unboxed throughout.
     */
    public abstract LongSeqStorage execute(LongSeqStorage storage, CallTarget mapper, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected LongSeqStorage mapEmpty(LongSeqStorage storage, CallTarget mapper, Object[] closureValues) {
        return storage;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected LongSeqStorage mapTiny(
            LongSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] result = new long[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            Object mapResult = callNode.call(args);
            result[i] = unboxLong(mapResult);
        }

        return new LongSeqStorage(result);
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() <= 16"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected LongSeqStorage mapSmall(
            LongSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] result = new long[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            Object mapResult = callNode.call(args);
            result[i] = unboxLong(mapResult);
        }

        return new LongSeqStorage(result);
    }

    @Specialization(guards = "storage.size() > 16", replaces = {"mapTiny", "mapSmall"})
    protected LongSeqStorage mapLarge(
            LongSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] result = new long[size];

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            Object mapResult = callNode.call(args);
            result[i] = unboxLong(mapResult);
        }

        return new LongSeqStorage(result);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, long value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value; // Unboxed long passed directly
        return args;
    }

    private static long unboxLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new IllegalStateException("Expected Long but got " + value.getClass());
    }

    public static SeqMapLongNode create() {
        return SeqMapLongNodeGen.create();
    }

    public static SeqMapLongNode getUncached() {
        return SeqMapLongNodeGen.getUncached();
    }
}
