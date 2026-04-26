package com.mezze.interpreter.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE subtraction node - embeds directly into parent nodes.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineSubtractNode extends Node {

    public abstract Object execute(Node node, Object left, Object right);

    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long subLongs(long left, long right) {
        return Math.subtractExact(left, right);
    }

    @Specialization(replaces = "subLongs")
    protected static double subLongsOverflow(long left, long right) {
        return (double) left - (double) right;
    }

    @Specialization
    protected static double subDoubles(double left, double right) {
        return left - right;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException("Cannot subtract " + right.getClass().getSimpleName() +
            " from " + left.getClass().getSimpleName());
    }
}
