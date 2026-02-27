package com.mezze.interpreter.v2.nodes.comparison;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Equality comparison node.
 *
 * Mezze uses structural equality for all values.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "==")
public abstract class EqualNode extends MezzeNode {

    /**
     * Fast path: Int == Int (unboxed longs).
     */
    @Specialization
    protected boolean equalLongs(long left, long right) {
        return left == right;
    }

    /**
     * Fast path: Float == Float (unboxed doubles).
     */
    @Specialization
    protected boolean equalDoubles(double left, double right) {
        return left == right;
    }

    /**
     * Fast path: Bool == Bool (unboxed booleans).
     */
    @Specialization
    protected boolean equalBooleans(boolean left, boolean right) {
        return left == right;
    }

    /**
     * String equality.
     */
    @Specialization
    @TruffleBoundary
    protected boolean equalStrings(String left, String right) {
        return left.equals(right);
    }

    /**
     * Record structural equality.
     */
    @Specialization
    @TruffleBoundary
    protected boolean equalRecords(MezzeRecord left, MezzeRecord right) {
        return left.equals(right);
    }

    /**
     * Variant structural equality.
     */
    @Specialization
    @TruffleBoundary
    protected boolean equalVariants(MezzeVariant left, MezzeVariant right) {
        return left.equals(right);
    }

    /**
     * Fallback: Use Object.equals().
     */
    @Fallback
    @TruffleBoundary
    protected boolean equalObjects(Object left, Object right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        return left.equals(right);
    }
}
