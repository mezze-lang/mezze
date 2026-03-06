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
 * Direct not-equal node that takes left and right operands directly.
 *
 * This bypasses the {l, r} record allocation used by BuiltinNeqNode,
 * enabling much faster comparison when the typechecker has resolved
 * concrete types.
 */
@NodeChild(value = "left", type = MezzeNode.class)
@NodeChild(value = "right", type = MezzeNode.class)
@NodeInfo(shortName = "direct-neq")
public abstract class DirectNotEqualNode extends MezzeNode {

    @Specialization
    protected static boolean neqLongs(long left, long right) {
        return left != right;
    }

    @Specialization
    protected static boolean neqDoubles(double left, double right) {
        return left != right;
    }

    @Specialization
    protected static boolean neqBooleans(boolean left, boolean right) {
        return left != right;
    }

    @Specialization
    protected static boolean neqStrings(String left, String right) {
        return !left.equals(right);
    }

    @Specialization
    protected static boolean neqRecords(MezzeRecord left, MezzeRecord right) {
        return !left.equals(right);
    }

    @Specialization
    protected static boolean neqVariants(MezzeVariant left, MezzeVariant right) {
        return !left.equals(right);
    }

    @Fallback
    protected static boolean neqGeneric(Object left, Object right) {
        return !Objects.equals(left, right);
    }

    public static DirectNotEqualNode create(MezzeNode left, MezzeNode right) {
        return DirectNotEqualNodeGen.create(left, right);
    }
}
