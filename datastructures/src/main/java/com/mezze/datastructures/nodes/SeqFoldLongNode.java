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
 * Extremely optimized fold/reduce node for long sequences.
 *
 * Uses:
 * - Unboxed long accumulator throughout
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 * - No boxing until final result
 */
@NodeInfo(shortName = "seq.fold.long")
public abstract class SeqFoldLongNode extends Node {

    /**
     * Folds a LongSeqStorage using a function (acc, elem) -> acc.
     * The accumulator and all elements stay unboxed.
     */
    public abstract long execute(LongSeqStorage storage, long initial, CallTarget folder, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected long foldEmpty(LongSeqStorage storage, long initial, CallTarget folder, Object[] closureValues) {
        return initial;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected long foldTiny(
            LongSeqStorage storage,
            long initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxLong(callNode.call(args));
        }

        return acc;
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() <= 16"})
    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    protected long foldSmall(
            LongSeqStorage storage,
            long initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxLong(callNode.call(args));
        }

        return acc;
    }

    @Specialization(guards = "storage.size() > 16", replaces = {"foldTiny", "foldSmall"})
    protected long foldLarge(
            LongSeqStorage storage,
            long initial,
            CallTarget folder,
            Object[] closureValues,
            @Cached("create(folder)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long acc = initial;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxLong(callNode.call(args));
        }

        return acc;
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, long acc, long elem) {
        int len = closureValues.length;
        Object[] args = new Object[len + 2];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = acc;
        args[len + 1] = elem;
        return args;
    }

    private static long unboxLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new IllegalStateException("Expected Long but got " + value.getClass());
    }

    public static SeqFoldLongNode create() {
        return SeqFoldLongNodeGen.create();
    }

    public static SeqFoldLongNode getUncached() {
        return SeqFoldLongNodeGen.getUncached();
    }
}
