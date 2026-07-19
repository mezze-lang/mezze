package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.instrumentation.MezzeDebugScope;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Function root node with record argument unpacking - MAXIMUM PERFORMANCE.
 *
 * Mezze functions receive a single record argument { x = 1, y = 2 }
 * and this node unpacks field values directly into typed frame slots.
 *
 * Key optimizations:
 * 1. @CompilationFinal parameter names - JIT constant folds field lookups
 * 2. Shape-cached field indices for O(1) field access after warmup
 * 3. @ExplodeLoop unrolls parameter binding completely
 * 4. Typed slot binding (Long/Double/Boolean/Object) avoids boxing
 * 5. Inline closure binding - no array copy overhead
 * 6. Zero-allocation hot path after JIT compilation
 *
 * Slot layout: [closure0, closure1, ..., param0, param1, ...]
 */
@NodeInfo(language = "mezze", description = "Mezze function with record unpacking")
@ExportLibrary(value = NodeLibrary.class, useForAOT = false)
public final class MezzeFunctionRootNode extends RootNode {

    private final String name;

    /**
     * Trampoline driver — wraps the user body in a Truffle LoopNode so the
     * trampoline iteration count is what triggers OSR compilation, not the
     * (single-call) per-CallTarget function-call count. Without this wrap,
     * tail-recursive driver functions like Collatz's {@code sum_lengths}
     * never compile: they're invoked exactly once and spin forever inside
     * the trampoline, never crossing the call-count threshold. JFR pre-fix
     * showed zero {@code Compilation} events for {@code sum_lengths} despite
     * 2M iterations; LoopNode's reported iteration count fixes this.
     */
    @Child private LoopNode bodyLoopNode;

    /**
     * The user body. Kept as a back-reference for {@link #getBodyNode()} so
     * the debugger and other introspection paths still work — the actual
     * {@code @Child} adoption lives inside {@link BodyRepeatingNode}.
     */
    private MezzeNode bodyNode;

    // Parameter names for record field extraction
    @CompilationFinal(dimensions = 1)
    private final String[] parameterNames;

    // Closure variable slots in parent frame
    @CompilationFinal(dimensions = 1)
    private final int[] closureSlots;

    // Slot types: 0=Long, 1=Double, 2=Boolean, 3=Object
    @CompilationFinal(dimensions = 1)
    private final int[] slotTypes;

    // Cached field indices for fast record access (populated after shape stabilizes)
    @CompilationFinal(dimensions = 1)
    private int[] cachedFieldIndices;

    // Cached shape for field index validity check
    @CompilationFinal
    private Object cachedShape;

    @CompilationFinal
    private final int closureCount;

    @CompilationFinal
    private final int parameterCount;

    // Source section for debugger/profiler integration — set after construction by TruffleCompiler
    @CompilationFinal
    private SourceSection sourceSection;

    public MezzeFunctionRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            String[] parameterNames,
            int[] closureSlots,
            int[] slotTypes
    ) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
        this.name = name;
        this.parameterNames = parameterNames != null ? parameterNames : EMPTY_STRINGS;
        this.closureSlots = closureSlots != null ? closureSlots : EMPTY_INTS;
        this.slotTypes = slotTypes != null ? slotTypes : EMPTY_INTS;
        this.closureCount = this.closureSlots.length;
        this.parameterCount = this.parameterNames.length;
        // Wrap the body in a LoopNode-driven trampoline. See bodyLoopNode
        // field comment for why.
        this.bodyLoopNode = Truffle.getRuntime().createLoopNode(new BodyRepeatingNode(bodyNode));
    }

    /**
     * Set the source section for this root node. Called by TruffleCompiler after construction.
     * Enables debuggers and profilers (e.g. Chrome DevTools) to show source locations in stack traces.
     */
    public void setSourceSection(SourceSection section) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        this.sourceSection = section;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    @Override
    public boolean isInternal() {
        // Functions with source sections are user-visible (not internal/framework)
        return sourceSection == null;
    }

    private static final String[] EMPTY_STRINGS = new String[0];
    private static final int[] EMPTY_INTS = new int[0];

    /**
     * Enable per-call-site cloning so Truffle's auto-splitter can
     * give each call site its own AST clone (task #50/#54).
     *
     * <p>Without this, one physical {@code MezzeFunctionRootNode}
     * hosts every call to a function. Its body's AST nodes carry
     * {@code @Child} + {@code @CompilationFinal} specialization
     * state (see e.g. {@code SeqFoldCallNode}). Cloning isolates
     * that state per call site — helpful for polymorphic call
     * sites, cheap otherwise (splitting is decided by the
     * auto-splitter's cost model).
     *
     * <p>Measured impact: on a 2M-element {@code seq::fold} run
     * 4x sequentially vs 4x in parallel via {@code Async.spawn},
     * the pre-change ratio par:seq was 1.26×; enabling cloning
     * keeps that in the ~1.0–1.3× band. The old "13×" cliff
     * documented in older memory doesn't reproduce on the current
     * pipeline — prior chain-bug and spawn work already closed most
     * of it. Cloning is kept enabled as insurance against future
     * regressions and to help polymorphic hot paths.
     *
     * <p>Full :interpreter:test (5168 tests) passes with cloning on.
     */
    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();

        // Bind closure variables first (slots 0..closureCount-1)
        // Closure values are passed as individual arguments before the record
        for (int i = 0; i < closureCount; i++) {
            if (i < args.length) {
                bindSlot(frame, i, args[i]);
            }
        }

        // FAST PATH: typed-LONG payload — args[closureCount] == LONG_MARKER and
        // args[closureCount+1] is a long[]. Write primitives straight to LONG slots.
        if (parameterCount > 0 && args.length > closureCount + 1
                && args[closureCount] == ScalarLongPayloadMarker.INSTANCE
                && args[closureCount + 1] instanceof long[] longArgs) {
            bindLongPayload(frame, longArgs);
            return runBodyLoop(frame);
        }

        // FAST PATH: typed-DOUBLE payload — symmetric to LONG. Writes to DOUBLE slots.
        if (parameterCount > 0 && args.length > closureCount + 1
                && args[closureCount] == ScalarDoublePayloadMarker.INSTANCE
                && args[closureCount + 1] instanceof double[] doubleArgs) {
            bindDoublePayload(frame, doubleArgs);
            return runBodyLoop(frame);
        }

        // FAST PATH: typed-MIXED payload — args[closureCount] == MIXED_MARKER and
        // the next four slots are paramKinds:byte[], longArgs:long[], doubleArgs:double[],
        // objArgs:Object[] (any of the bucket arrays may be null).
        if (parameterCount > 0 && args.length > closureCount + 4
                && args[closureCount] == ScalarMixedPayloadMarker.INSTANCE
                && args[closureCount + 1] instanceof byte[] mixedKinds) {
            long[] mLong = args[closureCount + 2] instanceof long[] l ? l : null;
            double[] mDouble = args[closureCount + 3] instanceof double[] d ? d : null;
            Object[] mObj = args[closureCount + 4] instanceof Object[] o ? o : null;
            bindMixedPayload(frame, mixedKinds, mLong, mDouble, mObj);
            return runBodyLoop(frame);
        }

        // Detect ABI: scalar (positional values) vs record (single field-named record).
        // Discrimination rules — see ScalarABIMarker:
        //   parameterCount == 0: scalar (no payload). Record ABI passes {} too —
        //     args.length == closureCount + 1 distinguishes (record has the empty rec).
        //   parameterCount == 1: scalar caller writes ScalarABIMarker.INSTANCE before
        //     the scalar value, since arity collides with record ABI here.
        //   parameterCount >= 2: scalar layout has args.length == closureCount + paramCount,
        //     record layout has args.length == closureCount + 1. Disambiguates by arity.
        boolean scalarABI;
        if (parameterCount == 0) {
            // Record ABI passes the empty record at args[closureCount];
            // scalar ABI passes nothing for params (abilityOps may follow).
            scalarABI = args.length == closureCount;
        } else if (parameterCount == 1) {
            // Arity collides (record arg or scalar arg both occupy 1 slot).
            // Caller writes the marker; receiver checks for it.
            scalarABI = args.length > closureCount
                && args[closureCount] == ScalarABIMarker.INSTANCE;
        } else {
            // parameterCount >= 2: disambiguate by arity alone.
            // Record ABI:   args.length == closureCount + 1.
            // Scalar ABI:   args.length == closureCount + parameterCount.
            // Since parameterCount > 1, these arities are distinct regardless of arg values.
            int recordArity = closureCount + 1;
            int scalarArity = closureCount + parameterCount;
            scalarABI = args.length == scalarArity && args.length != recordArity;
        }

        if (scalarABI && parameterCount > 0) {
            // Scalar ABI: positional values starting at args[scalarValuesStart].
            // For paramCount == 1 we wrote a marker, so values start one slot later.
            int valuesStart = (parameterCount == 1) ? closureCount + 1 : closureCount;
            bindScalarValuesAt(frame, args, valuesStart);
        } else if (parameterCount > 0 && args.length > closureCount) {
            // Record ABI: unpack the single record argument by field name
            Object recordArg = args[closureCount];
            if (recordArg instanceof MezzeRecord record) {
                unpackRecordFast(frame, record);
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalArgumentException("Expected record argument for fn=" + name +
                    " paramNames=" + java.util.Arrays.toString(parameterNames) +
                    " closureCount=" + closureCount +
                    " args.length=" + args.length +
                    ", got: " + (recordArg == null ? "null" : recordArg.getClass().getName()));
            }
        }

        return runBodyLoop(frame);
    }

    /**
     * Drive the body through the LoopNode trampoline. Truffle reports the
     * iteration count to the runtime, enabling OSR compilation for hot
     * tail-recursive driver functions. Catch logic for self-tail-calls
     * lives in {@link BodyRepeatingNode#executeRepeatingWithValue}.
     */
    private Object runBodyLoop(VirtualFrame frame) {
        return bodyLoopNode.execute(frame);
    }

    /**
     * One trampoline step:
     * <ul>
     *   <li>Body returns normally → return that value; loop stops.</li>
     *   <li>Body throws {@link TailCallScalarException} → rebind typed
     *       payload (long[]/double[]/mixed) and return
     *       {@link RepeatingNode#CONTINUE_LOOP_STATUS}.</li>
     *   <li>Body throws {@link TailCallException} → rebind from the new
     *       args array (scalar or record ABI) and continue.</li>
     * </ul>
     *
     * <p>Status discipline: anything other than {@code CONTINUE_LOOP_STATUS}
     * (including {@code null}) is treated as the final result. {@code null}
     * is a legal Mezze value (it's how we represent {@code unit}), so we
     * can't use it as the sentinel — Truffle's {@code CONTINUE_LOOP_STATUS}
     * is a private sentinel object guaranteed not to collide.
     */
    private final class BodyRepeatingNode extends Node implements RepeatingNode {

        @Child
        private MezzeNode bodyNode;

        @Child
        private com.oracle.truffle.api.nodes.IndirectCallNode crossFnTailCallNode;

        BodyRepeatingNode(MezzeNode bodyNode) {
            this.bodyNode = bodyNode;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            // We always produce values via executeRepeatingWithValue;
            // executeRepeating is required by the interface but unused.
            throw new IllegalStateException(
                "executeRepeating should not be called; use executeRepeatingWithValue");
        }

        @Override
        public Object executeRepeatingWithValue(VirtualFrame frame) {
            try {
                return bodyNode.execute(frame);
            } catch (TailCallScalarException tse) {
                // Tail call to SELF: rebind frame args, continue the loop.
                // Tail call to ANOTHER function (e.g. lambda body whose only
                // expr is a call to a different function — common when the
                // call is wrapped as TailCallNode by the IR but the callee
                // CallTarget differs from this RootNode's): the SELF-loop
                // trampoline can't absorb that — re-running our body would
                // execute the wrong code. Fall back to a real call so the
                // loop terminates with the callee's result. #285+
                if (tse.callTarget != getCallTarget()) {
                    return invokeCrossFn(tse.callTarget, scalarPayloadToArgs(tse));
                }
                // Typed-payload tail call. fill() / fillMixed() picks mode:
                //   paramKinds != null → mixed buckets
                //   else longArgs != null → all-LONG payload
                //   else doubleArgs != null → all-DOUBLE payload
                if (tse.paramKinds != null) {
                    bindMixedPayload(frame, tse.paramKinds, tse.longArgs, tse.doubleArgs, tse.objArgs);
                } else if (tse.longArgs != null) {
                    bindLongPayload(frame, tse.longArgs);
                } else if (tse.doubleArgs != null) {
                    bindDoublePayload(frame, tse.doubleArgs);
                }
                return CONTINUE_LOOP_STATUS;
            } catch (TailCallException e) {
                // Tail call to SELF: rebind, continue. Tail call to ANOTHER
                // function: invoke it for real so the loop terminates.
                // Without this guard, BodyRepeatingNode would re-execute the
                // wrong body forever (e.g. spawn closure `{} -> f {n}` calls
                // top-level `f` — without the check, we'd re-execute `{} -> f`
                // with f's new args, infinite loop).
                if (e.getCallTarget() != getCallTarget()) {
                    return invokeCrossFn(e.getCallTarget(), e.getArguments());
                }
                // Object-payload tail call. The tail-caller's arg layout
                // starts at index 0 (no closure prefix — closures are stable
                // within a single function's frame).
                Object[] newArgs = e.getArguments();
                if (parameterCount > 0 && newArgs.length > 0) {
                    boolean tailScalarABI;
                    if (parameterCount == 1) {
                        tailScalarABI = newArgs[0] == ScalarABIMarker.INSTANCE;
                    } else {
                        tailScalarABI = !(newArgs[0] instanceof MezzeRecord);
                    }
                    if (tailScalarABI) {
                        int valuesStart = (parameterCount == 1) ? 1 : 0;
                        bindScalarValuesAt(frame, newArgs, valuesStart);
                    } else if (newArgs[0] instanceof MezzeRecord record) {
                        unpackRecordFast(frame, record);
                    }
                }
                return CONTINUE_LOOP_STATUS;
            }
        }

        private Object invokeCrossFn(CallTarget target, Object[] args) {
            if (crossFnTailCallNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                crossFnTailCallNode = insert(
                    com.oracle.truffle.api.nodes.IndirectCallNode.create());
            }
            return crossFnTailCallNode.call(target, args);
        }

        /**
         * Unpack a TailCallScalarException's typed buckets into a single
         * Object[] suitable for IndirectCallNode.call. Only used on the
         * cross-function fallback path where we don't know the callee's
         * exact ABI; boxing is acceptable because this path is rare.
         */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private Object[] scalarPayloadToArgs(TailCallScalarException tse) {
            if (tse.paramKinds != null) {
                int n = tse.paramKinds.length;
                Object[] out = new Object[n];
                int li = 0, di = 0, oi = 0;
                for (int i = 0; i < n; i++) {
                    switch (tse.paramKinds[i]) {
                        case 0:  out[i] = tse.longArgs[li++]; break;
                        case 1:  out[i] = tse.doubleArgs[di++]; break;
                        default: out[i] = tse.objArgs[oi++]; break;
                    }
                }
                return out;
            }
            if (tse.longArgs != null) {
                Object[] out = new Object[tse.longArgs.length];
                for (int i = 0; i < tse.longArgs.length; i++) out[i] = tse.longArgs[i];
                return out;
            }
            if (tse.doubleArgs != null) {
                Object[] out = new Object[tse.doubleArgs.length];
                for (int i = 0; i < tse.doubleArgs.length; i++) out[i] = tse.doubleArgs[i];
                return out;
            }
            return tse.objArgs != null ? tse.objArgs : new Object[0];
        }

        @Override
        public Object initialLoopStatus() {
            return CONTINUE_LOOP_STATUS;
        }

        @Override
        public boolean shouldContinue(Object status) {
            return status == CONTINUE_LOOP_STATUS;
        }
    }

    /** Write a typed long[] payload into LONG-typed param slots.
     *  Used by both the typed-LONG initial entry path and the
     *  typed tail-call exception path. No boxing, no instanceof. */
    @ExplodeLoop
    private void bindLongPayload(VirtualFrame frame, long[] longArgs) {
        for (int i = 0; i < parameterCount; i++) {
            if (i < longArgs.length) {
                frame.setLong(closureCount + i, longArgs[i]);
            }
        }
    }

    /** Symmetric to bindLongPayload for DOUBLE-typed slots. */
    @ExplodeLoop
    private void bindDoublePayload(VirtualFrame frame, double[] doubleArgs) {
        for (int i = 0; i < parameterCount; i++) {
            if (i < doubleArgs.length) {
                frame.setDouble(closureCount + i, doubleArgs[i]);
            }
        }
    }

    /** Mixed-kind rebind: paramKinds[i] tells us which bucket to read for
     *  param i, advancing per-bucket indices as we go. The caller pre-bucketed
     *  values into longArgs/doubleArgs/objArgs in declaration order, so we
     *  walk paramKinds and consume buckets sequentially. */
    @ExplodeLoop
    private void bindMixedPayload(VirtualFrame frame, byte[] paramKinds,
                                  long[] longArgs, double[] doubleArgs, Object[] objArgs) {
        int li = 0, di = 0, oi = 0;
        for (int i = 0; i < parameterCount; i++) {
            if (i >= paramKinds.length) break;
            int slot = closureCount + i;
            byte k = paramKinds[i];
            if (k == 0 /* KIND_LONG */) {
                frame.setLong(slot, longArgs[li++]);
            } else if (k == 1 /* KIND_DOUBLE */) {
                frame.setDouble(slot, doubleArgs[di++]);
            } else {
                frame.setObject(slot, objArgs[oi++]);
            }
        }
    }

    /**
     * Bind {@code parameterCount} positional scalar values starting at
     * {@code args[valuesStart]} into the param slots.
     * Maps args[valuesStart + i] -> frame slot closureCount + i.
     */
    @ExplodeLoop
    private void bindScalarValuesAt(VirtualFrame frame, Object[] args, int valuesStart) {
        for (int i = 0; i < parameterCount; i++) {
            int srcIndex = valuesStart + i;
            int slotIndex = closureCount + i;
            if (srcIndex < args.length) {
                bindSlot(frame, slotIndex, args[srcIndex]);
            }
        }
    }

    /**
     * Fast path: unpack record using cached field indices.
     * After shape stabilizes, this becomes O(1) per field.
     */
    @ExplodeLoop
    private void unpackRecordFast(VirtualFrame frame, MezzeRecord record) {
        Object shape = record.getShape();

        // Check if we can use cached indices
        if (shape == cachedShape && cachedFieldIndices != null) {
            // FAST PATH: Use cached field indices - zero overhead after JIT
            for (int i = 0; i < parameterCount; i++) {
                int fieldIndex = cachedFieldIndices[i];
                int slotIndex = closureCount + i;
                if (fieldIndex >= 0) {
                    Object value = record.getField(fieldIndex);
                    bindSlot(frame, slotIndex, value);
                }
            }
        } else {
            // SLOW PATH: First call or shape changed - compute and cache indices
            CompilerDirectives.transferToInterpreterAndInvalidate();
            unpackRecordSlow(frame, record, shape);
        }
    }

    /**
     * Slow path: compute field indices and cache them.
     * Split into two parts - boundary for index computation, inline for frame binding.
     */
    private void unpackRecordSlow(VirtualFrame frame, MezzeRecord record, Object shape) {
        // Compute indices in boundary method (can't access frame there)
        Object[] result = computeFieldIndicesAndValues(record, shape);
        int[] indices = (int[]) result[0];
        Object[] values = (Object[]) result[1];

        // Bind values to frame slots (must be inline, not boundary)
        for (int i = 0; i < parameterCount; i++) {
            int slotIndex = closureCount + i;
            if (indices[i] >= 0) {
                frame.setObject(slotIndex, values[i]);
            }
        }

        // Cache indices for next call
        this.cachedFieldIndices = indices;
        this.cachedShape = shape;
    }

    /**
     * Compute field indices and extract values - can be @TruffleBoundary.
     */
    @TruffleBoundary
    private Object[] computeFieldIndicesAndValues(MezzeRecord record, Object shape) {
        int[] indices = new int[parameterCount];
        Object[] values = new Object[parameterCount];

        for (int i = 0; i < parameterCount; i++) {
            String paramName = parameterNames[i];
            int fieldIndex = record.getFieldIndex(paramName);
            indices[i] = fieldIndex;
            if (fieldIndex >= 0) {
                values[i] = record.getField(fieldIndex);
            }
        }

        return new Object[] { indices, values };
    }

    /**
     * Bind value to typed slot - JIT eliminates switch after profiling.
     */
    @ExplodeLoop
    private void bindSlot(VirtualFrame frame, int slotIndex, Object value) {
        if (slotIndex < slotTypes.length) {
            int slotType = slotTypes[slotIndex];
            switch (slotType) {
                case 0 -> { // KIND_LONG
                    if (value instanceof Long l) {
                        frame.setLong(slotIndex, l);
                    } else {
                        frame.setObject(slotIndex, value);
                    }
                }
                case 1 -> { // KIND_DOUBLE
                    if (value instanceof Double d) {
                        frame.setDouble(slotIndex, d);
                    } else if (value instanceof Long l) {
                        frame.setDouble(slotIndex, l.doubleValue());
                    } else {
                        frame.setObject(slotIndex, value);
                    }
                }
                case 2 -> { // KIND_BOOLEAN
                    if (value instanceof Boolean b) {
                        frame.setBoolean(slotIndex, b);
                    } else {
                        frame.setObject(slotIndex, value);
                    }
                }
                default -> frame.setObject(slotIndex, value);
            }
        } else {
            frame.setObject(slotIndex, value);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "MezzeFunctionRootNode[" + name + "]";
    }

    public int getClosureCount() {
        return closureCount;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    public String[] getParameterNames() {
        return parameterNames;
    }

    /** Slot-kind ints (KIND_LONG=0, DOUBLE=1, BOOLEAN=2, OBJECT=3) for the
     *  parameter slots, in declaration order. Used by callers (e.g. scalar
     *  tail-call sites) to choose typed payload paths that skip boxing. */
    public int[] getParamSlotKinds() {
        if (parameterCount == 0) return EMPTY_INTS;
        int[] kinds = new int[parameterCount];
        for (int i = 0; i < parameterCount; i++) {
            int idx = closureCount + i;
            kinds[i] = idx < slotTypes.length ? slotTypes[idx] : 3;
        }
        return kinds;
    }

    public MezzeNode getBodyNode() {
        return bodyNode;
    }

    // ========================================================================
    // NodeLibrary export — surfaces the current frame's named slots to the
    // debugger. Chrome DevTools / VS Code calls these when paused so the
    // user sees local bindings in the variables panel.
    //
    // hasScope/getScope are evaluated lazily by the debugger; cost on the
    // normal execution path (no debugger attached) is zero — neither method
    // is reached without an Instrumenter probe.
    // ========================================================================

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean hasScope(@SuppressWarnings("unused") Frame frame) {
        return true;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    Object getScope(Frame frame, @SuppressWarnings("unused") boolean nodeEnter) {
        return new MezzeDebugScope(frame);
    }
}
