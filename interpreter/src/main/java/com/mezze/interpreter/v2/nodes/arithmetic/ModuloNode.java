package com.mezze.interpreter.v2.nodes.arithmetic;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Modulo node with proper Truffle DSL specializations.
 * Nat is represented as primitive long for optimal JIT performance.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "%")
public abstract class ModuloNode extends MezzeNode {

    /**
     * Int/Nat % Int/Nat (unboxed longs).
     */
    @Specialization
    protected long moduloLongs(long left, long right) {
        if (right == 0) {
            throw new MezzeArithmeticException("Modulo by zero");
        }
        return left % right;
    }

    /**
     * Float % Float (unboxed doubles).
     */
    @Specialization
    protected double moduloDoubles(double left, double right) {
        if (right == 0.0) {
            throw new MezzeArithmeticException("Modulo by zero");
        }
        return left % right;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new MezzeArithmeticException(
            "Cannot compute modulo of " + left.getClass().getSimpleName() +
            " and " + right.getClass().getSimpleName()
        );
    }
}
