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
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Direct tail call by content hash with the SCALAR calling convention.
 *
 * Throws TailCallException carrying [closure..., MARKER, scalar0, ..., scalarN-1].
 * TailCallLoopNode catches it and either rebinds frame slots (self-recursive
 * tail call) or invokes the target's CallTarget (cross-function tail call).
 */
@NodeInfo(shortName = "tail-call-direct-scalar")
public final class TailCallDirectScalarNode extends MezzeNode {

    private final String functionName;

    @CompilationFinal
    private final Hash contentHash;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    @CompilationFinal
    private MezzeFunction cachedFunction;

    @CompilationFinal
    private CallTarget cachedCallTarget;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    @CompilationFinal
    private int cachedClosureLength;

    /** True iff cachedCallTarget == this node's enclosing RootNode's CallTarget. */
    @CompilationFinal
    private boolean isSelfTailCall;

    /** True iff every callee param is LONG-kind AND this is a self-recursive
     *  tail call. Enables the typed-payload path: arg expressions are read via
     *  {@code executeLong} and stored in a {@code long[]} buffer, eliminating
     *  Long boxing per call. Buffers are allocated per execute() call so the
     *  node is concurrency-safe under Loom virtual threads sharing CallTargets. */
    @CompilationFinal
    private boolean useTypedLongPayload;

    /** True iff every callee param is DOUBLE-kind AND this is a self-recursive
     *  tail call. Symmetric to useTypedLongPayload — eliminates Double boxing. */
    @CompilationFinal
    private boolean useTypedDoublePayload;

    /** True iff this self-recursive tail call's callee has a mix of primitive
     *  (LONG/DOUBLE) and OBJECT params, AND at least one param is primitive.
     *  Pure-OBJECT callees stay on the existing scalar-Object[] path (no boxing
     *  to eliminate). Pure-LONG / pure-DOUBLE keep their cheaper single-bucket
     *  paths above. */
    @CompilationFinal
    private boolean useTypedMixedPayload;

    /** Per-param kind (KIND_LONG=0, DOUBLE=1, BOOLEAN=2, OBJECT=3) — used by
     *  the receiver to dispatch each param into the right bucket. */
    @CompilationFinal(dimensions = 1)
    private byte[] mixedParamKinds;

    /** Mixed-kind bucket sizes — structural metadata, safe to share. */
    @CompilationFinal private int mixedLongCount;
    @CompilationFinal private int mixedDoubleCount;
    @CompilationFinal private int mixedObjCount;

    /** Per-arg index into the matching bucket. Computed once at resolve time:
     *  if argumentNodes[i] writes to longArgs, mixedArgBucketIdx[i] is the
     *  long[] slot to write to. Same for double / object kinds. */
    @CompilationFinal(dimensions = 1)
    private int[] mixedArgBucketIdx;

    public TailCallDirectScalarNode(Hash contentHash, String functionName, MezzeNode[] argumentNodes) {
        this.contentHash = contentHash;
        this.functionName = functionName;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (cachedCallTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveFunction();
        }

        // FAST PATH: self-recursive tail call to an all-LONG callee.
        // Read args via executeLong directly into a typed long[] buffer, throw a
        // typed-payload exception. No Long.valueOf boxing per call. Buffer and
        // exception are allocated per call so the node is concurrency-safe under
        // virtual threads sharing CallTargets.
        if (useTypedLongPayload) {
            long[] longArgs = new long[argumentCount];
            try {
                for (int i = 0; i < argumentCount; i++) {
                    longArgs[i] = argumentNodes[i].executeLong(frame);
                }
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedLongPayload = false;
                return execute(frame);
            }
            throw new TailCallScalarException().fill(cachedCallTarget, longArgs, null, null);
        }

        // FAST PATH: self-recursive tail call to an all-DOUBLE callee. Mirrors LONG.
        if (useTypedDoublePayload) {
            double[] doubleArgs = new double[argumentCount];
            try {
                for (int i = 0; i < argumentCount; i++) {
                    doubleArgs[i] = argumentNodes[i].executeDouble(frame);
                }
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedDoublePayload = false;
                return execute(frame);
            }
            throw new TailCallScalarException().fill(cachedCallTarget, null, doubleArgs, null);
        }

        // FAST PATH: self-recursive tail call to a MIXED-kind callee (e.g.
        // {f: ..., n: Int, acc: Int}). Each arg is read into the bucket
        // matching its slot kind — primitives unboxed, OBJECTs as-is.
        if (useTypedMixedPayload) {
            long[] longArgs = mixedLongCount > 0 ? new long[mixedLongCount] : null;
            double[] doubleArgs = mixedDoubleCount > 0 ? new double[mixedDoubleCount] : null;
            Object[] objArgs = mixedObjCount > 0 ? new Object[mixedObjCount] : null;
            try {
                fillMixedBuckets(frame, longArgs, doubleArgs, objArgs);
            } catch (UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                useTypedMixedPayload = false;
                return execute(frame);
            }
            throw new TailCallScalarException().fillMixed(
                cachedCallTarget, mixedParamKinds,
                longArgs, doubleArgs, objArgs);
        }

        // SLOW PATH: object-based payload (non-self-rec, or callee
        // hasn't been resolved yet so we don't know kinds). Always fresh args.
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

        throw new TailCallException(cachedCallTarget, args);
    }

    @TruffleBoundary
    private void resolveFunction() {
        MezzeContext context = MezzeContext.get(this);

        Object value = contentHash != null ? context.lookupGlobal(contentHash) : null;

        if (value == null && contentHash != null) {
            CallTarget stdlibCT = MezzeLanguage.get(this).getCachedStdlibCallTarget(contentHash);
            if (stdlibCT != null) {
                value = MezzeFunction.create(stdlibCT, contentHash.getHex());
            }
        }

        if (!(value instanceof MezzeFunction)) {
            throw new RuntimeException(
                "TailCallDirectScalarNode: expected MezzeFunction" +
                (contentHash != null ? " for hash " + contentHash.getHex() : "") +
                " (function name: " + functionName + "), got: " +
                (value == null ? "null" : value.getClass().getName())
            );
        }

        cachedFunction = (MezzeFunction) value;
        cachedCallTarget = cachedFunction.getCallTarget();
        cachedClosureValues = cachedFunction.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;

        // Detect self-recursion: if the target is this node's enclosing RootNode,
        // the buffer can be reused (caught synchronously by the loop above).
        com.oracle.truffle.api.nodes.RootNode rn = getRootNode();
        isSelfTailCall = rn != null && rn.getCallTarget() == cachedCallTarget;

        // Detect typed-LONG / typed-DOUBLE fast paths: self-rec + every callee
        // param is LONG-kind / DOUBLE-kind. Avoids boxing per recursive call.
        if (isSelfTailCall && rn instanceof MezzeFunctionRootNode mrn
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
            } else if (allDouble) {
                useTypedDoublePayload = true;
            } else {
                // Try mixed-kind path: any LONG/DOUBLE/OBJECT mix is fine.
                // We only need to skip the BOOLEAN slot — receiver's
                // bindMixedPayload doesn't have a boolean bucket today.
                boolean anyPrimitive = false;
                boolean anyBool = false;
                for (int i = 0; i < kinds.length; i++) {
                    if (kinds[i] == 2 /* KIND_BOOLEAN */) anyBool = true;
                    if (kinds[i] == 0 || kinds[i] == 1) anyPrimitive = true;
                }
                if (!anyBool && anyPrimitive) {
                    setupMixedBuckets(kinds);
                    useTypedMixedPayload = true;
                }
            }
        }
    }

    /** Cache structural metadata (kinds, bucket sizes, per-arg bucket
     *  indices) for the mixed-kind path. Buffers are allocated per
     *  execute() call to keep the node concurrency-safe. */
    private void setupMixedBuckets(int[] kinds) {
        int longCount = 0, doubleCount = 0, objCount = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == 0) longCount++;
            else if (kinds[i] == 1) doubleCount++;
            else objCount++;
        }
        mixedLongCount = longCount;
        mixedDoubleCount = doubleCount;
        mixedObjCount = objCount;

        mixedParamKinds = new byte[kinds.length];
        mixedArgBucketIdx = new int[kinds.length];
        int li = 0, di = 0, oi = 0;
        for (int i = 0; i < kinds.length; i++) {
            mixedParamKinds[i] = (byte) kinds[i];
            if (kinds[i] == 0) mixedArgBucketIdx[i] = li++;
            else if (kinds[i] == 1) mixedArgBucketIdx[i] = di++;
            else mixedArgBucketIdx[i] = oi++;
        }
    }

    /** Read each arg via the kind-appropriate execute method into the bucket
     *  at its precomputed index. Buckets passed in (allocated per call). */
    @ExplodeLoop
    private void fillMixedBuckets(VirtualFrame frame, long[] longArgs, double[] doubleArgs, Object[] objArgs) throws UnexpectedResultException {
        for (int i = 0; i < argumentCount; i++) {
            byte kind = mixedParamKinds[i];
            int idx = mixedArgBucketIdx[i];
            if (kind == 0 /* LONG */) {
                longArgs[idx] = argumentNodes[i].executeLong(frame);
            } else if (kind == 1 /* DOUBLE */) {
                doubleArgs[idx] = argumentNodes[i].executeDouble(frame);
            } else {
                objArgs[idx] = argumentNodes[i].execute(frame);
            }
        }
    }
}
