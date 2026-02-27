package com.mezze.interpreter.v2.nodes.comparison;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.arithmetic.MezzeArithmeticException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Greater-than comparison node.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = ">")
public abstract class GreaterThanNode extends MezzeNode {

    @Specialization
    protected boolean greaterThanLongs(long left, long right) {
        return left > right;
    }

    @Specialization
    protected boolean greaterThanDoubles(double left, double right) {
        return left > right;
    }

    @Specialization
    @TruffleBoundary
    protected boolean greaterThanStrings(String left, String right) {
        return left.compareTo(right) > 0;
    }

    @Fallback
    protected boolean typeError(Object left, Object right) {
        throw new MezzeArithmeticException(
            "Cannot compare " + left.getClass().getSimpleName() +
            " > " + right.getClass().getSimpleName()
        );
    }
}
