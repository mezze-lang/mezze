package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeContinuation;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_capture - Captures the current delimited continuation.
 *
 * This captures "the rest of the computation" up to the delimiter boundary
 * as a function that can be resumed later.
 *
 * The captured continuation is returned as a MezzeFunction that takes
 * { value: 'a } and returns 'b.
 *
 * Takes: { delim: MezzeCont }
 * Returns: ({ value: 'a } -> 'b) - resumable function
 */
@NodeInfo(shortName = "cont-capture")
public final class ContinuationCaptureNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationCaptureNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_capture expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_capture expects 'delim' field as MezzeCont");
        }

        // Get the current delimiter entry
        DelimiterStack.DelimiterEntry entry = DelimiterStack.find(delim.getId());

        if (entry == null) {
            // No active delimiter - return trivial continuation
            return createTrivialContinuation(delim.getId());
        }

        // If there's a captured continuation in the entry, return it
        if (entry.capturedContinuation != null) {
            return wrapContinuationAsFunction(entry.capturedContinuation);
        }

        // No continuation captured yet - return trivial continuation
        return createTrivialContinuation(delim.getId());
    }

    /**
     * Create a trivial continuation that just returns its input.
     */
    @TruffleBoundary
    private Object createTrivialContinuation(long delimiterId) {
        // Create a simple function that extracts { value } and returns value
        CallTarget callTarget = createTrivialCallTarget();
        MezzeContinuation cont = MezzeContinuation.create(callTarget, delimiterId);
        return wrapContinuationAsFunction(cont);
    }

    /**
     * Create a CallTarget for trivial continuation.
     */
    @TruffleBoundary
    private CallTarget createTrivialCallTarget() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        FrameDescriptor fd = builder.build();

        // Create a root node that extracts { value } from args and returns value
        MezzeRootNode rootNode = new MezzeRootNode(
                MezzeLanguage.get(this),
                fd,
                new ExtractValueNode(),
                "trivial_continuation",
                0,
                1
        );

        return rootNode.getCallTarget();
    }

    /**
     * Wrap a MezzeContinuation as a MezzeFunction.
     */
    @TruffleBoundary
    private MezzeFunction wrapContinuationAsFunction(MezzeContinuation cont) {
        return new MezzeFunction(
                cont.getCallTarget(),
                "continuation@" + cont.getDelimiterId(),
                cont.getCapturedValues()
        );
    }

    public static ContinuationCaptureNode create(MezzeNode argsNode) {
        return new ContinuationCaptureNode(argsNode);
    }

    /**
     * Helper node that extracts value from { value: x } argument.
     */
    private static final class ExtractValueNode extends MezzeNode {
        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            if (args.length > 0 && args[0] instanceof MezzeRecord record) {
                Object value = record.getField("value");
                return value != null ? value : MezzeRecord.empty();
            }
            return MezzeRecord.empty();
        }
    }
}
