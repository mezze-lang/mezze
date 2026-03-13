package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_collected_count - Get count of collected values.
 *
 * Returns the number of values currently in the collector.
 * Useful for checking if any values have been yielded without retrieving them.
 *
 * Takes: { delim: MezzeCont }
 * Returns: Nat (count of collected values)
 */
@NodeInfo(shortName = "cont-collected-count")
public final class ContinuationCollectedCountNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationCollectedCountNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_collected_count expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_collected_count expects 'delim' field as MezzeCont");
        }

        return getCollectedCount(delim.getId());
    }

    @TruffleBoundary
    private long getCollectedCount(long delimiterId) {
        DelimiterStack.DelimiterEntry entry = DelimiterStack.find(delimiterId);
        if (entry == null) {
            throw new RuntimeException("No active delimiter found for collected_count");
        }
        return entry.collectedCount();
    }

    public static ContinuationCollectedCountNode create(MezzeNode argsNode) {
        return new ContinuationCollectedCountNode(argsNode);
    }
}
