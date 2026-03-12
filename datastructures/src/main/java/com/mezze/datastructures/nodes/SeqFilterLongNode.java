package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedCountingConditionProfile;

/**
 * Extremely optimized filter node for long sequences.
 *
 * Uses:
 * - Unboxed long[] throughout
 * - Pre-allocated result array with trim
 * - DirectCallNode with inline caching
 * - InlinedCountingConditionProfile for predicate branch prediction
 */
@NodeInfo(shortName = "seq.filter.long")
public abstract class SeqFilterLongNode extends Node {

    public abstract LongSeqStorage execute(LongSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected LongSeqStorage filterEmpty(LongSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return storage;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected LongSeqStorage filterSmall(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] result = new long[size]; // Pre-allocate max
        int count = 0;

        for (int i = 0; i < size; i++) {
            long elem = data[i];
            Object[] args = prepareArgs(closureValues, elem);
            boolean matches = (boolean) callNode.call(args);

            if (matchProfile.profile(this, matches)) {
                result[count++] = elem;
            }
        }

        return trimResult(result, count, size);
    }

    @Specialization(guards = "storage.size() > 8", replaces = "filterSmall")
    protected LongSeqStorage filterLarge(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] result = new long[size]; // Pre-allocate max
        int count = 0;

        for (int i = 0; i < size; i++) {
            long elem = data[i];
            Object[] args = prepareArgs(closureValues, elem);
            boolean matches = (boolean) callNode.call(args);

            if (matchProfile.profile(this, matches)) {
                result[count++] = elem;
            }
        }

        return trimResult(result, count, size);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, long value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value;
        return args;
    }

    private static LongSeqStorage trimResult(long[] result, int count, int originalSize) {
        if (count == 0) {
            return new LongSeqStorage(new long[0]);
        }
        if (count == originalSize) {
            return new LongSeqStorage(result);
        }
        // Trim to actual size
        long[] trimmed = new long[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return new LongSeqStorage(trimmed);
    }

    public static SeqFilterLongNode create() {
        return SeqFilterLongNodeGen.create();
    }

    public static SeqFilterLongNode getUncached() {
        return SeqFilterLongNodeGen.getUncached();
    }
}
