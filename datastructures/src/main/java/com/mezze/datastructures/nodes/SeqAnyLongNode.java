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
 * Extremely optimized any/exists predicate node for long sequences.
 *
 * Uses:
 * - Unboxed long[] throughout
 * - Early exit on first match
 * - DirectCallNode with inline caching
 * - InlinedBranchProfile for early exit prediction
 */
@NodeInfo(shortName = "seq.any.long")
public abstract class SeqAnyLongNode extends Node {

    public abstract boolean execute(LongSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected boolean anyEmpty(LongSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return false;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected boolean anySmall(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedBranchProfile foundProfile) {

        int size = storage.size();
        long[] data = storage.getArray();

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
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedBranchProfile foundProfile) {

        int size = storage.size();
        long[] data = storage.getArray();

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
    private static Object[] prepareArgs(Object[] closureValues, long value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value;
        return args;
    }

    public static SeqAnyLongNode create() {
        return SeqAnyLongNodeGen.create();
    }

    public static SeqAnyLongNode getUncached() {
        return SeqAnyLongNodeGen.getUncached();
    }
}
