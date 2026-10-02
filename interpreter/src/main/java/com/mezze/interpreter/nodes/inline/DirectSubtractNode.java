package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct subtraction node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinSubNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-sub")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectSubtractNode extends MezzeNode {

    /** Typed execute methods so callers in tail-call hot loops can read
     *  the result without boxing through execute(). */
    @Override
    public abstract long executeLong(VirtualFrame frame) throws UnexpectedResultException;

    @Override
    public abstract double executeDouble(VirtualFrame frame) throws UnexpectedResultException;

    /**
     * Overflow THROWS, it does not widen.
     *
     * This used to answer `(double) left - (double) right`. `Int` is a 64-bit
     * signed long, so a result outside that range has no representable `Int` — and
     * returning a Double made a function declared `-> Int` produce one. Unsound
     * rather than imprecise: the Double flowed through comparisons unnoticed and blew
     * up only where something cast it back, which is why it surfaced as
     * `ClassCastException: Double cannot be cast to Long` from the VALUE RENDERER,
     * nowhere near the arithmetic. See S-6.
     *
     * Fatal and uncatchable, exactly as `/ 0` is — the same kind of situation, an
     * operation with no answer in the type, so it fails the same way.
     *
     * ## Why a try/catch and not `rewriteOn` + a throwing fallback
     *
     * The obvious edit — leave `rewriteOn = ArithmeticException.class` and make the
     * overflow specialization throw — carries `replaces = "subLongs"`, so the FIRST
     * overflow at a site would remove the long specialization and every later
     * addition there would take the throwing one. `3 + 4` after one overflow would
     * fail. Catching here means there is never a state in which the long path has
     * been replaced.
     */
    @Specialization
    @ForceQuickening
    public static long subLongs(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            throw MezzeGuestException.arithmeticOverflow("Subtraction", left, right);
        }
    }

    /**
     * Float subtraction. Also handles mixed types due to implicit widening.
     */
    @Specialization(replaces = "subLongs")
    public static double subDoubles(double left, double right) {
        return left - right;
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static MezzeRecord subFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.subtractFractions(left, right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot subtract " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectSubtractNode create(MezzeNode left, MezzeNode right) {
        return DirectSubtractNodeGen.create(left, right);
    }
}
