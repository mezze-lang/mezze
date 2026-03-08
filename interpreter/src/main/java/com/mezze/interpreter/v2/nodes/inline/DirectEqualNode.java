package com.mezze.interpreter.v2.nodes.inline;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

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
public abstract class DirectEqualNode extends MezzeNode {

    @Specialization
    protected static boolean eqLongs(long left, long right) {
        return left == right;
    }

    @Specialization
    protected static boolean eqDoubles(double left, double right) {
        return left == right;
    }

    @Specialization
    protected static boolean eqBooleans(boolean left, boolean right) {
        return left == right;
    }

    @Specialization
    protected static boolean eqStrings(String left, String right) {
        return left.equals(right);
    }

    @Specialization
    protected static boolean eqRecords(MezzeRecord left, MezzeRecord right) {
        return left.equals(right);
    }

    @Specialization
    protected static boolean eqVariants(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    @Fallback
    protected static boolean eqGeneric(Object left, Object right) {
        return Objects.equals(left, right);
    }

    public static DirectEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectEqualNodeGen.create(left, right);
    }
}
