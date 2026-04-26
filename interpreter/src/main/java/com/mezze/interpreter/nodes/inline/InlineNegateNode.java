package com.mezze.interpreter.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE negation node - embeds directly into parent nodes.
 *
 * This is a pure computation node without @NodeChild.
 * Use this when you want to inline negation into a parent node.
 *
 * Example usage in parent node:
 * {@code
 * @Specialization
 * protected Object doNegate(Object value,
 *     @Cached InlineNegateNode negateNode) {
 *     return negateNode.execute(this, value);
 * }
 * }
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineNegateNode extends Node {

    public abstract Object execute(Node node, Object value);

    /**
     * Negate Int (unboxed long).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long negateLong(long value) {
        return Math.negateExact(value);
    }

    /**
     * Overflow fallback for Long.MIN_VALUE.
     */
    @Specialization(replaces = "negateLong")
    protected static double negateLongOverflow(long value) {
        return -(double) value;
    }

    /**
     * Negate Float (unboxed double).
     */
    @Specialization
    protected static double negateDouble(double value) {
        return -value;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected static Object typeError(Object value) {
        throw new RuntimeException("Cannot negate " + value.getClass().getSimpleName());
    }
}
