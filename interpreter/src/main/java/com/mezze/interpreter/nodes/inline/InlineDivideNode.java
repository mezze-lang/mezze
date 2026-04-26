package com.mezze.interpreter.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE division node - embeds directly into parent nodes.
 * Always returns double for division to handle fractional results.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineDivideNode extends Node {

    public abstract Object execute(Node node, Object left, Object right);

    @Specialization
    protected static double divLongs(long left, long right) {
        return (double) left / (double) right;
    }

    @Specialization
    protected static double divDoubles(double left, double right) {
        return left / right;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException("Cannot divide " + left.getClass().getSimpleName() +
            " by " + right.getClass().getSimpleName());
    }
}
