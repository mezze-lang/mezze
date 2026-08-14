package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeSuspend;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct addition node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinAddNode,
 * enabling much faster arithmetic when the typechecker has resolved
 * concrete types.
 *
 * Use this when ResolvedAbility.typeArgs confirms the operand types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-add")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectAddNode extends MezzeNode {

    /** Typed execute methods so callers in tail-call hot loops can read
     *  the result directly without Long/Double boxing through execute(). */
    @Override
    public abstract long executeLong(VirtualFrame frame) throws UnexpectedResultException;

    @Override
    public abstract double executeDouble(VirtualFrame frame) throws UnexpectedResultException;

    /**
     * Fast path: Int + Int (unboxed longs).
     *
     * {@code @ForceQuickening} opts this specialization into BDSL's
     * type-specialized bytecode. Without it, {@code DirectAdd} would only
     * appear in bytecode as the polymorphic op that dispatches through
     * every specialization every iteration — and the {@code LOAD_LOCAL} /
     * {@code STORE_LOCAL} pair around it would fall back to Object slots
     * and re-box every iteration. With the annotation, BDSL emits
     * {@code DirectAdd$Long} + long-typed load/store, keeping the fused
     * fold accumulator on a primitive long slot end-to-end (~2× win on
     * long-heavy loops like sum_fold).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    @ForceQuickening
    public static long addLongs(long left, long right) {
        return Math.addExact(left, right);
    }

    /**
     * Overflow fallback.
     */
    @Specialization(replaces = "addLongs")
    public static double addLongsOverflow(long left, long right) {
        return (double) left + (double) right;
    }

    /**
     * Fast path: Float + Float (unboxed doubles).
     * Also handles mixed long+double due to implicit widening.
     */
    @Specialization(replaces = "addLongsOverflow")
    public static double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * String concatenation. `@TruffleBoundary` — `String + String` in JDK 24+
     * lowers to `StringConcatHelper.simpleConcat`, a blocklisted method under
     * native-image SVM runtime compilation. Boundary keeps the PE reachability
     * clean; string concat is inherently allocating so the boundary is free. #402.
     */
    @Specialization
    @TruffleBoundary
    public static String addStrings(String left, String right) {
        return left + right;
    }

    /**
     * Fraction addition: (a/b) + (c/d) = (a*d + c*b) / (b*d)
     */
    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static MezzeRecord addFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.addFractions(left, right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    /**
     * MezzeSuspend propagation: if either operand is suspended, propagate it.
     * This handles the case where an unhandled effect returns MezzeSuspend
     * and the result flows into arithmetic.
     */
    @Specialization(guards = "isSuspended(left)")
    public static MezzeSuspend addLeftSuspended(MezzeSuspend left, Object right) {
        return left;
    }

    @Specialization(guards = "isSuspended(right)")
    public static MezzeSuspend addRightSuspended(Object left, MezzeSuspend right) {
        return right;
    }

    public static boolean isSuspended(Object value) {
        return value instanceof MezzeSuspend;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    @TruffleBoundary
    public static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot add " + (left == null ? "null" : left.getClass().getSimpleName()) +
            " and " + (right == null ? "null" : right.getClass().getSimpleName())
        );
    }

    public static DirectAddNode create(MezzeNode left, MezzeNode right) {
        return DirectAddNodeGen.create(left, right);
    }
}
