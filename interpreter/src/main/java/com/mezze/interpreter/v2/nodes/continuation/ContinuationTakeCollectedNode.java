package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

import java.util.List;

/**
 * __native_continuation_take_collected - Get and clear collected values.
 *
 * Returns the seq of values that have been collected via suspend_and_resume
 * and clears the collector for reuse.
 *
 * Takes: { delim: MezzeCont }
 * Returns: ['a] (seq of collected values)
 */
@NodeInfo(shortName = "cont-take-collected")
public final class ContinuationTakeCollectedNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationTakeCollectedNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_take_collected expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_take_collected expects 'delim' field as MezzeCont");
        }

        return takeCollected(delim.getId());
    }

    @TruffleBoundary
    private Object takeCollected(long delimiterId) {
        DelimiterStack.DelimiterEntry entry = DelimiterStack.find(delimiterId);
        if (entry == null) {
            throw new RuntimeException("No active delimiter found for take_collected");
        }
        List<Object> collected = entry.takeCollected();
        return MezzeSeq.fromArray(collected.toArray());
    }

    public static ContinuationTakeCollectedNode create(MezzeNode argsNode) {
        return new ContinuationTakeCollectedNode(argsNode);
    }
}
