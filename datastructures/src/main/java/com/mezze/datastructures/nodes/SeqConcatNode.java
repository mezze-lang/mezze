package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for concatenating two sequences.
 *
 * Preserves storage type when both operands are the same type.
 */
@NodeInfo(shortName = "seq.concat")
public final class SeqConcatNode extends Node {

    public SeqStorage execute(SeqStorage left, SeqStorage right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return executeConcat(left, right);
    }

    @TruffleBoundary
    private SeqStorage executeConcat(SeqStorage left, SeqStorage right) {
        // Fast path for same types
        if (left instanceof LongSeqStorage ls && right instanceof LongSeqStorage rs) {
            return ls.concatLong(rs);
        }
        if (left instanceof DoubleSeqStorage ld && right instanceof DoubleSeqStorage rd) {
            return ld.concatDouble(rd);
        }
        return left.concat(right);
    }

    public static SeqConcatNode create() {
        return new SeqConcatNode();
    }
}
