package com.mezze.interpreter.v2.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE less-than comparison - embeds directly into parent nodes.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineLessThanNode extends Node {

    public abstract boolean execute(Node node, Object left, Object right);

    @Specialization
    protected static boolean ltLongs(long left, long right) {
        return left < right;
    }

    @Specialization
    protected static boolean ltDoubles(double left, double right) {
        return left < right;
    }

    @Specialization
    protected static boolean ltStrings(String left, String right) {
        return left.compareTo(right) < 0;
    }

    @Fallback
    protected static boolean typeError(Object left, Object right) {
        throw new RuntimeException("Cannot compare " + left.getClass().getSimpleName() +
            " < " + right.getClass().getSimpleName());
    }
}
