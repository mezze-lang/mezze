package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.function.TailCallException;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Thin outer trampoline for BDSL-compiled functions.
 *
 * <p>The BDSL root node's generated {@code execute()} runs the bytecode
 * program. Tail-call ops ({@code TailCallDirectN}, {@code TailCallIndirect})
 * throw {@link TailCallException} — that exception would escape past
 * BDSL's bytecode loop because BDSL has no built-in trampoline.
 *
 * <p><b>Only wrapped when needed.</b>
 * {@code BytecodeCompiler.compile} statically checks whether the IR body
 * contains any {@code isTailCall = true} node; if not, the raw BDSL root
 * is returned directly — no wrapper, no LoopNode indirection, no
 * DirectCallNode per-invocation overhead. Only functions that can throw
 * {@link TailCallException} get the wrapper. Callers of a
 * non-trampolined function are unaffected: cross-fn tail-calls TO it
 * still throw from the caller (whose OWN trampoline catches), so the
 * callee being un-trampolined is fine.
 *
 * <p>This wrapper is what the outside world calls. Its {@code execute()}
 * invokes the BDSL root's CallTarget via a {@link LoopNode}-driven
 * {@link TrampolineRepeatingNode}. When {@link TailCallException} is
 * caught, the loop re-invokes the carried target with its arguments.
 * Deep tail recursion stays stack-flat because the loop replaces stack
 * growth with a re-entrant call.
 *
 * <p>The {@link LoopNode} lets Truffle report iteration counts and
 * enables OSR compilation for hot tail-recursive drivers — same
 * mechanism the AST-side {@code MezzeFunctionRootNode.BodyRepeatingNode}
 * relies on for factorial-and-friends.
 *
 * <p><b>Two-phase construction (for self-recursion).</b> The BDSL builder
 * needs the callee's CallTarget baked as a {@code ConstantOperand} at
 * emit time. For self-recursive functions, the callee is the function
 * being built — so we need the trampoline's CallTarget available BEFORE
 * the BDSL inner exists. Solution: create the trampoline with no inner,
 * register its CallTarget under the function's hash, build the BDSL
 * inner (which references this trampoline as its own callee via
 * self-tail-call), then wire the inner in via {@link #attachInner}.
 *
 * <p>Self-tail-call detection: BDSL bakes the trampoline's outer
 * CallTarget into {@code TailCallDirectN} for self-recursion. At resume
 * time, the trampoline redirects targets equal to its own CallTarget
 * to the inner via a {@link DirectCallNode} — otherwise every
 * self-tail-call would re-enter the outer wrapper and grow the stack.
 */
public final class BdslTrampolineRootNode extends RootNode {

    private RootCallTarget innerTarget;
    private final String name;

    /**
     * ABI-unpack metadata. When {@code parameterCount == 0} and
     * {@code closureCount == 0} the trampoline just forwards args
     * verbatim — same behaviour as before the record-ABI work landed.
     *
     * <p>When {@code parameterCount > 0}, {@link #execute} rewrites the
     * incoming {@code Object[]} into the flat scalar layout the BDSL
     * bytecode expects: {@code [closureValues..., paramValues...]}.
     * Record ABI callers pass a single {@link com.mezze.interpreter.values.MezzeRecord}
     * (or empty record); scalar callers pass values positionally with a
     * marker in the arity-1 case. This mirrors
     * {@code MezzeFunctionRootNode.execute}'s discrimination.
     */
    private final int closureCount;
    private final int parameterCount;
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private final String[] parameterNames;

    /**
     * True iff the inner body contains a tail-call site. Used by
     * {@code BytecodeCompiler.CallDirectN emit} to decide whether a
     * BDSL→BDSL call can bypass this trampoline and target the inner
     * BDSL root directly (skipping the LoopNode + DirectCallNode wrap).
     * When true, callers MUST target the trampoline so
     * {@link TailCallException} thrown from a self-tail-call is caught
     * and re-driven; skipping the trampoline would let the exception
     * escape past the caller.
     */
    private boolean hasTailCalls;

    /**
     * Cached record shape → field index array. Empty until the first
     * record-ABI unpack; when the record's shape matches the cached one,
     * lookup is O(1). Miss → recompute and re-cache (transferring to
     * interpreter). Same pattern as {@code MezzeFunctionRootNode}.
     */
    @CompilerDirectives.CompilationFinal
    private Object cachedShape;
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private int[] cachedFieldIndices;

    @Child
    private LoopNode loop;

    private final int argsSlot;
    private final int targetSlot;
    private final int resultSlot;

    /** Single-phase constructor: used when the inner is already compiled. */
    public BdslTrampolineRootNode(MezzeLanguage language, RootNode inner, String name) {
        this(language, name);
        attachInner(inner);
    }

    /** Legacy two-phase constructor used by pre-registration paths that don't yet know ABI metadata. */
    public BdslTrampolineRootNode(MezzeLanguage language, String name) {
        this(language, name, 0, 0, EMPTY_STRINGS);
    }

    /**
     * Two-phase constructor with ABI metadata: create the trampoline
     * first, register its CallTarget under the function's hash, then
     * compile the inner BDSL root (which sees this trampoline as its
     * own callee for self-tail-calls), then call {@link #attachInner}.
     */
    public BdslTrampolineRootNode(MezzeLanguage language, String name,
                                  int closureCount, int parameterCount,
                                  String[] parameterNames) {
        super(language, makeDescriptor());
        this.name = name;
        this.closureCount = closureCount;
        this.parameterCount = parameterCount;
        this.parameterNames = parameterNames != null ? parameterNames : EMPTY_STRINGS;
        this.argsSlot = 0;
        this.targetSlot = 1;
        this.resultSlot = 2;
    }

    private static final String[] EMPTY_STRINGS = new String[0];

    private static FrameDescriptor makeDescriptor() {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder();
        b.addSlot(FrameSlotKind.Object, "args", null);
        b.addSlot(FrameSlotKind.Object, "target", null);
        b.addSlot(FrameSlotKind.Object, "result", null);
        return b.build();
    }

    /**
     * Wire the BDSL inner root into the trampoline. Idempotent-guarded:
     * calling twice throws. Must be called before {@link #execute}.
     */
    public void attachInner(RootNode inner) {
        attachInner(inner, false);
    }

    /**
     * Wire the BDSL inner root and set the {@link #hasTailCalls} flag
     * so callers can decide whether the trampoline is bypassable.
     *
     * <p>Idempotent under races: two threads may reach this concurrently
     * for the same content-addressed IR (both entered compile() before
     * either published). Since the IR is identical and BDSL emit is
     * deterministic, the losing thread's inner is functionally equivalent
     * — just discard it and keep the first.
     */
    public synchronized void attachInner(RootNode inner, boolean hasTailCalls) {
        if (this.innerTarget != null) return;
        this.innerTarget = inner.getCallTarget();
        this.hasTailCalls = hasTailCalls;
        this.loop = insert(Truffle.getRuntime().createLoopNode(
                new TrampolineRepeatingNode(this)));
    }

    /** True iff the inner body has tail-calls; see {@link #hasTailCalls} field. */
    public boolean hasTailCalls() {
        return hasTailCalls;
    }

    /** #374 — user-visible parameter count. Same value stored at construction
     *  ({@code paramSlots.size()} from the FunctionIR). Used by callers who
     *  need to distinguish a zero-param BDSL thunk from an arg-taking lambda
     *  (e.g. {@code ReadGlobalByHashNode.autoEvaluateThunk}, which must NOT
     *  auto-invoke a lambda). */
    public int getParameterCount() {
        return parameterCount;
    }

    /** #374 — captured-values count baked at construction. */
    public int getClosureCount() {
        return closureCount;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (innerTarget == null) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException(
                    "BdslTrampolineRootNode[" + name + "] executed before attachInner");
        }
        Object[] rawArgs = frame.getArguments();
        // #374 partial-application: if the caller passed a single MezzeRecord
        // (record ABI) and it doesn't contain values for every declared
        // parameter, return a partial-applied MezzeFunction instead of
        // padding missing params with null and running the body. Mirrors
        // MezzeFunctionRootNode / InvokeNode.callWithPartialAppCheck.
        if (parameterCount >= 1
                && rawArgs.length == closureCount + 1
                && rawArgs[closureCount] instanceof com.mezze.interpreter.values.MezzeRecord rec
                && !hasAllParameters(rec)
                && parameterNames.length > 0) {
            return buildPartial(rec, rawArgs);
        }
        Object[] flatArgs = parameterCount == 0
                ? rawArgs
                : normalizeArgs(rawArgs);
        frame.setObject(argsSlot, flatArgs);
        frame.setObject(targetSlot, innerTarget);
        loop.execute(frame);
        return frame.getObject(resultSlot);
    }

    private boolean hasAllParameters(com.mezze.interpreter.values.MezzeRecord rec) {
        for (String p : parameterNames) {
            // Skip hidden dictionary params — the compiler injects these for
            // dictionary passing and they're not present in user-visible
            // records. Matches MezzeFunction.hasAllParameters.
            if (com.mezze.language.ast.MagicParams.INSTANCE.isMagic(p)) continue;
            if (rec.getFieldIndex(p) < 0) return false;
        }
        return true;
    }

    @CompilerDirectives.TruffleBoundary
    private com.mezze.interpreter.values.MezzeFunction buildPartial(
            com.mezze.interpreter.values.MezzeRecord rec, Object[] rawArgs) {
        Object[] closures;
        if (closureCount == 0) {
            closures = new Object[0];
        } else {
            closures = new Object[closureCount];
            System.arraycopy(rawArgs, 0, closures, 0, closureCount);
        }
        com.mezze.interpreter.values.MezzeFunction fn =
                new com.mezze.interpreter.values.MezzeFunction(
                        this.getCallTarget(), name, closures, parameterNames);
        return fn.withPartialArgs(rec);
    }

    /** Package-private hook for the repeating node to re-normalise args on tail-call resume. */
    Object[] normalizeIfNeeded(Object[] args) {
        return parameterCount == 0 ? args : normalizeArgs(args);
    }

    /**
     * Rewrite the incoming arg array into BDSL's flat scalar layout
     * {@code [closureValues..., paramValues...]}. Mirrors the ABI
     * discrimination in {@code MezzeFunctionRootNode.execute}:
     * <ul>
     *   <li>{@code paramCount == 1}: scalar caller writes
     *       {@code ScalarABIMarker.INSTANCE} before the value; record
     *       caller writes the record directly.</li>
     *   <li>{@code paramCount >= 2}: scalar layout is
     *       {@code args.length == closureCount + paramCount}; record
     *       layout is {@code args.length == closureCount + 1}.</li>
     *   <li>{@code paramCount == 0}: handled by the fast path above.</li>
     * </ul>
     *
     * <p>Typed payloads (LONG / DOUBLE / MIXED markers) are not yet
     * handled here — the caller falls back to the record/scalar
     * discrimination. Adding them would let numeric hot loops through
     * BDSL avoid the boxing round-trip. Deferred until we measure need.
     */
    private Object[] normalizeArgs(Object[] args) {
        // Scalar ABI marker for the 1-param collision case.
        if (parameterCount == 1
                && args.length > closureCount
                && args[closureCount] == com.mezze.interpreter.nodes.function.ScalarABIMarker.INSTANCE) {
            // Scalar layout is [closures..., MARKER, value]. Flatten to
            // [closures..., value].
            Object[] flat = new Object[closureCount + 1];
            System.arraycopy(args, 0, flat, 0, closureCount);
            if (args.length > closureCount + 1) {
                flat[closureCount] = args[closureCount + 1];
            }
            return flat;
        }
        if (parameterCount >= 2) {
            // 2+ params: disambiguate by arity. Scalar layout is
            // args.length == closureCount + parameterCount; record
            // layout is args.length == closureCount + 1.
            int scalarArity = closureCount + parameterCount;
            if (args.length == scalarArity) {
                return args; // already flat
            }
        }
        // Record ABI: args[closureCount] holds the MezzeRecord to unpack.
        if (args.length > closureCount
                && args[closureCount] instanceof com.mezze.interpreter.values.MezzeRecord rec) {
            // Edge case (paramCount==1 only): the callee's paramName
            // might not correspond to a field in the record — meaning
            // the callee expects the RAW record as its single arg (its
            // body reads .field via ReadFieldIR). Detect by probing the
            // record for the paramName; if absent, pass the record
            // verbatim.
            if (parameterCount == 1
                    && rec.getFieldIndex(parameterNames[0]) < 0) {
                return args;
            }
            Object[] flat = new Object[closureCount + parameterCount];
            for (int i = 0; i < closureCount; i++) flat[i] = args[i];
            unpackRecord(rec, flat);
            return flat;
        }
        // No record present and not a recognised scalar shape — pass
        // through and let BDSL's LoadArgument throw a clearer error if
        // a param slot is missing.
        return args;
    }

    private void unpackRecord(com.mezze.interpreter.values.MezzeRecord rec, Object[] flat) {
        Object shape = rec.getShape();
        int[] indices = cachedFieldIndices;
        if (shape == cachedShape && indices != null) {
            for (int i = 0; i < parameterCount; i++) {
                int fi = indices[i];
                if (fi >= 0) flat[closureCount + i] = rec.getField(fi);
            }
            return;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        int[] fresh = new int[parameterCount];
        for (int i = 0; i < parameterCount; i++) {
            int fi = rec.getFieldIndex(parameterNames[i]);
            fresh[i] = fi;
            if (fi >= 0) flat[closureCount + i] = rec.getField(fi);
        }
        this.cachedFieldIndices = fresh;
        this.cachedShape = shape;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "BdslTrampolineRootNode[" + name + "]";
    }

    /** The wrapped BDSL root's CallTarget. Exposed for tests + debugging only. */
    public RootCallTarget getInnerTarget() {
        return innerTarget;
    }

    /**
     * One trampoline step. Reads (target, args) from the frame, invokes
     * the target. On normal return: stashes the value in {@code resultSlot}
     * and returns false to stop the loop. On {@link TailCallException}:
     * writes the new (target, args) back to the frame and returns true
     * to continue.
     */
    private static final class TrampolineRepeatingNode extends Node implements RepeatingNode {

        private final BdslTrampolineRootNode owner;
        private final CallTarget outerTarget;
        private final RootCallTarget innerTarget;
        private final int argsSlot;
        private final int targetSlot;
        private final int resultSlot;

        @Child
        private DirectCallNode selfCall;

        @Child
        private IndirectCallNode crossCall;

        TrampolineRepeatingNode(BdslTrampolineRootNode owner) {
            this.owner = owner;
            this.outerTarget = owner.getCallTarget();
            this.innerTarget = owner.innerTarget;
            this.argsSlot = owner.argsSlot;
            this.targetSlot = owner.targetSlot;
            this.resultSlot = owner.resultSlot;
            this.selfCall = DirectCallNode.create(innerTarget);
            this.crossCall = IndirectCallNode.create();
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            Object targetObj;
            Object[] args;
            try {
                targetObj = frame.getObject(targetSlot);
                args = (Object[]) frame.getObject(argsSlot);
            } catch (com.oracle.truffle.api.frame.FrameSlotTypeException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("trampoline frame slot type error", e);
            }
            CallTarget target = (CallTarget) targetObj;
            Object result;
            try {
                if (target == outerTarget || target == innerTarget) {
                    result = selfCall.call(args);
                } else {
                    result = crossCall.call(target, args);
                }
            } catch (TailCallException tce) {
                // Tail-call resume: normalise the new args in case they
                // still carry a caller-side ScalarABIMarker or record.
                // The tail-caller doesn't know whether the target is BDSL
                // (which wants flat args) or AST (which wants scalar/
                // record shape). Since we're about to re-enter innerTarget
                // (or another trampolined target of the same shape), the
                // owner's paramCount + closureCount govern.
                frame.setObject(targetSlot, tce.getCallTarget());
                Object[] rawArgs = tce.getArguments();
                if (tce.getCallTarget() == outerTarget
                        || tce.getCallTarget() == innerTarget) {
                    rawArgs = owner.normalizeIfNeeded(rawArgs);
                }
                frame.setObject(argsSlot, rawArgs);
                return true;
            }
            frame.setObject(resultSlot, result);
            return false;
        }
    }
}
