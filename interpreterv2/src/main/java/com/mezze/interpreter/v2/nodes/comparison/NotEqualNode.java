package com.mezze.interpreter.v2.nodes.comparison;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Inequality comparison node - OPTIMIZED.
 *
 * Same optimization strategy as EqualNode:
 * - Primitive comparisons are fully JIT-compilable
 * - Identity checks first for complex types
 * - @TruffleBoundary only for deep comparisons
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "!=")
@ReportPolymorphism
public abstract class NotEqualNode extends MezzeNode {

    /**
     * Fast path: Int != Int - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean notEqualLongs(long left, long right) {
        return left != right;
    }

    /**
     * Fast path: Float != Float - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean notEqualDoubles(double left, double right) {
        return left != right;
    }

    /**
     * Fast path: Bool != Bool - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean notEqualBooleans(boolean left, boolean right) {
        return left != right;
    }

    /**
     * String inequality - identity check first.
     */
    @Specialization
    protected boolean notEqualStrings(String left, String right) {
        // Identity check (same interned string = equal, so not equal is false)
        if (left == right) {
            return false;
        }
        return !deepStringEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepStringEquals(String left, String right) {
        return left.equals(right);
    }

    /**
     * Record inequality - identity check first.
     */
    @Specialization
    protected boolean notEqualRecords(MezzeRecord left, MezzeRecord right) {
        if (left == right) {
            return false;
        }
        return !deepRecordEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepRecordEquals(MezzeRecord left, MezzeRecord right) {
        return left.equals(right);
    }

    /**
     * Variant inequality - tag check first.
     */
    @Specialization
    protected boolean notEqualVariants(MezzeVariant left, MezzeVariant right) {
        if (left == right) {
            return false;
        }
        // Fast path: different tags mean not equal
        if (left.getTag() != right.getTag()) {
            return true;
        }
        return !deepVariantEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepVariantEquals(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    /**
     * Fallback for other types.
     */
    @Fallback
    protected boolean notEqualObjects(Object left, Object right) {
        if (left == right) {
            return false;
        }
        if (left == null || right == null) {
            return true;
        }
        return !deepObjectEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepObjectEquals(Object left, Object right) {
        return left.equals(right);
    }
}
