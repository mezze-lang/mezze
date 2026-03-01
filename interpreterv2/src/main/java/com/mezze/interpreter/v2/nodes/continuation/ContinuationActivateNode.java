package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_activate - Pushes a delimiter onto the thread-local stack.
 *
 * This makes the delimiter "active" - subsequent continuation operations
 * will use this delimiter as their scope boundary.
 *
 * Takes: { delim: MezzeCont }
 * Returns: {} (unit)
 */
@NodeInfo(shortName = "cont-activate")
public final class ContinuationActivateNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationActivateNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_activate expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_activate expects 'delim' field as MezzeCont");
        }

        // Push delimiter onto stack
        DelimiterStack.push(delim);

        return MezzeUnit.INSTANCE;
    }

    public static ContinuationActivateNode create(MezzeNode argsNode) {
        return new ContinuationActivateNode(argsNode);
    }
}
