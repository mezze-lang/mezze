package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;


import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_suspend_with - Suspends with a value attached.
 *
 * This is THE KEY PRIMITIVE for effect handlers. When called:
 * 1. Creates a continuation representing "the rest of the computation"
 * 2. Returns MezzeContinuationSuspended with the value and continuation
 *
 * The effect handler receives this suspended continuation and can:
 * - Resume it with a new value (resume)
 * - Resume it multiple times (fork)
 * - Discard it (abort)
 * - Return the value directly (early return)
 *
 * Takes: { delim: MezzeCont, value: 'a }
 * Returns: MezzeContinuationSuspended - the suspended continuation with value
 */
@NodeInfo(shortName = "cont-suspend-with")
public final class ContinuationSuspendWithNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationSuspendWithNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_suspend_with expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont delim)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_suspend_with expects 'delim' field as MezzeCont");
        }

        Object value = record.getField("value");
        if (value == null) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_suspend_with expects 'value' field");
        }

        // Create a continuation that, when resumed, returns the resume value
        MezzeContinuation continuation = createResumableContinuation(delim.getId());

        MezzeContinuationSuspended suspended = new MezzeContinuationSuspended(value, continuation);

        // Always throw to escape to the delimiter boundary.
        // This ensures the suspended value isn't wrapped by intermediate expressions.
        // The delimiter boundary (HandleNode or direct continuation usage) catches this
        // and extracts the suspended value.
        throw new ContinuationSuspendedException(suspended);
    }

    /**
     * Create a continuation that returns its resume value.
     */
    @TruffleBoundary
    private MezzeContinuation createResumableContinuation(long delimiterId) {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        FrameDescriptor fd = builder.build();

        // Root node that extracts { value } from args and returns value
        // Note: ExtractValueNode uses frame.getArguments() directly,
        // so we pass empty slotTypes to avoid MezzeRootNode trying to bind args to frame slots
        MezzeRootNode rootNode = new MezzeRootNode(
                MezzeLanguage.get(this),
                fd,
                new ExtractValueNode(),
                "resume_continuation",
                0,
                1,
                new int[0]  // No slot types - ExtractValueNode uses getArguments() directly
        );

        return MezzeContinuation.create(rootNode.getCallTarget(), delimiterId);
    }

    public static ContinuationSuspendWithNode create(MezzeNode argsNode) {
        return new ContinuationSuspendWithNode(argsNode);
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
