package com.mezze.interpreter.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE greater-than comparison - embeds directly into parent nodes.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineGreaterThanNode extends Node {

    public abstract boolean execute(Node node, Object left, Object right);

    @Specialization
    protected static boolean gtLongs(long left, long right) {
        return left > right;
    }

    @Specialization
    protected static boolean gtDoubles(double left, double right) {
        return left > right;
    }

    @Specialization
    protected static boolean gtStrings(String left, String right) {
        return left.compareTo(right) > 0;
    }

    @Fallback
    protected static boolean typeError(Object left, Object right) {
        throw new RuntimeException("Cannot compare " + left.getClass().getSimpleName() +
            " > " + right.getClass().getSimpleName());
    }
}
