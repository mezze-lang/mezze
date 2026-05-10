package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct call by content hash with the SCALAR calling convention.
 *
 * Args layout placed into the CallTarget:
 *   [closure0, ..., closureN-1, ScalarABIMarker.INSTANCE, scalar0, scalar1, ..., scalarParamCount-1]
 *
 * No MezzeRecord allocation — positional values flow through.
 * The receiver MezzeFunctionRootNode dispatches on the marker.
 *
 * Restriction: only emitted by the typechecker for fully-applied direct calls
 * (callee hash known, all params present). For incomplete record calls /
 * partial application / spread args, the typechecker emits the record-shaped
 * MonomorphicCallNode path instead.
 */
@NodeInfo(shortName = "call-direct-scalar")
public final class CallDirectScalarNode extends MezzeNode {

    private final String functionName;

    @CompilationFinal
    private final Hash contentHash;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    @CompilationFinal
    private MezzeFunction cachedFunction;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    @CompilationFinal
    private int cachedClosureLength;

    /** True iff every callee param is LONG-kind. Activates the typed-payload
     *  fast path: read each arg via executeLong() into a reusable long[],
     *  pass via [..., MARKER, longArr, ...]. Saves N Long.valueOf boxings. */
    @CompilationFinal
    private boolean useTypedLongPayload;

    /** Reusable typed args buffer for the all-LONG fast path. */
    @CompilationFinal(dimensions = 1)
    private long[] reusableLongArgs;

    /** True iff every callee param is DOUBLE-kind. Symmetric to LONG. */
    @CompilationFinal
    private boolean useTypedDoublePayload;

    /** Reusable typed args buffer for the all-DOUBLE fast path. */
    @CompilationFinal(dimensions = 1)
    private double[] reusableDoubleArgs;

    /** Reusable Object[] wrapper for the typed path:
     *  [closure..., ScalarXPayloadMarker.INSTANCE, xArrRef]
     *  (X = Long or Double). One slot for the marker, one for the typed array. */
    @CompilationFinal(dimensions = 1)
    private Object[] reusableTypedArgsWrapper;

    /** Mixed-kind path: callee has both primitive (LONG/DOUBLE) and OBJECT params,
     *  and at least one primitive param. Eliminates boxing for those primitives. */
    @CompilationFinal
    private boolean useTypedMixedPayload;
    @CompilationFinal(dimensions = 1)
    private byte[] mixedParamKinds;
    @CompilationFinal(dimensions = 1)
    private long[] mixedLongArgs;
    @CompilationFinal(dimensions = 1)
    private double[] mixedDoubleArgs;
    @CompilationFinal(dimensions = 1)
    private Object[] mixedObjArgs;
    @CompilationFinal(dimensions = 1)
    private int[] mixedArgBucketIdx;
    /** Mixed wrapper layout: [closure..., MIXED_MARKER, paramKinds, longArgs, doubleArgs, objArgs]. */
    @CompilationFinal(dimensions = 1)
    private Object[] reusableMixedWrapper;

    public CallDirectScalarNode(Hash contentHash, String functionName, MezzeNode[] argumentNodes) {
        this.contentHash = contentHash;
        this.functionName = functionName;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (callNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveFunction();
        }

        // FAST PATH: all-LONG callee. Read primitives via executeLong() into a
        // reused long[] and pass [closure..., LONG_MARKER, longArr] — N fewer
        // Long.valueOf allocations per call.
        if (useTypedLongPayload) {
            long[] longArgs = reusableLongArgs;
            try {
                for (int i = 0; i < argumentCount; i++) {
                    longArgs[i] = argumentNodes[i].executeLong(frame);
                }
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedLongPayload = false;
                return execute(frame);
            }
            Object[] wrapper = reusableTypedArgsWrapper;
            for (int i = 0; i < cachedClosureLength; i++) {
                wrapper[i] = cachedClosureValues[i];
            }
            // wrapper[cachedClosureLength] = LONG_MARKER (set once during resolve)
            // wrapper[cachedClosureLength + 1] = longArgs (set once during resolve)
            return callNode.call(wrapper);
        }

        // FAST PATH: all-DOUBLE callee. Symmetric to LONG.
        if (useTypedDoublePayload) {
            double[] doubleArgs = reusableDoubleArgs;
            try {
                for (int i = 0; i < argumentCount; i++) {
                    doubleArgs[i] = argumentNodes[i].executeDouble(frame);
                }
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedDoublePayload = false;
                return execute(frame);
            }
            Object[] wrapper = reusableTypedArgsWrapper;
            for (int i = 0; i < cachedClosureLength; i++) {
                wrapper[i] = cachedClosureValues[i];
            }
            return callNode.call(wrapper);
        }

        // FAST PATH: mixed-kind callee. Each arg goes into the bucket for its
        // slot kind — primitives unboxed, OBJECTs as-is. Receiver dispatches
        // via paramKinds (set once at resolve time, lives in the wrapper).
        if (useTypedMixedPayload) {
            try {
                fillMixedBuckets(frame);
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedMixedPayload = false;
                return execute(frame);
            }
            Object[] wrapper = reusableMixedWrapper;
            for (int i = 0; i < cachedClosureLength; i++) {
                wrapper[i] = cachedClosureValues[i];
            }
            return callNode.call(wrapper);
        }

        // SLOW PATH: object-shaped scalar payload (mixed kinds or unresolved).
        // ABI layout depends on argument arity:
        //   paramCount == 1: [closure..., MARKER, scalar0]
        //     (marker disambiguates from record ABI [closure..., recordArg]
        //      when the scalar arg might itself be a MezzeRecord value)
        //   paramCount != 1: [closure..., scalar0, ..., scalarN-1]
        //     (length unambiguously distinguishes scalar from record)
        boolean needsMarker = argumentCount == 1;
        int totalLen = cachedClosureLength + (needsMarker ? 1 : 0) + argumentCount;
        Object[] args = new Object[totalLen];

        for (int i = 0; i < cachedClosureLength; i++) {
            args[i] = cachedClosureValues[i];
        }
        int valuesStart = cachedClosureLength;
        if (needsMarker) {
            args[valuesStart] = ScalarABIMarker.INSTANCE;
            valuesStart++;
        }
        for (int i = 0; i < argumentCount; i++) {
            args[valuesStart + i] = argumentNodes[i].execute(frame);
        }

        return callNode.call(args);
    }

    @TruffleBoundary
    private void resolveFunction() {
        MezzeContext context = MezzeContext.get(this);

        Object value = contentHash != null ? context.lookupGlobal(contentHash) : null;

        // Fall back to language-cached stdlib CallTargets, mirroring MonomorphicCallNode.
        if (value == null && contentHash != null) {
            CallTarget stdlibCT = MezzeLanguage.get(this).getCachedStdlibCallTarget(contentHash);
            if (stdlibCT != null) {
                value = MezzeFunction.create(stdlibCT, contentHash.getHex());
            }
        }

        if (!(value instanceof MezzeFunction)) {
            throw new RuntimeException(
                "CallDirectScalarNode: expected MezzeFunction" +
                (contentHash != null ? " for hash " + contentHash.getHex() : "") +
                " (function name: " + functionName + "), got: " +
                (value == null ? "null" : value.getClass().getName())
            );
        }

        cachedFunction = (MezzeFunction) value;
        CallTarget target = cachedFunction.getCallTarget();
        callNode = DirectCallNode.create(target);
        cachedClosureValues = cachedFunction.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        insert(callNode);

        // Detect typed-LONG / typed-DOUBLE fast paths: every callee param is
        // LONG-kind / DOUBLE-kind AND call is fully-applied (argumentCount == paramCount).
        // Eliminates Long.valueOf / Double.valueOf boxing for arithmetic recursive calls.
        if (target instanceof com.oracle.truffle.api.RootCallTarget rct
                && rct.getRootNode() instanceof MezzeFunctionRootNode mrn
                && mrn.getParameterCount() == argumentCount) {
            int[] kinds = mrn.getParamSlotKinds();
            boolean allLong = kinds.length == argumentCount;
            boolean allDouble = kinds.length == argumentCount;
            for (int i = 0; (allLong || allDouble) && i < kinds.length; i++) {
                if (kinds[i] != 0 /* KIND_LONG */) allLong = false;
                if (kinds[i] != 1 /* KIND_DOUBLE */) allDouble = false;
            }
            if (allLong) {
                useTypedLongPayload = true;
                reusableLongArgs = new long[argumentCount];
                reusableTypedArgsWrapper = new Object[cachedClosureLength + 2];
                reusableTypedArgsWrapper[cachedClosureLength] = ScalarLongPayloadMarker.INSTANCE;
                reusableTypedArgsWrapper[cachedClosureLength + 1] = reusableLongArgs;
            } else if (allDouble) {
                useTypedDoublePayload = true;
                reusableDoubleArgs = new double[argumentCount];
                reusableTypedArgsWrapper = new Object[cachedClosureLength + 2];
                reusableTypedArgsWrapper[cachedClosureLength] = ScalarDoublePayloadMarker.INSTANCE;
                reusableTypedArgsWrapper[cachedClosureLength + 1] = reusableDoubleArgs;
            } else {
                // Mixed kind path: allowed for any LONG/DOUBLE/OBJECT mix with at
                // least one primitive (pure-OBJECT skips this since there's no
                // boxing to eliminate). BOOLEAN bucket isn't implemented yet —
                // skip rather than risk a wrong bucket assignment.
                boolean anyPrimitive = false;
                boolean anyBool = false;
                for (int i = 0; i < kinds.length; i++) {
                    if (kinds[i] == 2 /* KIND_BOOLEAN */) anyBool = true;
                    if (kinds[i] == 0 || kinds[i] == 1) anyPrimitive = true;
                }
                if (!anyBool && anyPrimitive) {
                    setupMixedBuckets(kinds);
                }
            }
        }
    }

    /** Build per-kind buckets sized to the kind counts and the per-arg
     *  index map. Wires the reusable wrapper layout used by execute(). */
    private void setupMixedBuckets(int[] kinds) {
        int longCount = 0, doubleCount = 0, objCount = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == 0) longCount++;
            else if (kinds[i] == 1) doubleCount++;
            else objCount++;
        }
        mixedLongArgs = longCount > 0 ? new long[longCount] : null;
        mixedDoubleArgs = doubleCount > 0 ? new double[doubleCount] : null;
        mixedObjArgs = objCount > 0 ? new Object[objCount] : null;

        mixedParamKinds = new byte[kinds.length];
        mixedArgBucketIdx = new int[kinds.length];
        int li = 0, di = 0, oi = 0;
        for (int i = 0; i < kinds.length; i++) {
            mixedParamKinds[i] = (byte) kinds[i];
            if (kinds[i] == 0) mixedArgBucketIdx[i] = li++;
            else if (kinds[i] == 1) mixedArgBucketIdx[i] = di++;
            else mixedArgBucketIdx[i] = oi++;
        }

        // Layout: [closure..., MIXED_MARKER, paramKinds, longArgs, doubleArgs, objArgs]
        reusableMixedWrapper = new Object[cachedClosureLength + 5];
        reusableMixedWrapper[cachedClosureLength] = ScalarMixedPayloadMarker.INSTANCE;
        reusableMixedWrapper[cachedClosureLength + 1] = mixedParamKinds;
        reusableMixedWrapper[cachedClosureLength + 2] = mixedLongArgs;
        reusableMixedWrapper[cachedClosureLength + 3] = mixedDoubleArgs;
        reusableMixedWrapper[cachedClosureLength + 4] = mixedObjArgs;
        useTypedMixedPayload = true;
    }

    /** Read each arg via the kind-appropriate execute method into the bucket
     *  at its precomputed index. */
    @ExplodeLoop
    private void fillMixedBuckets(VirtualFrame frame) throws UnexpectedResultException {
        for (int i = 0; i < argumentCount; i++) {
            byte kind = mixedParamKinds[i];
            int idx = mixedArgBucketIdx[i];
            if (kind == 0 /* LONG */) {
                mixedLongArgs[idx] = argumentNodes[i].executeLong(frame);
            } else if (kind == 1 /* DOUBLE */) {
                mixedDoubleArgs[idx] = argumentNodes[i].executeDouble(frame);
            } else {
                mixedObjArgs[idx] = argumentNodes[i].execute(frame);
            }
        }
    }
}
