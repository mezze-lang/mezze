package com.mezze.interpreter.nodes.inline;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.bytecode.OperationProxy;

import java.util.Objects;

/**
 * Direct not-equal node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinNeqNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-neq")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectNotEqualNode extends MezzeNode {

    @Specialization
    public static boolean neqLongs(long left, long right) {
        return left != right;
    }

    @Specialization
    public static boolean neqDoubles(double left, double right) {
        return left != right;
    }

    @Specialization
    public static boolean neqBooleans(boolean left, boolean right) {
        return left != right;
    }

    @Specialization
    public static boolean neqStrings(String left, String right) {
        return !left.equals(right);
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static boolean neqFractions(MezzeRecord left, MezzeRecord right) {
        return !FractionHelper.equalFractions(left, right);
    }

    /** `@TruffleBoundary` — see {@link DirectEqualNode#eqRecords} for the
     *  identity-hashCode blocklist rationale. #402. */
    @Specialization(replaces = "neqFractions")
    @TruffleBoundary
    public static boolean neqRecords(MezzeRecord left, MezzeRecord right) {
        if (FractionHelper.isFraction(left) && FractionHelper.isFraction(right)) {
            return !FractionHelper.equalFractions(left, right);
        }
        return !left.equals(right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    /** `@TruffleBoundary` — see {@link DirectEqualNode#eqVariants}. #402. */
    @Specialization
    @TruffleBoundary
    public static boolean neqVariants(MezzeVariant left, MezzeVariant right) {
        return !left.equals(right);
    }

    /** `@TruffleBoundary` — see {@link DirectEqualNode#eqGeneric}. #402. */
    @Fallback
    @TruffleBoundary
    public static boolean neqGeneric(Object left, Object right) {
        return !Objects.equals(left, right);
    }

    public static DirectNotEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectNotEqualNodeGen.create(left, right);
    }
}
