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
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Extremely optimized find node for double sequences.
 *
 * Returns the first element matching the predicate.
 *
 * Uses:
 * - Unboxed double[] throughout
 * - Early exit on first match
 * - DirectCallNode with inline caching
 */
@NodeInfo(shortName = "seq.find.double")
public abstract class SeqFindDoubleNode extends Node {

    /**
     * Returns the index of the first matching element, or -1 if not found.
     * Caller converts to Option type.
     */
    public abstract int execute(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected int findEmpty(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return -1;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected int findSmall(
            DoubleSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedBranchProfile foundProfile) {

        int size = storage.size();
        double[] data = storage.getArray();

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            boolean matches = (boolean) callNode.call(args);
            if (matches) {
                foundProfile.enter(this);
                return i;
            }
        }

        return -1;
    }

    @Specialization(guards = "storage.size() > 8", replaces = "findSmall")
    protected int findLarge(
            DoubleSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedBranchProfile foundProfile) {

        int size = storage.size();
        double[] data = storage.getArray();

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            boolean matches = (boolean) callNode.call(args);
            if (matches) {
                foundProfile.enter(this);
                return i;
            }
        }

        return -1;
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

    public static SeqFindDoubleNode create() {
        return SeqFindDoubleNodeGen.create();
    }

    public static SeqFindDoubleNode getUncached() {
        return SeqFindDoubleNodeGen.getUncached();
    }
}
