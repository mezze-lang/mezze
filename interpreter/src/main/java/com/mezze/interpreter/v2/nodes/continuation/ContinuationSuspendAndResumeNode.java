package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_suspend_and_resume - Collect value and auto-resume.
 *
 * Unlike suspend_with which escapes to the handler, this:
 * 1. Adds the value to the delimiter's internal collector
 * 2. Returns immediately, allowing execution to continue
 *
 * This is the key primitive for Generator yield - collect values
 * without escaping, then retrieve them all at the end.
 *
 * Takes: { delim: MezzeCont, value: 'a }
 * Returns: {} (unit) - execution continues after this call
 */
@NodeInfo(shortName = "cont-suspend-and-resume")
public final class ContinuationSuspendAndResumeNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationSuspendAndResumeNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_suspend_and_resume expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_suspend_and_resume expects 'delim' field as MezzeCont");
        }

        Object value = record.getField("value");
        if (value == null) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_suspend_and_resume expects 'value' field");
        }

        // Add value to the delimiter's collector
        addToCollector(delim.getId(), value);

        // Return unit - execution continues
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    private void addToCollector(long delimiterId, Object value) {
        DelimiterStack.DelimiterEntry entry = DelimiterStack.find(delimiterId);
        if (entry == null) {
            throw new RuntimeException("No active delimiter found for suspend_and_resume");
        }
        entry.addCollected(value);
    }

    public static ContinuationSuspendAndResumeNode create(MezzeNode argsNode) {
        return new ContinuationSuspendAndResumeNode(argsNode);
    }
}
