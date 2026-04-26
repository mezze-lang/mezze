package com.mezze.interpreter.nodes.continuation;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

import java.util.List;

/**
 * __native_continuation_get_collected - Get collected values without clearing.
 *
 * Returns the seq of values that have been collected via suspend_and_resume
 * without removing them from the collector.
 *
 * Takes: { delim: MezzeCont }
 * Returns: ['a] (seq of collected values)
 */
@NodeInfo(shortName = "cont-get-collected")
public final class ContinuationGetCollectedNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationGetCollectedNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_get_collected expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_get_collected expects 'delim' field as MezzeCont");
        }

        return getCollected(delim.getId());
    }

    @TruffleBoundary
    private Object getCollected(long delimiterId) {
        DelimiterStack.DelimiterEntry entry = DelimiterStack.find(delimiterId);
        if (entry == null) {
            throw new RuntimeException("No active delimiter found for get_collected");
        }
        List<Object> collected = entry.getCollected();
        return MezzeSeq.fromArray(collected.toArray());
    }

    public static ContinuationGetCollectedNode create(MezzeNode argsNode) {
        return new ContinuationGetCollectedNode(argsNode);
    }
}
