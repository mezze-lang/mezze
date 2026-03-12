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
 * Optimized count node for sequences.
 *
 * Counts elements matching a predicate without creating intermediate collection.
 *
 * Uses:
 * - Unboxed int counter
 * - InlinedCountingConditionProfile for branch prediction
 * - @ExplodeLoop for small sequences
 */
@NodeInfo(shortName = "seq.count")
public abstract class SeqCountNode extends Node {

    public abstract long execute(MezzeSeq<?> seq, CallTarget predicate, Object[] closureValues);

    @Specialization(guards = "seq.size() == 0")
    protected long countEmpty(MezzeSeq<?> seq, CallTarget predicate, Object[] closureValues) {
        return 0L;
    }

    @Specialization(guards = {"seq.size() > 0", "seq.size() <= 8"})
    @ExplodeLoop
    protected long countSmall(
            MezzeSeq<?> seq,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = seq.size();
        int count = 0;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, seq.get(i));
            boolean matches = (boolean) callNode.call(args);
            if (matchProfile.profile(this, matches)) {
                count++;
            }
        }

        return count;
    }

    @Specialization(guards = "seq.size() > 8", replaces = "countSmall")
    protected long countLarge(
            MezzeSeq<?> seq,
            CallTarget predicate,
            Object[] closureValues,
            @Cached("create(predicate)") DirectCallNode callNode,
            @Cached InlinedCountingConditionProfile matchProfile) {

        int size = seq.size();
        int count = 0;

        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, seq.get(i));
            boolean matches = (boolean) callNode.call(args);
            if (matchProfile.profile(this, matches)) {
                count++;
            }
        }

        return count;
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, Object value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value;
        return args;
    }

    public static SeqCountNode create() {
        return SeqCountNodeGen.create();
    }

    public static SeqCountNode getUncached() {
        return SeqCountNodeGen.getUncached();
    }
}
