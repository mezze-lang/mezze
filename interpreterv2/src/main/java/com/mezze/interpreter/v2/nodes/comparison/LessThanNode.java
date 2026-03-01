package com.mezze.interpreter.v2.nodes.comparison;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.arithmetic.MezzeArithmeticException;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Less-than comparison node - OPTIMIZED.
 *
 * Numeric comparisons are fully JIT-compilable.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "<")
@ReportPolymorphism
public abstract class LessThanNode extends MezzeNode {

    /**
     * Fast path: Int < Int - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean lessThanLongs(long left, long right) {
        return left < right;
    }

    /**
     * Fast path: Float < Float - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean lessThanDoubles(double left, double right) {
        return left < right;
    }

    /**
     * String lexicographic comparison.
     * Identity check is JIT-compilable (same string = not less than).
     */
    @Specialization
    protected boolean lessThanStrings(String left, String right) {
        // Same string cannot be less than itself
        if (left == right) {
            return false;
        }
        return deepStringCompare(left, right);
    }

    @TruffleBoundary
    private static boolean deepStringCompare(String left, String right) {
        return left.compareTo(right) < 0;
    }

    @Fallback
    protected boolean typeError(Object left, Object right) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new MezzeArithmeticException(
            "Cannot compare " + left.getClass().getSimpleName() +
            " < " + right.getClass().getSimpleName()
        );
    }
}
