package com.mezze.interpreter.v2.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE addition node - embeds directly into parent nodes.
 *
 * This is a pure computation node without @NodeChild.
 * Use this when you want to inline addition into a parent node,
 * such as in loop bodies or hot paths.
 *
 * Example usage:
 * {@code
 * @Specialization
 * protected Object doAdd(Object left, Object right,
 *     @Cached InlineAddNode addNode) {
 *     return addNode.execute(this, left, right);
 * }
 * }
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineAddNode extends Node {

    public abstract Object execute(Node node, Object left, Object right);

    /**
     * Fast path: Int + Int (unboxed longs).
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected static long addLongs(long left, long right) {
        return Math.addExact(left, right);
    }

    /**
     * Overflow fallback.
     */
    @Specialization(replaces = "addLongs")
    protected static double addLongsOverflow(long left, long right) {
        return (double) left + (double) right;
    }

    /**
     * Fast path: Float + Float (unboxed doubles).
     */
    @Specialization
    protected static double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * String concatenation.
     */
    @Specialization
    protected static String addStrings(String left, String right) {
        return left + right;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException(
            "Cannot add " + left.getClass().getSimpleName() +
            " and " + right.getClass().getSimpleName()
        );
    }
}
