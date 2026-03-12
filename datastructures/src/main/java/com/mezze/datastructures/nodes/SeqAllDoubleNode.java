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
 * Extremely optimized all/forall predicate node for double sequences.
 *
 * Uses:
 * - Unboxed double[] throughout
 * - Early exit on first non-match
 * - DirectCallNode with inline caching
 * - InlinedBranchProfile for early exit prediction
 */
@NodeInfo(shortName = "seq.all.double")
public abstract class SeqAllDoubleNode extends Node {

    public abstract boolean execute(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected boolean allEmpty(DoubleSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return true; // Vacuous truth
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected boolean allSmall(
            DoubleSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedBranchProfile failProfile) {

        int size = storage.size();
        double[] data = storage.getArray();

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            boolean matches = (boolean) callNode.call(args);
            if (!matches) {
                failProfile.enter(this);
                return false;
            }
        }

        return true;
    }

    @Specialization(guards = "storage.size() > 8", replaces = "allSmall")
    protected boolean allLarge(
            DoubleSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedBranchProfile failProfile) {

        int size = storage.size();
        double[] data = storage.getArray();

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            boolean matches = (boolean) callNode.call(args);
            if (!matches) {
                failProfile.enter(this);
                return false;
            }
        }

        return true;
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

    public static SeqAllDoubleNode create() {
        return SeqAllDoubleNodeGen.create();
    }

    public static SeqAllDoubleNode getUncached() {
        return SeqAllDoubleNodeGen.getUncached();
    }
}
