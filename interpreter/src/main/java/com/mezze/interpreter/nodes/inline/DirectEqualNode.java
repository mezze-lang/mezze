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
 * Direct equality node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinEqNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-eq")
@OperationProxy.Proxyable(allowUncached = true)
public abstract class DirectEqualNode extends MezzeNode {

    @Specialization
    public static boolean eqLongs(long left, long right) {
        return left == right;
    }

    @Specialization
    public static boolean eqDoubles(double left, double right) {
        return left == right;
    }

    @Specialization
    public static boolean eqBooleans(boolean left, boolean right) {
        return left == right;
    }

    @Specialization
    public static boolean eqStrings(String left, String right) {
        return left.equals(right);
    }

    @Specialization(guards = {"isFrac(left)", "isFrac(right)"})
    public static boolean eqFractions(MezzeRecord left, MezzeRecord right) {
        return FractionHelper.equalFractions(left, right);
    }

    /**
     * `@TruffleBoundary` on the record-eq fallback: {@link MezzeRecord#equals}
     * reaches user data-structure equals implementations (MezzeMap, MezzeSet)
     * whose hashCode paths call {@link System#identityHashCode}, which is
     * blocklisted from runtime compilation in native-image. Without the
     * boundary, {@code mez build --native} fails with "Blocklisted methods
     * are reachable for runtime compilation". See #402.
     */
    @Specialization(replaces = "eqFractions")
    @TruffleBoundary
    public static boolean eqRecords(MezzeRecord left, MezzeRecord right) {
        // Check for fractions even in generic path
        if (FractionHelper.isFraction(left) && FractionHelper.isFraction(right)) {
            return FractionHelper.equalFractions(left, right);
        }
        return left.equals(right);
    }

    public static boolean isFrac(Object value) {
        return FractionHelper.isFraction(value);
    }

    /**
     * `@TruffleBoundary` for the same reason as {@link #eqRecords} —
     * {@link MezzeVariant#equals} can reach payload records whose eq
     * chains hit blocklisted identity-hashCode methods. See #402.
     */
    @Specialization
    @TruffleBoundary
    public static boolean eqVariants(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    /**
     * Generic-object fallback — reaches arbitrary {@code .equals} through
     * {@link Objects#equals}. Boundary keeps native-image build valid; the
     * fallback is cold anyway (the specialised paths above handle every
     * common shape). See #402.
     */
    @Fallback
    @TruffleBoundary
    public static boolean eqGeneric(Object left, Object right) {
        return Objects.equals(left, right);
    }

    public static DirectEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectEqualNodeGen.create(left, right);
    }
}
