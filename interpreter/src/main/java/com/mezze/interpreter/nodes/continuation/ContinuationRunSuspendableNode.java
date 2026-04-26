package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_run_suspendable - Run a thunk and catch suspension.
 *
 * This is THE KEY PRIMITIVE for generators. It runs a thunk (zero-argument function)
 * and catches any ContinuationSuspendedException thrown by suspend_with operations.
 *
 * Returns a variant:
 * - Completed {value: 'a} if the thunk completes normally
 * - Suspended {value: 'v, cont: ...} if the thunk suspends via suspend_with
 *
 * Takes: { thunk: {} -> 'a }
 * Returns: | Completed {value: 'a}, Suspended {value: 'v, cont: ({value: 'r} -> 'a)} |
 *
 * Example usage in Mezze:
 *   match run_suspendable {thunk = gen} is
 *       Completed {value} -> Done {value}
 *       Suspended {value, cont} -> do
 *           # Process yielded value
 *           # Resume with: cont {value = resume_value}
 */
@NodeInfo(shortName = "cont-run-suspendable")
public final class ContinuationRunSuspendableNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    @Child
    private IndirectCallNode callNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile suspendedProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile completedProfile = InlinedBranchProfile.getUncached();

    public ContinuationRunSuspendableNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
        this.callNode = IndirectCallNode.create();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_run_suspendable expects record argument");
        }

        Object thunkObj = record.getField("thunk");
        if (!(thunkObj instanceof MezzeFunction thunk)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_run_suspendable expects 'thunk' field as function");
        }

        // Call the thunk with empty record argument {}
        Object[] callArgs = prepareCallArgs(thunk);

        try {
            Object result = callNode.call(thunk.getCallTarget(), callArgs);

            // Check if result is already a MezzeContinuationSuspended
            // (in case it wasn't thrown but returned directly)
            if (result instanceof MezzeContinuationSuspended suspended) {
                suspendedProfile.enter(this);
                return buildSuspendedResult(suspended);
            }

            // Normal completion
            completedProfile.enter(this);
            return buildCompletedResult(result);
        } catch (ContinuationSuspendedException e) {
            // Thunk suspended via suspend_with - capture the suspension
            suspendedProfile.enter(this);
            return buildSuspendedResult(e.suspended);
        }
    }

    @TruffleBoundary
    private Object[] prepareCallArgs(MezzeFunction thunk) {
        Object[] closureValues = thunk.getClosureValues();
        int closureLen = closureValues.length;
        Object[] callArgs = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, callArgs, 0, closureLen);
        callArgs[closureLen] = MezzeRecord.empty();  // Pass {} as argument
        return callArgs;
    }

    @TruffleBoundary
    private MezzeVariant buildCompletedResult(Object value) {
        MezzeRecord inner = MezzeRecord.fromFields(
            new String[]{"value"},
            new Object[]{value}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Completed");
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, inner);
    }

    @TruffleBoundary
    private MezzeVariant buildSuspendedResult(MezzeContinuationSuspended suspended) {
        Object value = suspended.getSuspendedValue();
        MezzeContinuation cont = suspended.getContinuation();

        // Wrap continuation as function
        MezzeFunction contFunc = new MezzeFunction(
            cont.getCallTarget(),
            "suspended_continuation",
            cont.getCapturedValues()
        );

        MezzeRecord inner = MezzeRecord.fromFields(
            new String[]{"value", "cont"},
            new Object[]{value, contFunc}
        );
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Suspended");
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, inner);
    }

    public static ContinuationRunSuspendableNode create(MezzeNode argsNode) {
        return new ContinuationRunSuspendableNode(argsNode);
    }
}
