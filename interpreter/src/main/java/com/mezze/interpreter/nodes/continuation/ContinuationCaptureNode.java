package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.runtime.MezzeLanguage;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
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

        // Try to capture from state machine context first
        return captureFromStateMachineContext(delim.getId());
    }

    /**
     * Capture continuation from the current state machine execution context.
     */
    @TruffleBoundary
    private Object captureFromStateMachineContext(long delimiterId) {
        com.mezze.interpreter.nodes.statemachine.StateMachineContext.ExecutionSnapshot context =
                com.mezze.interpreter.nodes.statemachine.StateMachineContext.getCurrentContext();

        if (context == null) {
            // No state machine context - return trivial continuation
            return createTrivialContinuation(delimiterId);
        }

        // Create a continuation that resumes the state machine from the next state
        MezzeContinuation cont = MezzeContinuation.create(
                context.stateMachine.createResumeCallTarget(),
                context.capturedLocals,
                delimiterId
        );

        return wrapContinuationAsFunction(cont);
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
        // Note: ExtractValueNode uses frame.getArguments() directly,
        // so we pass empty slotTypes to avoid MezzeRootNode trying to bind args to frame slots
        MezzeRootNode rootNode = new MezzeRootNode(
                MezzeLanguage.get(this),
                fd,
                new ExtractValueNode(),
                "trivial_continuation",
                0,
                1,
                new int[0]  // No slot types - ExtractValueNode uses getArguments() directly
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
