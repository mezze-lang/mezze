package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeContinuation;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * __native_continuation_resume - Resumes a continuation with a value.
 *
 * This calls the continuation function with the provided value, effectively
 * "continuing" the computation from where it was suspended.
 *
 * Takes: { delim: MezzeCont, cont: ({ value: 'a } -> 'b), value: 'a }
 * Returns: 'b - the result of resuming the continuation
 */
@NodeInfo(shortName = "cont-resume")
public final class ContinuationResumeNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    // Use inline caching for monomorphic call sites
    @Child
    private DirectCallNode cachedCallNode;

    @CompilationFinal
    private CallTarget cachedTarget;

    // Fallback for polymorphic call sites
    @Child
    private IndirectCallNode indirectCallNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();

    public ContinuationResumeNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_resume expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_resume expects 'delim' field as MezzeCont");
        }

        Object contObj = record.getField("cont");
        Object value = record.getField("value");

        if (value == null) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_resume expects 'value' field");
        }

        // Create the argument record
        MezzeRecord valueRecord = MezzeRecord.fromFields(
                new String[]{"value"},
                new Object[]{value}
        );

        // Handle different continuation types
        if (contObj instanceof MezzeFunction func) {
            CallTarget target = func.getCallTarget();

            // Build call arguments
            Object[] closureValues = func.getClosureValues();
            int closureLen = closureValues.length;
            Object[] callArgs = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, callArgs, 0, closureLen);
            callArgs[closureLen] = valueRecord;

            // Monomorphic fast path: same target every time
            if (cachedTarget == target && cachedCallNode != null) {
                return cachedCallNode.call(callArgs);
            }

            // First call or target changed - use inline caching
            if (cachedTarget == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                cachedTarget = target;
                cachedCallNode = insert(DirectCallNode.create(target));
                return cachedCallNode.call(callArgs);
            }

            // Polymorphic: different target than cached, use indirect call
            if (indirectCallNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                indirectCallNode = insert(IndirectCallNode.create());
            }
            return indirectCallNode.call(target, callArgs);
        }

        if (contObj instanceof MezzeContinuation cont) {
            return cont.resume(valueRecord);
        }

        errorProfile.enter(this);
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("__native_continuation_resume expects 'cont' field as function or continuation");
    }

    public static ContinuationResumeNode create(MezzeNode argsNode) {
        return new ContinuationResumeNode(argsNode);
    }
}
