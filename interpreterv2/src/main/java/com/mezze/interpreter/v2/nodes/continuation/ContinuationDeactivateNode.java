package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_deactivate - Pops a delimiter from the stack.
 *
 * This is the cleanup primitive that marks the end of a delimiter scope.
 * Should be called when done using a continuation to prevent stack leaks.
 *
 * Takes: { delim: MezzeCont }
 * Returns: {} (unit)
 */
@NodeInfo(shortName = "cont-deactivate")
public final class ContinuationDeactivateNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationDeactivateNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_deactivate expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_deactivate expects 'delim' field as MezzeCont");
        }

        // Pop the delimiter from the stack
        DelimiterStack.pop();

        return MezzeUnit.INSTANCE;
    }

    public static ContinuationDeactivateNode create(MezzeNode argsNode) {
        return new ContinuationDeactivateNode(argsNode);
    }
}
