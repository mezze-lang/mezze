package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Indirect call with a single positional scalar argument.
 *
 * Targets the HOF callback hot path: {@code f {x = n}} inside {@code map}/
 * {@code filter}/{@code fold}. The typechecker emits {@link com.mezze.language.truffleir.CallIndirectScalarIR}
 * when the call's record literal has exactly one field — typecheck guarantees
 * the field name matches the callee's single param name, so positional binding
 * at index 0 is correct.
 *
 * Strategy: evaluate the function expression once, inline-cache the resolved
 * MezzeFunction, then take the typed-LONG / typed-DOUBLE fast path when the
 * cached callee's single param is primitive. Otherwise materialize a single-
 * field MezzeRecord and dispatch through the existing record path.
 *
 * Restriction: only used when the call is fully-applied (1 arg into 1 param).
 * If the cached function has 0 params (e.g. partial-app result returned as a
 * thunk) or > 1 params (curried), we fall back to record materialization too,
 * since the typechecker's static guarantee no longer holds.
 */
@NodeInfo(shortName = "invoke-scalar-1")
public final class InvokeScalar1Node extends MezzeNode {

    @Child private MezzeNode functionNode;
    @Child private MezzeNode argNode;
    private final String fieldName;

    /** Inline cache (monomorphic for now). */
    @CompilationFinal private MezzeFunction cachedFunction;
    @Child private DirectCallNode cachedCallNode;
    @CompilationFinal(dimensions = 1) private Object[] cachedClosureValues;
    @CompilationFinal private int cachedClosureLength;

    /** Detected typed-LONG / DOUBLE fast path on the cached callee. */
    @CompilationFinal private boolean useTypedLong;
    @CompilationFinal private boolean useTypedDouble;
    /** Per-node typed payload buffers (reused) and the wrapper Object[]. */
    @CompilationFinal(dimensions = 1) private long[] reusableLongArgs;
    @CompilationFinal(dimensions = 1) private double[] reusableDoubleArgs;
    @CompilationFinal(dimensions = 1) private Object[] reusableTypedWrapper;

    public InvokeScalar1Node(MezzeNode functionNode, MezzeNode argNode, String fieldName) {
        this.functionNode = functionNode;
        this.argNode = argNode;
        this.fieldName = fieldName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object fnValue = functionNode.execute(frame);
        if (!(fnValue instanceof MezzeFunction fn)) {
            CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("InvokeScalar1Node: function expr did not produce MezzeFunction, got " +
                (fnValue == null ? "null" : fnValue.getClass().getName()));
        }

        // Inline cache: if a different function shows up, fall back permanently.
        if (cachedFunction == null || cachedFunction != fn) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveAndCache(fn);
        }

        // Typed-LONG fast path
        if (useTypedLong) {
            try {
                reusableLongArgs[0] = argNode.executeLong(frame);
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedLong = false;
                return fallbackRecordCall(frame, fn);
            }
            // wrapper layout: [closure..., LONG_MARKER, longArrRef]
            // closure values + marker + array ref are all set once during resolve;
            // the array contents (reusableLongArgs[0]) is the only live mutation.
            return cachedCallNode.call(reusableTypedWrapper);
        }

        // Typed-DOUBLE fast path
        if (useTypedDouble) {
            try {
                reusableDoubleArgs[0] = argNode.executeDouble(frame);
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedDouble = false;
                return fallbackRecordCall(frame, fn);
            }
            return cachedCallNode.call(reusableTypedWrapper);
        }

        // Fallback: build a single-field MezzeRecord and dispatch through the
        // record ABI. Slower but correct for any callee shape (partial app,
        // OBJECT-typed param, etc.).
        return fallbackRecordCall(frame, fn);
    }

    private Object fallbackRecordCall(VirtualFrame frame, MezzeFunction fn) {
        Object value = argNode.execute(frame);
        // Build a single-field MezzeRecord using the existing factory.
        // String[] is per-call here; future optimization could share via Shape cache.
        MezzeRecord record = MezzeRecord.fromFields(new String[] { fieldName }, new Object[] { value });
        // Build args: [closure..., record]
        if (cachedFunction == fn && cachedCallNode != null) {
            // Partial-app handling: if the cached function expects a record with all
            // params, we already have it (1-field) — call directly. Otherwise, hand
            // off to the runtime helper.
            if (fn.getParameterNames().length == 1
                    && fn.getParameterNames()[0].equals(fieldName)
                    && !fn.hasPartialArgs()) {
                Object[] args = new Object[cachedClosureLength + 1];
                for (int i = 0; i < cachedClosureLength; i++) args[i] = cachedClosureValues[i];
                args[cachedClosureLength] = record;
                return cachedCallNode.call(args);
            }
            return slowGenericInvoke(fn, record);
        }
        return slowGenericInvoke(fn, record);
    }

    @CompilerDirectives.TruffleBoundary
    private Object slowGenericInvoke(MezzeFunction fn, MezzeRecord record) {
        // Generic: handle partial application, mismatched param names, etc.
        if (fn.getParameterNames().length > 0 && !fn.hasAllParameters(record)) {
            return fn.withPartialArgs(record);
        }
        if (fn.hasPartialArgs() && fn.hasAllParameters(record)) {
            MezzeRecord complete = fn.buildCompleteRecord(record);
            return invokeWithClosure(fn, complete);
        }
        return invokeWithClosure(fn, record);
    }

    private Object invokeWithClosure(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        for (int i = 0; i < closureValues.length; i++) args[i] = closureValues[i];
        args[closureValues.length] = arg;
        return fn.getCallTarget().call(args);
    }

    @CompilerDirectives.TruffleBoundary
    private void resolveAndCache(MezzeFunction fn) {
        cachedFunction = fn;
        cachedCallNode = insert(DirectCallNode.create(fn.getCallTarget()));
        cachedClosureValues = fn.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        useTypedLong = false;
        useTypedDouble = false;
        reusableLongArgs = null;
        reusableDoubleArgs = null;
        reusableTypedWrapper = null;

        // Inspect the cached callee's RootNode to detect a primitive-typed single param.
        // Skip if the function carries partial args (different binding behavior) or
        // its arity disagrees with our single-arg assumption.
        if (fn.hasPartialArgs() || fn.getParameterNames().length != 1) return;
        if (!fn.getParameterNames()[0].equals(fieldName)) return;

        if (!(fn.getCallTarget() instanceof RootCallTarget rct)) return;
        if (!(rct.getRootNode() instanceof MezzeFunctionRootNode mrn)) return;
        if (mrn.getParameterCount() != 1) return;

        int[] kinds = mrn.getParamSlotKinds();
        if (kinds.length != 1) return;
        if (kinds[0] == 0 /* KIND_LONG */) {
            useTypedLong = true;
            reusableLongArgs = new long[1];
            reusableTypedWrapper = new Object[cachedClosureLength + 2];
            for (int i = 0; i < cachedClosureLength; i++) reusableTypedWrapper[i] = cachedClosureValues[i];
            reusableTypedWrapper[cachedClosureLength] = ScalarLongPayloadMarker.INSTANCE;
            reusableTypedWrapper[cachedClosureLength + 1] = reusableLongArgs;
        } else if (kinds[0] == 1 /* KIND_DOUBLE */) {
            useTypedDouble = true;
            reusableDoubleArgs = new double[1];
            reusableTypedWrapper = new Object[cachedClosureLength + 2];
            for (int i = 0; i < cachedClosureLength; i++) reusableTypedWrapper[i] = cachedClosureValues[i];
            reusableTypedWrapper[cachedClosureLength] = ScalarDoublePayloadMarker.INSTANCE;
            reusableTypedWrapper[cachedClosureLength + 1] = reusableDoubleArgs;
        }
    }
}
