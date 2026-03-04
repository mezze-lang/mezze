package com.mezze.interpreter.v2.nodes.arithmetic;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Division node with proper Truffle DSL specializations.
 * Nat is represented as primitive long for optimal JIT performance.
 *
 * Note: Integer division in Mezze produces a Float result,
 * matching mathematical semantics (5 / 2 = 2.5, not 2).
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "/")
public abstract class DivideNode extends MezzeNode {

    /**
     * Int/Nat / Int/Nat produces Float (mathematical division).
     */
    @Specialization
    protected double divideLongs(long left, long right) {
        if (right == 0) {
            throw new MezzeArithmeticException("Division by zero");
        }
        return (double) left / (double) right;
    }

    /**
     * Float / Float (unboxed doubles).
     */
    @Specialization
    protected double divideDoubles(double left, double right) {
        if (right == 0.0) {
            throw new MezzeArithmeticException("Division by zero");
        }
        return left / right;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new MezzeArithmeticException(
            "Cannot divide " + left.getClass().getSimpleName() +
            " by " + right.getClass().getSimpleName()
        );
    }
}
