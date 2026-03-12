package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.dsl.Cached;

/**
 * Extremely optimized integer division node.
 *
 * Uses branch profiling for division-by-zero check.
 * JIT can eliminate the check if never taken.
 */
@NodeInfo(shortName = "int.div")
public abstract class IntDivNode extends Node {

    public abstract long execute(long left, long right);

    @Specialization
    protected long divide(long left, long right,
            @Cached InlinedBranchProfile zeroDivisorProfile) {
        if (right == 0) {
            zeroDivisorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new ArithmeticException("Division by zero");
        }
        return left / right;
    }

    public static IntDivNode create() {
        return IntDivNodeGen.create();
    }

    public static IntDivNode getUncached() {
        return IntDivNodeGen.getUncached();
    }
}
