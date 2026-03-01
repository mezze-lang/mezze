package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_abort - Aborts the current continuation.
 *
 * Throws a ContinuationAbortedException to terminate the current
 * computation path. Effect handlers should catch this and handle
 * it appropriately.
 *
 * Takes: { delim: MezzeCont }
 * Returns: never (throws)
 */
@NodeInfo(shortName = "cont-abort")
public final class ContinuationAbortNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationAbortNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_abort expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_abort expects 'delim' field as MezzeCont");
        }

        // Throw abort exception
        throw new ContinuationAbortedException("Continuation aborted for delimiter " + delim.getId());
    }

    public static ContinuationAbortNode create(MezzeNode argsNode) {
        return new ContinuationAbortNode(argsNode);
    }
}
