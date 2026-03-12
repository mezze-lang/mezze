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
 * Optimized count node for long sequences.
 *
 * Unboxed version - counts elements matching a predicate.
 *
 * Uses:
 * - Unboxed long[] throughout
 * - Unboxed int counter
 * - InlinedCountingConditionProfile for branch prediction
 */
@NodeInfo(shortName = "seq.count.long")
public abstract class SeqCountLongNode extends Node {

    public abstract long execute(LongSeqStorage storage, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected long countEmpty(LongSeqStorage storage, CallTarget predicate, Object[] closureValues) {
        return 0L;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 8"})
    @ExplodeLoop
    protected long countSmall(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        long[] data = storage.getArray();
        int count = 0;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            boolean matches = (boolean) callNode.call(args);
            if (matchProfile.profile(this, matches)) {
                count++;
            }
        }

        return count;
    }

    @Specialization(guards = "storage.size() > 8", replaces = "countSmall")
    protected long countLarge(
            LongSeqStorage storage,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = storage.size();
        long[] data = storage.getArray();
        int count = 0;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            boolean matches = (boolean) callNode.call(args);
            if (matchProfile.profile(this, matches)) {
                count++;
            }
        }

        return count;
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

    public static SeqCountLongNode create() {
        return SeqCountLongNodeGen.create();
    }

    public static SeqCountLongNode getUncached() {
        return SeqCountLongNodeGen.getUncached();
    }
}
