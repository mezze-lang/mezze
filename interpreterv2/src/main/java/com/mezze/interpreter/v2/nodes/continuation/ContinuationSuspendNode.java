package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeContinuation;
import com.mezze.interpreter.v2.values.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeNull;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * __native_continuation_suspend - Suspends without a value.
 *
 * Like suspend_with but doesn't attach a value (uses null/unit).
 *
 * Takes: {}
 * Returns: MezzeContinuationSuspended
 */
@NodeInfo(shortName = "cont-suspend")
public final class ContinuationSuspendNode extends MezzeNode {

    @Override
    public Object execute(VirtualFrame frame) {
        // Get current delimiter
        DelimiterStack.DelimiterEntry entry = DelimiterStack.current();
        long delimiterId = entry != null ? entry.delimiter.getId() : 0;

        // Create continuation
        MezzeContinuation continuation = createResumableContinuation(delimiterId);

        // Return suspended with null value
        return new MezzeContinuationSuspended(MezzeNull.INSTANCE, continuation);
    }

    @TruffleBoundary
    private MezzeContinuation createResumableContinuation(long delimiterId) {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        FrameDescriptor fd = builder.build();

        MezzeRootNode rootNode = new MezzeRootNode(
                MezzeLanguage.get(this),
                fd,
                new ExtractValueNode(),
                "suspend_continuation",
                0,
                1
        );

        return MezzeContinuation.create(rootNode.getCallTarget(), delimiterId);
    }

    public static ContinuationSuspendNode create() {
        return new ContinuationSuspendNode();
    }

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
