package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.OperationProxy;

/**
 * Direct less-than node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinLtNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-lt")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectLessThanNode extends MezzeNode {

    /** {@code @ForceQuickening} — see DirectAddNode.addLongs for rationale.
     *  Needed so the fused-fold loop-bound check stays on primitive-long
     *  bytecode (LOAD_LOCAL$Long i, LOAD_LOCAL$Long end, DirectLessThan$Long). */
    @Specialization
    @ForceQuickening
    public static boolean ltLongs(long left, long right) {
        return left < right;
    }

    /**
     * Float comparison. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "ltLongs")
    public static boolean ltDoubles(double left, double right) {
        return left < right;
    }

    @Specialization
    public static boolean ltStrings(String left, String right) {
        return left.compareTo(right) < 0;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static boolean ltFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.compareFractions(left, right) < 0;
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot compare " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " < " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectLessThanNode create(MezzeNode left, MezzeNode right) {
        return DirectLessThanNodeGen.create(left, right);
    }
}
