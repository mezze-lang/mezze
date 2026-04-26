package com.mezze.interpreter.nodes.comparison;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Equality comparison node - OPTIMIZED.
 * Nat is represented as primitive long for optimal JIT performance.
 *
 * Mezze uses structural equality for all values.
 * Primitive comparisons (long, double, boolean) are fully JIT-compilable.
 * Complex comparisons (strings, records, variants) use @TruffleBoundary
 * only as a last resort after identity checks.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "==")
@ReportPolymorphism
public abstract class EqualNode extends MezzeNode {

    /**
     * Fast path: Int/Nat == Int/Nat (unboxed longs) - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean equalLongs(long left, long right) {
        return left == right;
    }

    /**
     * Fast path: Float == Float (unboxed doubles) - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean equalDoubles(double left, double right) {
        return left == right;
    }

    /**
     * Fast path: Bool == Bool (unboxed booleans) - FULLY JIT-COMPILABLE.
     */
    @Specialization
    protected boolean equalBooleans(boolean left, boolean right) {
        return left == right;
    }

    /**
     * String equality - identity check first, then deep equals.
     * Identity check is JIT-compilable; deep equals only if needed.
     */
    @Specialization
    protected boolean equalStrings(String left, String right) {
        // Identity check (common case: same interned string)
        if (left == right) {
            return true;
        }
        // Deep comparison
        return deepStringEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepStringEquals(String left, String right) {
        return left.equals(right);
    }

    /**
     * Record structural equality - identity check first.
     * Special case for fractions: use mathematical equality.
     */
    @Specialization
    protected boolean equalRecords(MezzeRecord left, MezzeRecord right) {
        // Identity check (same record instance)
        if (left == right) {
            return true;
        }
        // Deep structural comparison
        return deepRecordEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepRecordEquals(MezzeRecord left, MezzeRecord right) {
        // Check if both are fractions - if so, use mathematical equality
        if (isFraction(left) && isFraction(right)) {
            long ln = (Long) left.getField("numerator");
            long ld = (Long) left.getField("denominator");
            long rn = (Long) right.getField("numerator");
            long rd = (Long) right.getField("denominator");
            // a/b == c/d iff a*d == b*c
            return ln * rd == ld * rn;
        }
        return left.equals(right);
    }

    private static boolean isFraction(MezzeRecord record) {
        return record.getFieldIndex("numerator") >= 0 && record.getFieldIndex("denominator") >= 0
            && record.getFieldCount() == 2;
    }

    /**
     * Variant structural equality - tag check first.
     */
    @Specialization
    protected boolean equalVariants(MezzeVariant left, MezzeVariant right) {
        // Identity check
        if (left == right) {
            return true;
        }
        // Tag check (fast reject if different tags)
        if (left.getTag() != right.getTag()) {
            return false;
        }
        // Deep payload comparison
        return deepVariantEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepVariantEquals(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    /**
     * Fallback for other types.
     */
    @Fallback
    protected boolean equalObjects(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return deepObjectEquals(left, right);
    }

    @TruffleBoundary
    private static boolean deepObjectEquals(Object left, Object right) {
        return left.equals(right);
    }
}
