package com.mezze.interpreter.nodes.comparison;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.arithmetic.MezzeArithmeticException;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Greater-than comparison node - OPTIMIZED.
 * Nat is represented as primitive long for optimal JIT performance.
 *
 * Numeric comparisons are fully JIT-compilable.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = ">")
@ReportPolymorphism
public abstract class GreaterThanNode extends MezzeNode {

    /**
     * Fast path: Int/Nat > Int/Nat - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean greaterThanLongs(long left, long right) {
        return left > right;
    }

    /**
     * Fast path: Float > Float - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean greaterThanDoubles(double left, double right) {
        return left > right;
    }

    /**
     * String lexicographic comparison.
     * Identity check is JIT-compilable.
     */
    @Specialization
    protected boolean greaterThanStrings(String left, String right) {
        // Same string cannot be greater than itself
        if (left == right) {
            return false;
        }
        return deepStringCompare(left, right);
    }

    @TruffleBoundary
    private static boolean deepStringCompare(String left, String right) {
        return left.compareTo(right) > 0;
    }

    @Fallback
    protected boolean typeError(Object left, Object right) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new MezzeArithmeticException(
            "Cannot compare " + left.getClass().getSimpleName() +
            " > " + right.getClass().getSimpleName()
        );
    }
}
