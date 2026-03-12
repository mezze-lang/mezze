package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedCountingConditionProfile;

/**
 * Optimized partition node for long sequences.
 *
 * Splits sequence into two based on predicate: (matches, nonMatches)
 *
 * Uses:
 * - Single pass with two result arrays
 * - Pre-allocated max-size arrays with trimming
 * - InlinedCountingConditionProfile for predicate profiling
 */
@NodeInfo(shortName = "seq.partition.long")
public abstract class SeqPartitionLongNode extends Node {

    /**
     * Returns a record-like result with two LongSeqStorage arrays.
     * Index 0 = matching elements, Index 1 = non-matching elements
     */
    public abstract LongSeqStorage[] execute(LongSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected LongSeqStorage[] partitionEmpty(LongSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return new LongSeqStorage[] {
            new LongSeqStorage(new long[0]),
            new LongSeqStorage(new long[0])
        };
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected LongSeqStorage[] partitionSmall(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] matches = new long[size];
        long[] nonMatches = new long[size];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < size; i++) {
            long elem = data[i];
            Object[] args = prepareArgs(closureValues, elem);
            boolean isMatch = (boolean) callNode.call(args);

            if (matchProfile.profile(this, isMatch)) {
                matches[matchCount++] = elem;
            } else {
                nonMatches[nonMatchCount++] = elem;
            }
        }

        return new LongSeqStorage[] {
            trimArray(matches, matchCount),
            trimArray(nonMatches, nonMatchCount)
        };
    }

    @Specialization(guards = "storage.size() > 8", replaces = "partitionSmall")
    protected LongSeqStorage[] partitionLarge(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        long[] data = storage.getArray();
        long[] matches = new long[size];
        long[] nonMatches = new long[size];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < size; i++) {
            long elem = data[i];
            Object[] args = prepareArgs(closureValues, elem);
            boolean isMatch = (boolean) callNode.call(args);

            if (matchProfile.profile(this, isMatch)) {
                matches[matchCount++] = elem;
            } else {
                nonMatches[nonMatchCount++] = elem;
            }
        }

        return new LongSeqStorage[] {
            trimArray(matches, matchCount),
            trimArray(nonMatches, nonMatchCount)
        };
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

    private static LongSeqStorage trimArray(long[] array, int count) {
        if (count == 0) {
            return new LongSeqStorage(new long[0]);
        }
        if (count == array.length) {
            return new LongSeqStorage(array);
        }
        long[] trimmed = new long[count];
        System.arraycopy(array, 0, trimmed, 0, count);
        return new LongSeqStorage(trimmed);
    }

    public static SeqPartitionLongNode create() {
        return SeqPartitionLongNodeGen.create();
    }

    public static SeqPartitionLongNode getUncached() {
        return SeqPartitionLongNodeGen.getUncached();
    }
}
