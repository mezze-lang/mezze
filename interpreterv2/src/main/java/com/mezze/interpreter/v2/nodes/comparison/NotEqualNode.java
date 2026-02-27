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
 * Inequality comparison node.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "!=")
public abstract class NotEqualNode extends MezzeNode {

    @Specialization
    protected boolean notEqualLongs(long left, long right) {
        return left != right;
    }

    @Specialization
    protected boolean notEqualDoubles(double left, double right) {
        return left != right;
    }

    @Specialization
    protected boolean notEqualBooleans(boolean left, boolean right) {
        return left != right;
    }

    @Specialization
    @TruffleBoundary
    protected boolean notEqualStrings(String left, String right) {
        return !left.equals(right);
    }

    @Specialization
    @TruffleBoundary
    protected boolean notEqualRecords(MezzeRecord left, MezzeRecord right) {
        return !left.equals(right);
    }

    @Specialization
    @TruffleBoundary
    protected boolean notEqualVariants(MezzeVariant left, MezzeVariant right) {
        return !left.equals(right);
    }

    @Fallback
    @TruffleBoundary
    protected boolean notEqualObjects(Object left, Object right) {
        if (left == null && right == null) return false;
        if (left == null || right == null) return true;
        return !left.equals(right);
    }
}
