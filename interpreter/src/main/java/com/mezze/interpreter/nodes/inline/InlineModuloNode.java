package com.mezze.interpreter.nodes.inline;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE modulo node - embeds directly into parent nodes.
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineModuloNode extends Node {

    public abstract Object execute(Node node, Object left, Object right);

    @Specialization
    protected static long modLongs(long left, long right) {
        return left % right;
    }

    @Specialization
    protected static double modDoubles(double left, double right) {
        return left % right;
    }

    @Fallback
    protected static Object typeError(Object left, Object right) {
        throw new RuntimeException("Cannot modulo " + left.getClass().getSimpleName() +
            " by " + right.getClass().getSimpleName());
    }
}
