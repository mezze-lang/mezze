package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Indirect call with N positional scalar args (N >= 1) — generalizes
 * {@link InvokeScalar1Node}. Used for HOF callbacks like {@code f {acc, x}}
 * inside {@code fold}.
 *
 * Strategy:
 *   1. Evaluate the function expression once; inline-cache the resolved
 *      MezzeFunction (monomorphic).
 *   2. On first cache miss, compute a permutation (caller field-name order
 *      → callee param-name order) and the per-callee-param slot kinds.
 *   3. For all-LONG / all-DOUBLE / mixed-kind callees, take the typed-payload
 *      fast path. Otherwise materialize a single MezzeRecord with the caller's
 *      field-name layout and dispatch through the legacy record path.
 *
 * The permutation is necessary because the IR records args in caller-source
 * order (e.g. user wrote {@code f {acc = a, x = e}} → ["acc","x"]) but the
 * callee may be declared with reversed params ({@code f = {x, acc} -> ...}).
 * Typecheck guarantees the sets match; only the order varies.
 */
@NodeInfo(shortName = "invoke-scalar-n")
public final class InvokeScalarNNode extends MezzeNode {

    @Child private MezzeNode functionNode;
    @Children private final MezzeNode[] argumentNodes;
    @CompilationFinal(dimensions = 1) private final String[] argFieldNames;
    private final int argumentCount;

    /** Inline cache. Monomorphic for now — switching cached function deopts. */
    @CompilationFinal private MezzeFunction cachedFunction;
    @Child private DirectCallNode cachedCallNode;
    @CompilationFinal(dimensions = 1) private Object[] cachedClosureValues;
    @CompilationFinal private int cachedClosureLength;

    /** Permutation: callerToCalleeIdx[i] = callee param index for caller arg i.
     *  Computed once on cache miss. */
    @CompilationFinal(dimensions = 1) private int[] callerToCalleeIdx;
    /** Kind of the callee param at each callee position (LONG/DOUBLE/OBJECT). */
    @CompilationFinal(dimensions = 1) private byte[] calleeParamKinds;

    /** Typed-payload path activation flags. At most one is true at a time.
     *  Buffers (long[]/double[]/Object[]) are allocated per execute() call
     *  so the node is concurrency-safe under Loom virtual threads sharing
     *  CallTargets. */
    @CompilationFinal private boolean useTypedLong;
    @CompilationFinal private boolean useTypedDouble;
    @CompilationFinal private boolean useTypedMixed;

    /** Mixed-kind bucket sizes — structural metadata, safe to share. */
    @CompilationFinal private int mixedLongCount;
    @CompilationFinal private int mixedDoubleCount;
    @CompilationFinal private int mixedObjCount;

    /** Per-arg bucket-slot index for mixed payload: bucketIdxByCallerArg[i] is
     *  the index into the appropriate bucket (long/double/obj) for caller arg i,
     *  determined by the kind of the callee param this arg maps to. */
    @CompilationFinal(dimensions = 1) private int[] bucketIdxByCallerArg;

    public InvokeScalarNNode(MezzeNode functionNode, MezzeNode[] argumentNodes, String[] argFieldNames) {
        this.functionNode = functionNode;
        this.argumentNodes = argumentNodes;
        this.argFieldNames = argFieldNames;
        this.argumentCount = argumentNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object fnValue = functionNode.execute(frame);
        if (!(fnValue instanceof MezzeFunction fn)) {
            CompilerDirectives.transferToInterpreter();
            throw new RuntimeException("InvokeScalarNNode: function expr did not produce MezzeFunction, got " +
                (fnValue == null ? "null" : fnValue.getClass().getName()));
        }
        if (cachedFunction == null || cachedFunction != fn) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveAndCache(fn);
        }

        // FAST PATH: all-LONG callee. Permute args into long[] bucket then call.
        // Buffers allocated per call to keep the node concurrency-safe under
        // virtual threads sharing CallTargets.
        if (useTypedLong) {
            long[] longArgs = new long[argumentCount];
            try {
                for (int i = 0; i < argumentCount; i++) {
                    longArgs[callerToCalleeIdx[i]] = argumentNodes[i].executeLong(frame);
                }
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedLong = false;
                return fallbackRecordCall(frame, fn);
            }
            Object[] wrapper = new Object[cachedClosureLength + 2];
            for (int i = 0; i < cachedClosureLength; i++) wrapper[i] = cachedClosureValues[i];
            wrapper[cachedClosureLength] = ScalarLongPayloadMarker.INSTANCE;
            wrapper[cachedClosureLength + 1] = longArgs;
            return cachedCallNode.call(wrapper);
        }

        // FAST PATH: all-DOUBLE callee.
        if (useTypedDouble) {
            double[] doubleArgs = new double[argumentCount];
            try {
                for (int i = 0; i < argumentCount; i++) {
                    doubleArgs[callerToCalleeIdx[i]] = argumentNodes[i].executeDouble(frame);
                }
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedDouble = false;
                return fallbackRecordCall(frame, fn);
            }
            Object[] wrapper = new Object[cachedClosureLength + 2];
            for (int i = 0; i < cachedClosureLength; i++) wrapper[i] = cachedClosureValues[i];
            wrapper[cachedClosureLength] = ScalarDoublePayloadMarker.INSTANCE;
            wrapper[cachedClosureLength + 1] = doubleArgs;
            return cachedCallNode.call(wrapper);
        }

        // FAST PATH: mixed-kind callee. Each caller arg is read with the kind
        // of the callee param it maps to and stored in the right bucket.
        if (useTypedMixed) {
            long[] longArgs = mixedLongCount > 0 ? new long[mixedLongCount] : null;
            double[] doubleArgs = mixedDoubleCount > 0 ? new double[mixedDoubleCount] : null;
            Object[] objArgs = mixedObjCount > 0 ? new Object[mixedObjCount] : null;
            try {
                fillMixedBuckets(frame, longArgs, doubleArgs, objArgs);
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedMixed = false;
                return fallbackRecordCall(frame, fn);
            }
            Object[] wrapper = new Object[cachedClosureLength + 5];
            for (int i = 0; i < cachedClosureLength; i++) wrapper[i] = cachedClosureValues[i];
            wrapper[cachedClosureLength] = ScalarMixedPayloadMarker.INSTANCE;
            wrapper[cachedClosureLength + 1] = calleeParamKinds;
            wrapper[cachedClosureLength + 2] = longArgs;
            wrapper[cachedClosureLength + 3] = doubleArgs;
            wrapper[cachedClosureLength + 4] = objArgs;
            return cachedCallNode.call(wrapper);
        }

        // Fallback: build a MezzeRecord with caller's field names + values and
        // dispatch through the existing record ABI. Slower but correct for any
        // callee (partial app, OBJECT params, arity mismatch).
        return fallbackRecordCall(frame, fn);
    }

    @ExplodeLoop
    private void fillMixedBuckets(VirtualFrame frame, long[] longArgs, double[] doubleArgs, Object[] objArgs) throws UnexpectedResultException {
        for (int i = 0; i < argumentCount; i++) {
            int calleeIdx = callerToCalleeIdx[i];
            byte kind = calleeParamKinds[calleeIdx];
            int bucketIdx = bucketIdxByCallerArg[i];
            if (kind == 0 /* LONG */) {
                longArgs[bucketIdx] = argumentNodes[i].executeLong(frame);
            } else if (kind == 1 /* DOUBLE */) {
                doubleArgs[bucketIdx] = argumentNodes[i].executeDouble(frame);
            } else {
                objArgs[bucketIdx] = argumentNodes[i].execute(frame);
            }
        }
    }

    private Object fallbackRecordCall(VirtualFrame frame, MezzeFunction fn) {
        Object[] values = new Object[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            values[i] = argumentNodes[i].execute(frame);
        }
        MezzeRecord record = MezzeRecord.fromFields(argFieldNames, values);

        // If cache matches and the callee accepts our field set, dispatch directly.
        // Otherwise hand off to the slow generic helper (handles partial app, etc).
        if (cachedFunction == fn && cachedCallNode != null
                && fn.getParameterNames().length == argumentCount
                && !fn.hasPartialArgs()) {
            Object[] args = new Object[cachedClosureLength + 1];
            for (int i = 0; i < cachedClosureLength; i++) args[i] = cachedClosureValues[i];
            args[cachedClosureLength] = record;
            return cachedCallNode.call(args);
        }
        return slowGenericInvoke(fn, record);
    }

    @CompilerDirectives.TruffleBoundary
    private Object slowGenericInvoke(MezzeFunction fn, MezzeRecord record) {
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
        useTypedMixed = false;
        mixedLongCount = 0;
        mixedDoubleCount = 0;
        mixedObjCount = 0;
        callerToCalleeIdx = null;
        calleeParamKinds = null;
        bucketIdxByCallerArg = null;

        if (fn.hasPartialArgs() || fn.getParameterNames().length != argumentCount) return;
        String[] calleeParamNames = fn.getParameterNames();

        // Build permutation. callerToCalleeIdx[i] = position in callee param list
        // of caller arg i (whose field name is argFieldNames[i]).
        int[] perm = new int[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            int found = -1;
            for (int j = 0; j < calleeParamNames.length; j++) {
                if (calleeParamNames[j].equals(argFieldNames[i])) { found = j; break; }
            }
            if (found < 0) return; // field name mismatch — fall back to record path
            perm[i] = found;
        }
        callerToCalleeIdx = perm;

        // Inspect callee root node for slot kinds.
        if (!(fn.getCallTarget() instanceof RootCallTarget rct)) return;
        if (!(rct.getRootNode() instanceof MezzeFunctionRootNode mrn)) return;
        if (mrn.getParameterCount() != argumentCount) return;
        int[] kinds = mrn.getParamSlotKinds();
        if (kinds.length != argumentCount) return;

        boolean allLong = true, allDouble = true, anyBool = false, anyPrimitive = false;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] != 0) allLong = false;
            if (kinds[i] != 1) allDouble = false;
            if (kinds[i] == 2) anyBool = true;
            if (kinds[i] == 0 || kinds[i] == 1) anyPrimitive = true;
        }

        if (allLong) {
            useTypedLong = true;
            return;
        }
        if (allDouble) {
            useTypedDouble = true;
            return;
        }
        if (!anyBool && anyPrimitive) {
            // Mixed-kind path: cache structural metadata; buckets are allocated
            // per execute() call (see fast-path branch).
            int longCount = 0, doubleCount = 0, objCount = 0;
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i] == 0) longCount++;
                else if (kinds[i] == 1) doubleCount++;
                else objCount++;
            }
            mixedLongCount = longCount;
            mixedDoubleCount = doubleCount;
            mixedObjCount = objCount;

            calleeParamKinds = new byte[kinds.length];
            // calleeBucketIdx[k] = position of callee param k in its kind's bucket.
            int[] calleeBucketIdx = new int[kinds.length];
            int li = 0, di = 0, oi = 0;
            for (int k = 0; k < kinds.length; k++) {
                calleeParamKinds[k] = (byte) kinds[k];
                if (kinds[k] == 0) calleeBucketIdx[k] = li++;
                else if (kinds[k] == 1) calleeBucketIdx[k] = di++;
                else calleeBucketIdx[k] = oi++;
            }
            // For each caller arg i, the bucket index is determined by the
            // callee param position it maps to.
            bucketIdxByCallerArg = new int[argumentCount];
            for (int i = 0; i < argumentCount; i++) {
                bucketIdxByCallerArg[i] = calleeBucketIdx[perm[i]];
            }
            useTypedMixed = true;
        }
    }
}
