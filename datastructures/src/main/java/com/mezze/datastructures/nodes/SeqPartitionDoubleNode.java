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
 * Optimized partition node for double sequences.
 *
 * Splits sequence into two based on predicate: (matches, nonMatches)
 *
 * Uses:
 * - Single pass with two result arrays
 * - Pre-allocated max-size arrays with trimming
 * - InlinedCountingConditionProfile for predicate profiling
 */
@NodeInfo(shortName = "seq.partition.double")
public abstract class SeqPartitionDoubleNode extends Node {

    /**
     * Returns a record-like result with two DoubleSeqStorage arrays.
     * Index 0 = matching elements, Index 1 = non-matching elements
     */
    public abstract DoubleSeqStorage[] execute(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected DoubleSeqStorage[] partitionEmpty(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return new DoubleSeqStorage[] {
            new DoubleSeqStorage(new double[0]),
            new DoubleSeqStorage(new double[0])
        };
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected DoubleSeqStorage[] partitionSmall(
            DoubleSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] matches = new double[size];
        double[] nonMatches = new double[size];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < size; i++) {
            double elem = data[i];
            Object[] args = prepareArgs(closureValues, elem);
            boolean isMatch = (boolean) callNode.call(args);

            if (matchProfile.profile(this, isMatch)) {
                matches[matchCount++] = elem;
            } else {
                nonMatches[nonMatchCount++] = elem;
            }
        }

        return new DoubleSeqStorage[] {
            trimArray(matches, matchCount),
            trimArray(nonMatches, nonMatchCount)
        };
    }

    @Specialization(guards = "storage.size() > 8", replaces = "partitionSmall")
    protected DoubleSeqStorage[] partitionLarge(
            DoubleSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        double[] data = storage.getArray();
        double[] matches = new double[size];
        double[] nonMatches = new double[size];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < size; i++) {
            double elem = data[i];
            Object[] args = prepareArgs(closureValues, elem);
            boolean isMatch = (boolean) callNode.call(args);

            if (matchProfile.profile(this, isMatch)) {
                matches[matchCount++] = elem;
            } else {
                nonMatches[nonMatchCount++] = elem;
            }
        }

        return new DoubleSeqStorage[] {
            trimArray(matches, matchCount),
            trimArray(nonMatches, nonMatchCount)
        };
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, double value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value;
        return args;
    }

    private static DoubleSeqStorage trimArray(double[] array, int count) {
        if (count == 0) {
            return new DoubleSeqStorage(new double[0]);
        }
        if (count == array.length) {
            return new DoubleSeqStorage(array);
        }
        double[] trimmed = new double[count];
        System.arraycopy(array, 0, trimmed, 0, count);
        return new DoubleSeqStorage(trimmed);
    }

    public static SeqPartitionDoubleNode create() {
        return SeqPartitionDoubleNodeGen.create();
    }

    public static SeqPartitionDoubleNode getUncached() {
        return SeqPartitionDoubleNodeGen.getUncached();
    }
}
