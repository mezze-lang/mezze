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
 * Extremely optimized reduce (foldl1) node for long sequences.
 *
 * Unlike fold, reduce doesn't take an initial value - uses first element.
 * Returns Option[Long] since empty sequence has no value.
 *
 * Uses:
 * - Unboxed long accumulator throughout
 * - DirectCallNode with inline caching
 * - @ExplodeLoop for small sequences
 */
@NodeInfo(shortName = "seq.reduce.long")
public abstract class SeqReduceLongNode extends Node {

    // Sentinel for Option.None
    public static final long NONE_SENTINEL = Long.MIN_VALUE;

    /**
     * Returns the reduced value, or NONE_SENTINEL if empty.
     * Caller should wrap in Option type.
     */
    public abstract long execute(LongSeqStorage storage, CallTarget reducer, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected long reduceEmpty(LongSeqStorage storage, CallTarget reducer, Object[] closureValues) {
        return NONE_SENTINEL;
    }

    @Specialization(guards = "storage.size() == 1")
    protected long reduceSingle(LongSeqStorage storage, CallTarget reducer, Object[] closureValues) {
        return storage.getArray()[0];
    }

    @Specialization(guards = {"storage.size() > 1", "storage.size() <= 8"})
    @ExplodeLoop
    protected long reduceSmall(
            LongSeqStorage storage,
            CallTarget reducer,
            Object[] closureValues,
            @Cached("create(reducer)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long acc = data[0];

        for (int i = 1; i < size; i++) {
            Object[] args = prepareArgs(closureValues, acc, data[i]);
            acc = unboxLong(callNode.call(args));
        }

        return acc;
    }

    @Specialization(guards = "storage.size() > 8", replaces = "reduceSmall")
    protected long reduceLarge(
            LongSeqStorage storage,
            CallTarget reducer,
            Object[] closureValues,
            @Cached("create(reducer)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();
        long acc = data[0];

        for (int i = 1; i < size; i++) {
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

    public static SeqReduceLongNode create() {
        return SeqReduceLongNodeGen.create();
    }

    public static SeqReduceLongNode getUncached() {
        return SeqReduceLongNodeGen.getUncached();
    }
}
