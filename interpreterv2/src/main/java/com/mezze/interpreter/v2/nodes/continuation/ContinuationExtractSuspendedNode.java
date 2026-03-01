package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeContinuation;
import com.mezze.interpreter.v2.values.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_extract_suspended - Extract value and continuation from suspended state.
 *
 * Takes: { delim: MezzeCont, suspended: 'a }
 * Returns: Option { value: 'v, cont: ({ value: 'a } -> 'b) }
 *          - Some { val = { value, cont } } if suspended is MezzeContinuationSuspended
 *          - None otherwise
 */
@NodeInfo(shortName = "cont-extract-suspended")
public final class ContinuationExtractSuspendedNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile suspendedProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile notSuspendedProfile = InlinedBranchProfile.getUncached();

    public ContinuationExtractSuspendedNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_extract_suspended expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_extract_suspended expects 'delim' field");
        }

        Object suspended = record.getField("suspended");

        if (suspended instanceof MezzeContinuationSuspended susp) {
            suspendedProfile.enter(this);
            return buildSomeResult(susp);
        }

        // Not suspended - return None
        notSuspendedProfile.enter(this);
        return MezzeVariant.none();
    }

    @TruffleBoundary
    private MezzeVariant buildSomeResult(MezzeContinuationSuspended susp) {
        Object value = susp.getSuspendedValue();
        MezzeContinuation cont = susp.getContinuation();

        // Wrap continuation as function
        MezzeFunction contFunc = new MezzeFunction(
                cont.getCallTarget(),
                "extracted_continuation",
                cont.getCapturedValues()
        );

        MezzeRecord innerRecord = MezzeRecord.fromFields(
                new String[]{"value", "cont"},
                new Object[]{value, contFunc}
        );

        return MezzeVariant.some(innerRecord);
    }

    public static ContinuationExtractSuspendedNode create(MezzeNode argsNode) {
        return new ContinuationExtractSuspendedNode(argsNode);
    }
}
