package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;

/**
 * Direct negation node that takes the operand directly.
 *
 * This bypasses the {val} record allocation used by BuiltinNegNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "operand", type = MezzeNode.class)
@NodeInfo(shortName = "direct-neg")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectNegateNode extends MezzeNode {

    /**
     * Overflow THROWS, it does not widen. See `DirectAddNode` for the full reasoning
     * and for why this is a try/catch rather than a throwing `replaces` fallback.
     *
     * Only one input can reach it: `Int`'s minimum has no positive counterpart in a
     * 64-bit signed range.
     */
    @Specialization
    @ForceQuickening
    public static long negateLong(long value) {
        try {
            return Math.negateExact(value);
        } catch (ArithmeticException overflow) {
            throw MezzeGuestException.arithmeticOverflow("Negation", value);
        }
    }

    @Specialization
    public static double negateDouble(double value) {
        return -value;
    }

    @Specialization(guards = "isFrac(value)")
    public static MezzeRecord negateFraction(MezzeRecord value) {
        return FractionHelper.negateFraction(value);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object value) {
        throw new RuntimeException(
            "Cannot negate " + (value == null ? "null" : value.getClass().getSimpleName())
        );
    }

    public static DirectNegateNode create(MezzeNode operand) {
        return DirectNegateNodeGen.create(operand);
    }
}
