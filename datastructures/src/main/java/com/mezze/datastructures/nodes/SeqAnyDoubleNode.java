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
 * Extremely optimized any/exists predicate node for double sequences.
 *
 * Uses:
 * - Unboxed double[] throughout
 * - Early exit on first match
 * - DirectCallNode with inline caching
 * - InlinedBranchProfile for early exit prediction
 */
@NodeInfo(shortName = "seq.any.double")
public abstract class SeqAnyDoubleNode extends Node {

    public abstract boolean execute(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected boolean anyEmpty(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return false;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected boolean anySmall(
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
                return true;
            }
        }

        return false;
    }

    @Specialization(guards = "storage.size() > 8", replaces = "anySmall")
    protected boolean anyLarge(
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
                return true;
            }
        }

        return false;
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

    public static SeqAnyDoubleNode create() {
        return SeqAnyDoubleNodeGen.create();
    }

    public static SeqAnyDoubleNode getUncached() {
        return SeqAnyDoubleNodeGen.getUncached();
    }
}
