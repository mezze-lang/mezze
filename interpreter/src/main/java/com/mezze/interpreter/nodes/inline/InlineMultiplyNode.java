package com.mezze.interpreter.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE multiplication node - embeds directly into parent nodes.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineMultiplyNode extends Node {

    public abstract Object execute(Node node, Object left, Object right);

    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long mulLongs(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    @Specialization(replaces = "mulLongs")
    protected static double mulLongsOverflow(long left, long right) {
        return (double) left * (double) right;
    }

    @Specialization
    protected static double mulDoubles(double left, double right) {
        return left * right;
    }

    @Specialization
    protected static String mulStringLong(String left, long right) {
        if (right <= 0) return "";
        if (right == 1) return left;
        return left.repeat((int) right);
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException("Cannot multiply " + left.getClass().getSimpleName() +
            " and " + right.getClass().getSimpleName());
    }
}
