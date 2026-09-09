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
public final class TrampolineRootNode extends RootNode {

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
    @CompilerDirectives.CompilationFinal
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
    /** Cached dict-fallback functions for dict params with fi < 0 (missing from record). */
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private com.mezze.interpreter.values.MezzeFunction[] cachedDictFallbacks;

    @Child
    private LoopNode loop;

    /**
     * Fast-path direct call node for non-tail-call functions.
     * When {@link #hasTailCalls} is false, execute() calls the inner
     * directly through this node, bypassing the LoopNode + frame-slot
     * machinery entirely. This eliminates ~7 frame-slot accesses per
     * call for functions like fib that are recursive but NOT
     * tail-recursive.
     */
    @Child
    private DirectCallNode directInnerCall;

    private final int argsSlot;
    private final int targetSlot;
    private final int resultSlot;

    /** Single-phase constructor: used when the inner is already compiled. */
    public TrampolineRootNode(MezzeLanguage language, RootNode inner, String name) {
        this(language, name);
        attachInner(inner);
    }

    /** Legacy two-phase constructor used by pre-registration paths that don't yet know ABI metadata. */
    public TrampolineRootNode(MezzeLanguage language, String name) {
        this(language, name, 0, 0, EMPTY_STRINGS);
    }

    /**
     * Two-phase constructor with ABI metadata: create the trampoline
     * first, register its CallTarget under the function's hash, then
     * compile the inner BDSL root (which sees this trampoline as its
     * own callee for self-tail-calls), then call {@link #attachInner}.
     */
    public TrampolineRootNode(MezzeLanguage language, String name,
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
        if (hasTailCalls) {
            this.loop = insert(Truffle.getRuntime().createLoopNode(
                    new TrampolineRepeatingNode(this)));
        } else {
            // No tail calls → fast path: direct call, no LoopNode overhead.
            this.directInnerCall = insert(DirectCallNode.create(this.innerTarget));
        }
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

    public String[] getParameterNames() {
        return parameterNames;
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
                    "TrampolineRootNode[" + name + "] executed before attachInner");
        }
        Object[] rawArgs = frame.getArguments();
        // #389 — partial application entry check (boundary method to
        // prevent PE from following getFieldIndex → JDK internals)
        if (parameterCount >= 2
                && rawArgs.length == closureCount + 1
                && closureCount < rawArgs.length
                && rawArgs[closureCount] instanceof com.mezze.interpreter.values.MezzeRecord rec) {
            Object partial = checkPartialApplication(rec, rawArgs);
            if (partial != null) return partial;
        }
        Object[] flatArgs = parameterCount == 0
                ? rawArgs
                : normalizeArgs(rawArgs);
        // Fast path: no tail calls → direct call to inner, skip LoopNode
        // + frame-slot gymnastics. Saves ~7 frame-slot accesses per call.
        // The PE folds this branch away since hasTailCalls is
        // @CompilationFinal and set once in attachInner.
        if (!hasTailCalls) {
            return directInnerCall.call(flatArgs);
        }
        frame.setObject(argsSlot, flatArgs);
        frame.setObject(targetSlot, innerTarget);
        loop.execute(frame);
        return frame.getObject(resultSlot);
    }

    /**
     * Boundary: check if the incoming record is a partial application
     * (missing some param fields). Returns the partial MezzeFunction if
     * so, or null if all fields are present (normal call).
     *
     * <p>Dict params ({@code __dict_<op>}) are excluded from the check:
     * when a polymorphic function with dict params is called indirectly
     * (through a captured closure), the caller can't supply the dict.
     * Missing dict params are resolved at runtime in {@code unpackRecordSlow}
     * via {@link DictFallbackRootNode} instead of triggering partial
     * application.
     */
    @CompilerDirectives.TruffleBoundary
    private Object checkPartialApplication(
            com.mezze.interpreter.values.MezzeRecord rec, Object[] rawArgs) {
        for (int i = 0; i < parameterCount; i++) {
            if (rec.getFieldIndex(parameterNames[i]) < 0
                    && !com.mezze.language.ast.MagicParams.INSTANCE.isDict(parameterNames[i])) {
                Object[] closures;
                if (closureCount == 0) {
                    closures = null;
                } else {
                    closures = new Object[closureCount];
                    System.arraycopy(rawArgs, 0, closures, 0, closureCount);
                }
                com.mezze.interpreter.values.MezzeFunction fn =
                        new com.mezze.interpreter.values.MezzeFunction(
                                this.getCallTarget(), name, closures, parameterNames);
                return fn.withPartialArgs(rec);
            }
        }
        return null;
    }

    /**
     * Normalize args from caller ABI to flat scalar layout. Public so the
     * cross-fn tail-call bypass path in {@link TrampolineRepeatingNode}
     * and {@link com.mezze.interpreter.nodes.function.MezzeFunctionRootNode}
     * can pre-normalize args before calling the inner BDSL root directly.
     */
    public Object[] normalizeIfNeeded(Object[] args) {
        return parameterCount == 0 ? args : normalizeArgs(args);
    }

    /**
     * Resolve a CallTarget to the inner BDSL root if it belongs to a
     * TrampolineRootNode, for cross-fn tail-call bypass. Returns null
     * if the target is not a trampoline or has no inner attached yet.
     */
    @CompilerDirectives.TruffleBoundary
    public static TrampolineRootNode asTrampolineOrNull(CallTarget target) {
        if (target instanceof RootCallTarget rct) {
            var root = rct.getRootNode();
            if (root instanceof TrampolineRootNode tramp && tramp.innerTarget != null) {
                return tramp;
            }
        }
        return null;
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
            // verbatim. Boundary to keep PE from following into string
            // comparison code at every recursive inlining level.
            if (parameterCount == 1
                    && isSingleParamRawRecord(rec)) {
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

    /**
     * Boundary helper: check if a single-param function received a record
     * that doesn't contain the expected parameter name (meaning the
     * function expects the raw record as its argument, not an unpacked
     * field).
     */
    @CompilerDirectives.TruffleBoundary
    private boolean isSingleParamRawRecord(com.mezze.interpreter.values.MezzeRecord rec) {
        // Dict params missing from the record need dict-fallback resolution
        // in unpackRecordSlow — never treat them as the "raw record" case.
        if (com.mezze.language.ast.MagicParams.INSTANCE.isDict(parameterNames[0])) return false;
        return rec.getFieldIndex(parameterNames[0]) < 0;
    }

    private void unpackRecord(com.mezze.interpreter.values.MezzeRecord rec, Object[] flat) {
        Object shape = rec.getShape();
        int[] indices = cachedFieldIndices;
        if (shape == cachedShape && indices != null) {
            for (int i = 0; i < parameterCount; i++) {
                int fi = indices[i];
                if (fi >= 0) {
                    flat[closureCount + i] = rec.getField(fi);
                } else if (cachedDictFallbacks != null && cachedDictFallbacks[i] != null) {
                    flat[closureCount + i] = cachedDictFallbacks[i];
                }
            }
            return;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        unpackRecordSlow(rec, flat);
    }

    @CompilerDirectives.TruffleBoundary
    private void unpackRecordSlow(com.mezze.interpreter.values.MezzeRecord rec, Object[] flat) {
        int[] fresh = new int[parameterCount];
        com.mezze.interpreter.values.MezzeFunction[] fallbacks = null;
        for (int i = 0; i < parameterCount; i++) {
            int fi = rec.getFieldIndex(parameterNames[i]);
            fresh[i] = fi;
            boolean isDict = com.mezze.language.ast.MagicParams.INSTANCE.isDict(parameterNames[i]);
            if (fi >= 0) {
                Object val = rec.getField(fi);
                if (isDict && !(val instanceof com.mezze.interpreter.values.MezzeFunction)) {
                    // Dict param present but holds a sentinel (e.g. {} from
                    // UnitLitIR when the compiler couldn't resolve the dict at
                    // the call site). Resolve the fallback instead.
                    var fb = resolveDictFallback(parameterNames[i]);
                    flat[closureCount + i] = fb;
                    fresh[i] = -1; // mark as "missing" so cached path uses fallback
                    if (fallbacks == null) fallbacks = new com.mezze.interpreter.values.MezzeFunction[parameterCount];
                    fallbacks[i] = fb;
                } else {
                    flat[closureCount + i] = val;
                }
            } else if (isDict) {
                // Dict param missing from caller record — resolve at runtime.
                // This happens when a polymorphic function with dict params is
                // called indirectly (through a captured closure) and the caller
                // can't inject dicts. Fall back to an intrinsic function that
                // performs the operation directly on primitive types.
                var fb = resolveDictFallback(parameterNames[i]);
                flat[closureCount + i] = fb;
                if (fallbacks == null) fallbacks = new com.mezze.interpreter.values.MezzeFunction[parameterCount];
                fallbacks[i] = fb;
            }
        }
        this.cachedFieldIndices = fresh;
        this.cachedDictFallbacks = fallbacks;
        this.cachedShape = rec.getShape();
    }

    @CompilerDirectives.TruffleBoundary
    private com.mezze.interpreter.values.MezzeFunction resolveDictFallback(String dictParamName) {
        String opName = com.mezze.language.ast.MagicParams.INSTANCE.dictMethodOf(dictParamName);
        if (opName == null) return null;
        var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
        return lang.getDictFallback(opName);
    }

    /**
     * Enable per-call-site cloning (splitting) so Truffle's auto-splitter
     * can give each call site its own copy of this trampoline. Without
     * this, all call sites share specialization state, which bleeds
     * polymorphism across monomorphic sites and blocks inlining.
     *
     * <p>Mirrors {@code MezzeFunctionRootNode.isCloningAllowed()}.
     */
    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "TrampolineRootNode[" + name + "]";
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

        private final TrampolineRootNode owner;
        private final CallTarget outerTarget;
        private final RootCallTarget innerTarget;
        private final int argsSlot;
        private final int targetSlot;
        private final int resultSlot;

        @Child
        private DirectCallNode selfCall;

        /**
         * Cross-function tail-call bypass: instead of calling the
         * target's outer trampoline (which would catch any
         * TailCallException from the target's body before we see it),
         * we call the target's INNER BDSL root directly. This way,
         * any TailCallException thrown from the target's body
         * propagates back to OUR trampoline, keeping the stack flat
         * for mutual recursion (A {@code <->} B).
         *
         * <p>The cached fields resolve the first cross-fn target seen.
         * If the target is a TrampolineRootNode, we cache its inner
         * CallTarget and the TrampolineRootNode itself (for arg
         * normalization). For non-trampoline targets, we fall through
         * to the IndirectCallNode.
         */
        @Child
        private DirectCallNode cachedCrossInner;
        @CompilerDirectives.CompilationFinal
        private CallTarget cachedCrossOuter;
        @CompilerDirectives.CompilationFinal
        private TrampolineRootNode cachedCrossTramp;
        @Child
        private IndirectCallNode crossCall;

        TrampolineRepeatingNode(TrampolineRootNode owner) {
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
                } else if (target == cachedCrossOuter && cachedCrossInner != null) {
                    // Cached cross-fn bypass: normalize + call inner.
                    // The cachedCrossInner null guard handles the rare
                    // race where another thread sees cachedCrossOuter
                    // written but cachedCrossInner not yet adopted — on
                    // miss we fall through to the megamorphic path.
                    Object[] normalized = cachedCrossTramp != null
                            ? cachedCrossTramp.normalizeIfNeeded(args)
                            : args;
                    result = cachedCrossInner.call(normalized);
                } else if (cachedCrossOuter == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    TrampolineRootNode tramp = TrampolineRootNode.asTrampolineOrNull(target);
                    if (tramp != null) {
                        cachedCrossTramp = tramp;
                        cachedCrossInner = insert(DirectCallNode.create(tramp.getInnerTarget()));
                        // Set cachedCrossOuter LAST so any concurrent
                        // reader that matches it will see cachedCrossInner.
                        cachedCrossOuter = target;
                        Object[] normalized = tramp.normalizeIfNeeded(args);
                        result = cachedCrossInner.call(normalized);
                    } else {
                        // Non-trampoline target: call directly (no bypass)
                        cachedCrossInner = insert(DirectCallNode.create(target));
                        cachedCrossOuter = target;
                        result = cachedCrossInner.call(args);
                    }
                } else {
                    // Megamorphic: try to resolve inner for bypass
                    result = crossCallWithBypass(target, args);
                }
            } catch (TailCallException tce) {
                // Tail-call resume: any TailCallException here is either
                // from our own inner (self-tail-call) or from a cross-fn
                // target's inner body (because we bypassed the target's
                // trampoline). In both cases, store the new target and
                // continue — our loop drives the entire tail-call chain.
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

        /**
         * Megamorphic cross-fn call: resolve the target's inner BDSL
         * root if it's a trampoline, normalize, and call via
         * IndirectCallNode. Falls back to calling the outer target
         * directly if it's not a trampoline.
         */
        @CompilerDirectives.TruffleBoundary
        private Object crossCallWithBypass(CallTarget target, Object[] args) {
            TrampolineRootNode tramp = TrampolineRootNode.asTrampolineOrNull(target);
            if (tramp != null) {
                Object[] normalized = tramp.normalizeIfNeeded(args);
                return crossCall.call(tramp.getInnerTarget(), normalized);
            }
            return crossCall.call(target, args);
        }
    }
}
