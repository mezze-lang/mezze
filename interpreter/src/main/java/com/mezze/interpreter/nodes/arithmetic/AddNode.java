package com.mezze.interpreter.nodes.arithmetic;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Addition node with proper Truffle DSL specializations.
 *
 * Specialization order (most to least common):
 * 1. long + long (Int/Nat + Int/Nat) - unboxed, with overflow handling
 * 2. double + double (Float + Float) - unboxed
 * 3. String + String - concatenation
 * 4. Fallback - error
 *
 * Nat is represented as primitive long for optimal JIT performance.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "+")
public abstract class AddNode extends MezzeNode {

    /**
     * Fast path: Int/Nat + Int/Nat (unboxed longs).
     * Uses Math.addExact to detect overflow.
     */
    @Specialization(rewriteOn = ArithmeticException.class)
    protected long addLongs(long left, long right) {
        return Math.addExact(left, right);
    }

    /**
     * Overflow fallback: Convert to double on overflow.
     * This replaces the addLongs specialization when overflow occurs.
     */
    @Specialization(replaces = "addLongs")
    protected double addLongsOverflow(long left, long right) {
        return (double) left + (double) right;
    }

    /**
     * Fast path: Float + Float (unboxed doubles).
     */
    @Specialization
    protected double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * String concatenation - uses invokedynamic for efficient StringBuilder.
     */
    @Specialization
    @TruffleBoundary
    protected String addStrings(String left, String right) {
        return left + right;
    }

    /**
     * Mixed: String + anything = String concatenation.
     * TruffleBoundary allows JVM to optimize the string operations.
     */
    @Specialization(guards = "!isString(right)")
    @TruffleBoundary
    protected String addStringLeft(String left, Object right) {
        return left + right.toString();
    }

    /**
     * Mixed: anything + String = String concatenation.
     * TruffleBoundary allows JVM to optimize the string operations.
     */
    @Specialization(guards = "!isString(left)")
    @TruffleBoundary
    protected String addStringRight(Object left, String right) {
        return left.toString() + right;
    }

    /**
     * Fallback: Type error.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new MezzeArithmeticException(
            "Cannot add " + left.getClass().getSimpleName() +
            " and " + right.getClass().getSimpleName()
        );
    }

    @Idempotent
    protected static boolean isString(Object value) {
        return value instanceof String;
    }
}
