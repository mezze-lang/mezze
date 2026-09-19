package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.builtin.dsl.NativeHandler;
import com.mezze.interpreter.nodes.builtin.dsl.NativeHandlers;
import com.mezze.interpreter.nodes.builtin.dsl.TypedNativeHandlers;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.truffleir.BlockIR;
import com.mezze.language.truffleir.OrdKind;
import com.mezze.language.truffleir.BoolLitIR;
import com.mezze.language.truffleir.CallDirectIR;
import com.mezze.language.truffleir.CallIndirectIR;
import com.mezze.language.truffleir.CallLocalIR;
import com.mezze.language.truffleir.CallNativeIR;
import com.mezze.language.truffleir.CharLitIR;
import com.mezze.language.truffleir.DoubleLitIR;
import com.mezze.language.truffleir.FieldIR;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.truffleir.IfIR;
import com.mezze.language.truffleir.InlineBinaryOpIR;
import com.mezze.language.truffleir.InterpolatedStringIR;
import com.mezze.language.truffleir.StringPartIR;
import com.mezze.language.truffleir.InlineOp;
import com.mezze.language.truffleir.NativeSignatures;
import com.mezze.language.truffleir.FusedChainIR;
import com.mezze.language.truffleir.SourceIR;
import com.mezze.language.truffleir.StageIR;
import com.mezze.language.truffleir.SinkIR;
import com.mezze.language.truffleir.LongLitIR;
import com.mezze.language.truffleir.CaptureIR;
import com.mezze.language.truffleir.CaseIR;
import com.mezze.language.truffleir.MakeLambdaIR;
import com.mezze.language.truffleir.MakeRecordIR;
import com.mezze.language.truffleir.MakeSeqIR;
import com.mezze.language.truffleir.SeqGetIR;
import com.mezze.language.truffleir.MakeVariantIR;
import com.mezze.language.truffleir.SeqElementIR;
import com.mezze.language.truffleir.MatchIR;
import com.mezze.language.truffleir.NodeIR;
import com.mezze.language.truffleir.PatternIR;
import com.mezze.language.truffleir.CallIndirectScalarIR;
import com.mezze.language.truffleir.DictMethodCallIR;
import com.mezze.language.truffleir.EffectBindingIR;
import com.mezze.language.truffleir.HandleIR;
import com.mezze.language.truffleir.HandlerBindIR;
import com.mezze.language.truffleir.PartialApplyDirectIR;
import com.mezze.language.truffleir.PerformOpIR;
import com.mezze.language.truffleir.FracLitIR;
import com.mezze.language.truffleir.ReadArgIR;
import com.mezze.language.truffleir.ReadCaptureIR;
import com.mezze.language.truffleir.ReadFieldIR;
import com.mezze.language.truffleir.ReadGlobalByHashIR;
import com.mezze.language.truffleir.ReadGlobalFnByHashIR;
import com.mezze.language.truffleir.ReadLocalIR;
import com.mezze.language.truffleir.RecordUpdateIR;
import com.mezze.language.truffleir.StmtIR;
import com.mezze.language.truffleir.StringLitIR;
import com.mezze.language.truffleir.UnitLitIR;
import com.mezze.language.truffleir.WriteLocalIR;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.nodes.RootNode;

import java.util.HashMap;
import java.util.Map;

/**
 * IR → Bytecode DSL lowering pass — Phase 1 walking skeleton.
 *
 * <p>Sibling of {@code TruffleCompiler} (Kotlin, AST) but emits Truffle
 * 25 Bytecode DSL programs. Written in Java on purpose: the annotation-
 * processor-generated {@link MezzeBytecodeRootNodeGen} is produced in
 * the same {@code compileJava} invocation, so the reference resolves
 * without Gradle wiring gymnastics.
 *
 * <p>Phase 1 supported IR subset:
 * <ul>
 *   <li>{@link LongLitIR}, {@link DoubleLitIR}, {@link BoolLitIR},
 *       {@link StringLitIR}, {@link CharLitIR}, {@link UnitLitIR} —
 *       primitive/object constant push</li>
 *   <li>{@link InlineBinaryOpIR} with {@code op = ADD} — long add</li>
 *   <li>{@link ReadLocalIR} — parameter or local read (uses IR slot
 *       layout {@code [captures..., params..., locals...]}; captures
 *       empty in Phase 1)</li>
 *   <li>{@link WriteLocalIR} — local write</li>
 *   <li>{@link IfIR} — branching (via BDSL built-in {@code IfThenElse})</li>
 *   <li>{@link BlockIR} with {@link StmtIR.Let} + {@link StmtIR.Expr}</li>
 *   <li>{@link CallDirectIR} with ANY arity, scalar-ABI, non-tail-call
 *       — via the variadic {@code CallDirectN} custom operation</li>
 *   <li>{@link MakeRecordIR} — all-Object-storage record construction
 *       (rejects {@code __spread__} entries; those go through AST)
 *       via the {@code MakeRecord} variadic operation</li>
 *   <li>{@link ReadFieldIR} — read a field by name from a
 *       {@link com.mezze.interpreter.values.MezzeRecord}, via the
 *       {@code ReadFieldByName} operation (PIC on Shape + megamorphic
 *       fallback)</li>
 *   <li>{@link RecordUpdateIR} — {@code { ...r, x = 1 }} shape via the
 *       {@code UpdateRecord} operation (boundary-fold to a new
 *       all-object record, overrides existing names + extends the
 *       shape with brand-new update names)</li>
 *   <li>{@link MakeVariantIR} — construct a variant, no-payload and
 *       single-payload forms via {@code MakeVariantNoPayload} and
 *       {@code MakeVariantSinglePayload}</li>
 *   <li>{@link MatchIR} — variant-tag + wildcard patterns only. Chain
 *       of {@code IfThenElse} on {@code VariantTagEquals}, with
 *       {@code UnwrapSinglePayload} for single-payload bindings.
 *       No guards / if-lets / literal / record / seq / string-interp
 *       patterns yet.</li>
 *   <li>{@link MakeSeqIR} — build a {@link com.mezze.datastructures.seq.MezzeSeq}
 *       from element values via the {@code MakeSeq} op. No-spread
 *       only; {@code SeqElementIR.Spread} elements ({@code [x, ...rest]})
 *       are Phase 3+.</li>
 *   <li>{@link CallIndirectIR} — call a {@link MezzeFunction} value.
 *       Function expr evaluated, arg expr (typically a MakeRecordIR)
 *       evaluated, then the {@code CallIndirect} op prepends the
 *       function's {@code closureValues} to the arg list before
 *       invoking. No tail-call variant yet.</li>
 *   <li>{@link MakeLambdaIR} — closure construction. Captures are
 *       read from the caller's frame slots (LoadArgument for arg
 *       slots, LoadLocal for local slots) and packed into a
 *       {@link com.mezze.interpreter.values.MezzeFunction}'s
 *       {@code closureValues} array via the {@code MakeLambda} op.
 *       The callee's CallTarget must already be compiled — same
 *       constraint as {@link CallDirectIR}.</li>
 *   <li>{@link CallNativeIR} — scalar-ABI natives (positional typed
 *       args) whose handler doesn't need a Node reference. Handler
 *       resolved once at build time from
 *       {@link NativeHandlers#handlerOf(String)}, baked into the
 *       bytecode as a constant operand via {@code CallNativeScalar}.
 *       Ops requiring a Node (Async, IndirectCallNode-taking) are
 *       Phase 3.</li>
 * </ul>
 *
 * <p>Anything else is rejected by {@link #supports}. Under the BDSL-only
 * migration plan (docs/perf/bytecode-dsl-migration-2026-07.md, task #278)
 * we don't silently fall back to AST; unsupported programs throw
 * {@link UnsupportedOperationException} at emit time.
 *
 * <p>Multi-root strategy for calls: each callee is compiled to its own
 * standalone {@code BytecodeRootNodes}, we grab its
 * {@code getNode(0).getCallTarget()}, then pass that CallTarget as a
 * constant operand to {@code CallDirectN} in the caller. This means
 * mutual recursion isn't supported yet — a caller's compile requires
 * its callees to be compiled first. Phase 2+ will move to a shared
 * multi-root or lazy CallTarget resolution.
 *
 * <p>Not thread-safe. One instance per compilation session.
 */
public final class BytecodeCompiler {

    /**
     * Field names of the record we yield when Path D lowering is on.
     * See docs/perf/bdsl-effects-path-d-2026-07.md. The driver reads
     * these three fields via MezzeRecord.getField to dispatch the op.
     */
    private static final String[] PERFORM_YIELD_RECORD_FIELDS =
            new String[] { "effect_hash", "op_name", "arg" };

    /** Variant tags for Option Some/None, computed once via BuiltinTypes. */
    private static final int SOME_TAG = com.mezze.language.ast.BuiltinTypes.INSTANCE
            .computeVariantTag(com.mezze.language.ast.BuiltinTypes.SOME, null);
    private static final int NONE_TAG = com.mezze.language.ast.BuiltinTypes.INSTANCE
            .computeVariantTag(com.mezze.language.ast.BuiltinTypes.NONE, null);
    /** Cached field name array for Option payload records ({val = x}). */
    private static final String[] VAL_FIELD = new String[] { "val" };

    private final MezzeLanguage language;

    /**
     * #356 — Source cache keyed by file path. Materialising
     * com.oracle.truffle.api.source.Source is IO-heavy (reads the file);
     * cache so repeated compiles of fns from the same module share one.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, com.oracle.truffle.api.source.Source> sourceCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Resolve a Truffle Source for a FunctionIR's source file. Returns null on unreadable files. */
    private com.oracle.truffle.api.source.Source resolveSource(String path) {
        if (path == null || path.isEmpty()) return null;
        var cached = sourceCache.get(path);
        if (cached != null) return cached;
        try {
            var f = new java.io.File(path);
            if (!f.exists()) return null;
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                                     java.nio.charset.StandardCharsets.UTF_8);
            var src = com.oracle.truffle.api.source.Source
                    .newBuilder("mezze", text, f.getName())
                    .uri(f.toURI())
                    .build();
            sourceCache.putIfAbsent(path, src);
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert a {@link com.mezze.language.truffleir.SourceInfo}'s
     * line/col range into a Truffle {@code (charIndex, length)} pair via
     * {@code Source.createSection}. Returns null if any coordinate is
     * out of range (avoids crashing on desugared / synthetic nodes).
     */
    private int[] toCharRange(com.oracle.truffle.api.source.Source source,
                              com.mezze.language.truffleir.SourceInfo si) {
        if (source == null || si == null) return null;
        try {
            var section = source.createSection(
                    si.getStartLine(), si.getStartCol(),
                    si.getEndLine(), si.getEndCol());
            return new int[] { section.getCharIndex(), section.getCharLength() };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cache of already-compiled callee CallTargets, keyed by hash.
     * Populated by {@link #compileWithCallees} before a caller is
     * emitted so its CallDirect2 sites can bake in the callee's target.
     */
    private final Map<Hash, CallTarget> compiledCallTargets = new HashMap<>();

    /**
     * #304 — evict stub CallTargets (LazyStubRootNode) and orphan
     * trampolines (attachInner never called) from the per-BDSL-compiler
     * cache at parse() time. Called by MezzeLanguage.parse() alongside
     * userStubCache.clear() so a stale stub/orphan from a disposed
     * context can't be resolved to on the next test.
     */
    public void invalidateStubs() {
        compiledCallTargets.values().removeIf(ct -> {
            if (ct instanceof com.oracle.truffle.api.RootCallTarget rct) {
                var root = rct.getRootNode();
                if (root instanceof LazyStubRootNode) return true;
                if (root instanceof TrampolineRootNode t
                        && t.getInnerTarget() == null) return true;
            }
            return false;
        });
    }

    /**
     * Remove an unattached trampoline that {@link #compile} registered
     * before crashing mid-emit. Without this cleanup, subsequent
     * {@code supports()} calls find the trampoline as "local" (already
     * in {@link #compiledCallTargets}) and skip the {@link LazyStubRootNode}
     * fallback — the {@code !localMl} guard in the {@code MakeLambdaIR}
     * handler treats local entries as "will be attached later", which is
     * false when the compile that registered them failed.
     *
     * <p>Called by {@code eagerCompileReachable} in the catch block.
     */
    public void evictUnattachedTrampoline(Hash hash) {
        CallTarget ct = compiledCallTargets.get(hash);
        if (ct != null && isUnattachedTrampoline(ct)) {
            compiledCallTargets.remove(hash);
        }
    }

    /**
     * #356 — set by {@link #compile} at the top of each fn compile.
     * Non-null iff BDSL debug instrumentation is on AND the fn's source
     * file could be resolved. Read by {@link #emitNode} to decide
     * whether to emit StatementTag / SourceSection wraps.
     */
    private com.oracle.truffle.api.source.Source currentSource;

    /**
     * #374 — set by {@link #compileEntryFn} while emitting the outermost
     * {@code BlockIR} of {@code __entry__}. Non-empty means "for these
     * StmtIR.Let object identities, wrap the value in a RegisterGlobal op
     * so top-level {@code let x = ...} publishes {@code x} into the
     * enclosing MezzeContext's globals + test-inspection maps".
     * Empty set (the default) leaves normal function-body emit unchanged
     * — every StmtIR.Let stays a pure StoreLocal.
     *
     * <p>Identity map, not by (slot, name), because a nested block inside
     * {@code __entry__.body} may legitimately re-use a slot and we only
     * want the semantic to fire on the outermost statement level.
     */
    private java.util.IdentityHashMap<StmtIR.Let, Boolean> entryTopLevelLets =
            new java.util.IdentityHashMap<>();

    /**
     * #443 Day 10 — slot → let-init map for fusion peel. When emitting a
     * BlockIR, each StmtIR.Let records (slot, RHS) here; when leaving the
     * block, entries are removed. The fusion peel consults this map to
     * resolve a ReadLocalIR into its defining expression so that
     * {@code let xs = seq::range{...}; xs.fold(...)} peels the same as
     * the inline form. Only "stable" defs are recorded — the peel checks
     * that the slot hasn't been reassigned since (single-def scope).
     *
     * <p>Kept as an instance field (not threaded through call args) to
     * avoid churning every emit method signature. Since compilation is
     * single-threaded per compiler instance, this is safe.
     */
    private final java.util.HashMap<Integer, NodeIR> currentLetInits =
            new java.util.HashMap<>();

    // ── Self-tail-loop context ──────────────────────────────────────
    // When a function's only tail-calls are self-recursive scalar-ABI
    // CallDirectIR, we compile the body as a BDSL While loop instead
    // of throwing/catching TailCallException per iteration.  These
    // fields are set during self-tail-loop compilation and cleared
    // afterwards.  emitNode reads them to redirect param reads and
    // rewrite self-tail-call sites.
    private boolean inSelfTailLoop;
    private BytecodeLocal[] selfTailParamLocals;
    private BytecodeLocal selfTailContinueFlag;
    private int selfTailCaptureCount;
    private Hash selfTailHash;

    // ── Local lambda devirtualization ─────────────────────────────────
    // Maps local slot → MakeLambdaIR for `let` bindings that assign a
    // lambda. Used by CallIndirectScalarIR emission to convert indirect
    // calls on known-constant lambdas into direct calls, eliminating
    // the 30×+ overhead of the CallIndirectScalar1 PIC path.
    private final java.util.HashMap<Integer, MakeLambdaIR> localLambdas = new java.util.HashMap<>();

    // ── Captured-lambda devirtualization ──────────────────────────────
    // Pre-analyzed mapping: childFunctionHash → captureIndex → binding.
    // When a function `go` captures a let-bound lambda `mul` from its
    // parent, this map records the relationship so compile() can emit
    // CallDirect instead of CallIndirect for ReadCapture-based calls.
    // Built by preAnalyzeCapturedLambdas() before compileWithCallees().
    private final java.util.HashMap<Hash, java.util.HashMap<Integer, CapturedLambdaBinding>>
            capturedLambdaDevirt = new java.util.HashMap<>();

    // Hash of the function currently being compiled — set at the start
    // of compile() so the CallIndirectScalarIR handler can look up the
    // capturedLambdaDevirt map.
    private Hash currentFunctionHash;

    private static final class CapturedLambdaBinding {
        final Hash lambdaHash;
        final int lambdaCaptureCount;
        CapturedLambdaBinding(Hash lambdaHash, int lambdaCaptureCount) {
            this.lambdaHash = lambdaHash;
            this.lambdaCaptureCount = lambdaCaptureCount;
        }
    }

    /**
     * Optional external CallTarget resolver — used when this compiler
     * is invoked from the runtime path where callee CallTargets already
     * live in {@link MezzeLanguage}'s shared stdlib cache rather than
     * this compiler's local map. Set once at construction. Null means
     * "only look at compiledCallTargets" (the test / standalone mode).
     */
    private final java.util.function.Function<Hash, CallTarget> externalResolver;

    public BytecodeCompiler(MezzeLanguage language) {
        this(language, null);
    }

    public BytecodeCompiler(MezzeLanguage language,
                            java.util.function.Function<Hash, CallTarget> externalResolver) {
        this.language = language;
        this.externalResolver = externalResolver;
    }

    /**
     * True iff {@code target} is a {@link TrampolineRootNode} whose inner
     * BDSL root has not yet been attached. Such a target cannot be
     * executed — {@link TrampolineRootNode#execute} throws
     * "executed before attachInner" — so callers must route via a
     * {@link LazyStubRootNode} instead. This condition arises when the
     * trampoline is pre-registered for self-recursion resolution
     * ({@link #compileWithCallees}) or eagerly published by an outer
     * emit whose callee is still mid-compile (SVM AOT loses the JVM's
     * lucky ordering that hides this bug).
     */
    static boolean isUnattachedTrampoline(CallTarget target) {
        return target instanceof com.oracle.truffle.api.RootCallTarget rct
                && rct.getRootNode() instanceof TrampolineRootNode t
                && t.getInnerTarget() == null;
    }

    /** Look up a callee CallTarget: local map first, then external resolver. */
    private CallTarget resolveCallTarget(Hash hash) {
        CallTarget ct = compiledCallTargets.get(hash);
        if (ct != null) return ct;
        // #391 Step 4b Commit 4 — Option A empirical probe. Design in
        // docs/effects/bdsl-eager-closure-makelambda.md §3–§5. Reframing:
        // closure-adaptation is done inline by CallIndirect via
        // MezzeFunction.getClosureValues() — NOT by the baked CT itself.
        // MakeLambda's ConstantOperand CT is never invoked directly by
        // BDSL; only through the MezzeFunction wrapper (which carries the
        // correct closureValues from the Variadic captures). So baking the
        // eager trampoline CT for BOTH closureless AND closure-bearing is
        // safe. Q2 already proved zero closure-bearing hits at CallDirect
        // sites; the fallback branch was defensive scaffolding.
        if (!"0".equals(System.getenv("MEZZE_EAGER")) && language != null) {
            var slot = language.getSlotTable().get(hash);
            if (slot != null && slot.isPublished()) {
                if ("1".equals(System.getenv("MEZZE_EAGER_DEBUG"))) {
                    var ir = language.lookupFunctionIR(hash);
                    if (ir != null && ir.getCaptureSlots() != null
                            && !ir.getCaptureSlots().isEmpty()) {
                        EAGER_CLOSURE_BEARING_FALLBACK.incrementAndGet();
                    }
                }
                ct = slot.getCallTarget();
                compiledCallTargets.put(hash, ct);
                return ct;
            }
        }
        if (externalResolver != null) {
            ct = externalResolver.apply(hash);
            if (ct != null) {
                // Cache locally so subsequent supports()/emit sees it
                // without re-hitting the external resolver.
                compiledCallTargets.put(hash, ct);
            }
        }
        return ct;
    }

    // #391 Step 4b counters (doc §6 Q1/Q2 data-gathering).
    private static final java.util.concurrent.atomic.AtomicLong
            EAGER_CLOSURE_BEARING_FALLBACK = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong
            MAKELAMBDA_CLOSURELESS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong
            MAKELAMBDA_TOTAL = new java.util.concurrent.atomic.AtomicLong();

    public static long getEagerClosureBearingFallbacks() { return EAGER_CLOSURE_BEARING_FALLBACK.get(); }
    public static long getMakeLambdaClosureless() { return MAKELAMBDA_CLOSURELESS.get(); }
    public static long getMakeLambdaTotal() { return MAKELAMBDA_TOTAL.get(); }

    /**
     * True iff {@code target} is a BDSL-compiled callee. AST callees use
     * {@code MezzeFunctionRootNode}; BDSL callees are either a
     * {@link TrampolineRootNode} wrapper (paramCount &gt; 0 or has
     * tail-calls) or a bare {@link MezzeBytecodeRootNode}-generated
     * root (paramCount == 0 non-tail).
     *
     * <p>Used at emit time to skip caller-side scalar-marker prepend
     * when the callee is BDSL — the marker would be dead weight because
     * {@link TrampolineRootNode#normalizeArgs} strips it anyway.
     * Skipping saves ~5-10 ns per hot arity-1 call.
     */
    private static boolean isBdslCallee(CallTarget target) {
        if (!(target instanceof com.oracle.truffle.api.RootCallTarget rct)) return false;
        var root = rct.getRootNode();
        return root instanceof TrampolineRootNode
                || root instanceof MezzeBytecodeRootNode;
    }

    /**
     * True iff the trampoline carries hidden {@code __dict_<op>} params
     * that need runtime dict-fallback resolution. Bypassing such a
     * trampoline at a CallDirect site passes the caller's raw arg
     * straight to the inner BDSL root, where the dict slot receives the
     * arg value (typically an empty record) instead of the resolved impl
     * function → {@code UnsupportedSpecializationException} at
     * {@code CallIndirect}.
     */
    private static boolean trampolineHasDictParams(TrampolineRootNode tramp) {
        String[] names = tramp.getParameterNames();
        if (names == null) return false;
        for (String name : names) {
            if (com.mezze.language.ast.MagicParams.INSTANCE.isDict(name)) return true;
        }
        return false;
    }

    /**
     * True iff {@code ir} uses only IR node types this compiler can
     * lower. Bail early instead of building a partial BDSL program that
     * would later throw at an unsupported node. Callers MUST also check
     * that any {@link CallDirectIR} target has already been compiled
     * (its Hash present in {@link #compiledCallTargets}); the walker
     * doesn't validate that.
     */
    public boolean supports(FunctionIR ir) {
        // Captures are laid out at the head of the argument list — see
        // MezzeFunctionRootNode's convention: args[0..captureCount-1] are
        // closure values, args[captureCount..] are user parameters. We
        // treat both as LoadArgument slots at emit time.
        int argCount = ir.getCaptureSlots().size() + ir.getParamSlots().size();
        LAST_REJECTION.remove();
        boolean ok = supportsNode(ir.getBody(), argCount);
        if (!ok && STATS_ENABLED) {
            String key = LAST_REJECTION.get();
            if (key == null) key = "<unknown>";
            REJECTION_COUNTS.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
        }
        // NB: LAST_REJECTION is intentionally left set on failure so the
        // caller (MezzeLanguage.bdslCompileError) can name the rejected
        // node. It is cleared at the head of the next supports() call.
        return ok;
    }

    private static final boolean STATS_ENABLED =
            System.getenv("MEZZE_BDSL_STATS") != null
                && !"0".equals(System.getenv("MEZZE_BDSL_STATS"));
    private static final ThreadLocal<String> LAST_REJECTION = new ThreadLocal<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            REJECTION_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Map<String, Long> getRejectionCounts() {
        var out = new java.util.LinkedHashMap<String, Long>();
        REJECTION_COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            CRASH_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Bucket an emit-time throwable by exception type + Mezze caller of
     *  the throwing frame + a short prefix of the exception message. The
     *  first (top) frame is usually the generated Truffle Builder's
     *  failArgument/failState — recording only that collapses all 48
     *  distinct call sites into one bucket. What we want is the Mezze
     *  emit method that CALLED into the Builder, plus enough of the
     *  message to distinguish which Builder invariant it tripped. */
    public static void noteCrash(Throwable t) {
        if (!STATS_ENABLED || t == null) return;
        String type = t.getClass().getSimpleName();
        if (type == null || type.isEmpty()) type = t.getClass().getName();
        StackTraceElement frame = firstMezzeCallerFrame(t);
        String msg = shortMessage(t.getMessage());
        StringBuilder key = new StringBuilder(type);
        if (frame != null) {
            key.append(" @ ").append(shortClassName(frame.getClassName()))
               .append(".").append(frame.getMethodName())
               .append(":").append(frame.getLineNumber());
        }
        if (msg != null) key.append(" [").append(msg).append("]");
        CRASH_COUNTS.computeIfAbsent(key.toString(),
                k -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
    }

    /** First stack frame in com.mezze.interpreter.bytecode.* other than the
     *  auto-generated Truffle Builder itself — that is, the human-written
     *  emit method that made the invalid Builder call. */
    private static StackTraceElement firstMezzeCallerFrame(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            for (StackTraceElement f : cur.getStackTrace()) {
                String cn = f.getClassName();
                if (!cn.startsWith("com.mezze.")) continue;
                if (cn.contains("Gen$") || cn.endsWith("Gen")) continue;
                return f;
            }
        }
        return null;
    }

    private static String shortMessage(String m) {
        if (m == null) return null;
        int nl = m.indexOf('\n');
        if (nl >= 0) m = m.substring(0, nl);
        int cap = 80;
        return m.length() > cap ? m.substring(0, cap) + "…" : m;
    }

    private static String shortClassName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    public static java.util.Map<String, Long> getCrashCounts() {
        var out = new java.util.LinkedHashMap<String, Long>();
        CRASH_COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    private static void markReject(NodeIR node, String extra) {
        // Always record (cheap ThreadLocal set) so supports()==false can
        // name the exact blocking node in the surfaced error, not just a
        // terse "reason=unsupported". Only capture the FIRST (deepest)
        // rejection so we know the actual blocking node type — outer
        // wrappers set it too but we don't overwrite.
        if (LAST_REJECTION.get() == null) {
            String label = (node == null) ? "<null>" : node.getClass().getSimpleName();
            if (extra != null) label += ":" + extra;
            LAST_REJECTION.set(label);
        }
    }

    /**
     * The node label recorded by the most recent {@link #supports} call
     * that returned false, or {@code null} if it succeeded. Set as a
     * side effect of {@link #supports}; read immediately after a false
     * result to surface WHICH IR node shape was rejected. Not thread-safe
     * across interleaved supports() calls beyond the single ThreadLocal.
     */
    public String lastRejectionReason() {
        return LAST_REJECTION.get();
    }

    private boolean supportsNode(NodeIR node, int argCount) {
        if (node instanceof LongLitIR) return true;
        if (node instanceof DoubleLitIR) return true;
        if (node instanceof BoolLitIR) return true;
        if (node instanceof StringLitIR) return true;
        if (node instanceof CharLitIR) return true;
        if (node instanceof UnitLitIR) return true;
        if (node instanceof ReadLocalIR) return true;
        if (node instanceof ReadGlobalByHashIR) return true;
        if (node instanceof ReadGlobalFnByHashIR) return true;
        if (node instanceof FracLitIR) return true;
        if (node instanceof ReadArgIR) return true;
        if (node instanceof ReadCaptureIR) return true;
        if (node instanceof EffectBindingIR) return true;
        if (node instanceof FusedChainIR chain) {
            // Canon-emit contract (#446): canonicalizer must only produce
            // shapes emit can handle. Accept unconditionally here; if emit
            // refuses at runtime, it throws and MezzeLanguage buckets it
            // as emit-crash. Without this branch (removed by oversight
            // when Phase 4b landed), supports() rejects at line 864 and
            // emit is never given a chance — which crashed all
            // fusion-producing user programs like bench/cases/sum_fold.
            //
            // #446 Phase 5 fix: walk into the FusedChainIR's embedded
            // MakeLambdaIR nodes (in stages + sink) so their function hashes
            // get pre-compiled into compiledCallTargets. Without this, the
            // emitter resolves them via LazyStub → TrampolineRootNode whose
            // inner is never attached, crashing with "executed before
            // attachInner". The non-fused path pre-compiles these at the
            // MakeLambdaIR supportsNode handler (below); the fused path
            // must do the same.
            for (var stage : chain.getStages()) {
                if (stage instanceof StageIR.Map ms && !supportsNode(ms.getF(), argCount)) return false;
                if (stage instanceof StageIR.Filter fs && !supportsNode(fs.getP(), argCount)) return false;
            }
            var sink = chain.getSink();
            if (sink instanceof SinkIR.Fold fold) {
                if (!supportsNode(fold.getF(), argCount)) return false;
                if (!supportsNode(fold.getInit(), argCount)) return false;
            }
            if (sink instanceof SinkIR.ForEach fe && !supportsNode(fe.getF(), argCount)) return false;
            if (sink instanceof SinkIR.Any a && !supportsNode(a.getP(), argCount)) return false;
            if (sink instanceof SinkIR.All a && !supportsNode(a.getP(), argCount)) return false;
            if (sink instanceof SinkIR.Find f && !supportsNode(f.getP(), argCount)) return false;
            if (sink instanceof SinkIR.Reduce r && !supportsNode(r.getF(), argCount)) return false;
            // Source expressions (range start/end, opaque/raw-seq source) also
            // need to be walked so their sub-expressions compile cleanly.
            var src = chain.getSource();
            if (src instanceof SourceIR.RangeSeq rs) {
                if (!supportsNode(rs.getStart(), argCount)) return false;
                if (!supportsNode(rs.getEnd(), argCount)) return false;
            }
            if (src instanceof SourceIR.RangeStepSeq rss) {
                if (!supportsNode(rss.getStart(), argCount)) return false;
                if (!supportsNode(rss.getEnd(), argCount)) return false;
                if (!supportsNode(rss.getStep(), argCount)) return false;
            }
            if (src instanceof SourceIR.RawSeq rs && !supportsNode(rs.getXs(), argCount)) return false;
            if (src instanceof SourceIR.RawList rl && !supportsNode(rl.getXs(), argCount)) return false;
            if (src instanceof SourceIR.Opaque os && !supportsNode(os.getExpr(), argCount)) return false;
            // Walk fallback so its MakeLambdaIR hashes get pre-compiled.
            // Without this, emitNode falling back to chain.getFallback()
            // encounters MakeLambdaIR refs whose function wasn't pre-compiled,
            // causing "TrampolineRootNode executed before attachInner".
            if (chain.getFallback() != null && !supportsNode(chain.getFallback(), argCount)) return false;
            return true;
        }
        if (node instanceof PerformOpIR p) {
            // arg is evaluated then handed to PerformD chain-dispatch.
            // The Mut fast path lowers the raw `act` lambda directly (no
            // wrapper record on the stack) to preserve the marker fast
            // path.
            return supportsNode(p.getArg(), argCount);
        }
        if (node instanceof HandleIR h) {
            // Every HandleIR compiles to inline BDSL ops
            // (PushHandlerChain / body / PopHandlerChain wrapped in
            // TryCatchOtherwise with UnwrapAbortValue in the catch branch).
            // See emit for shape. Single-shot semantics apply — handler
            // impls that use multi-shot resume (Generator.AsStream) won't
            // behave correctly, but those live in stdlib functions that
            // already carry their own effect-safety story; if a user
            // writes a multi-shot handler in a BDSL-compiled fn they get
            // fall-through-only semantics (same as PerformD).
            //
            // Partial-discharge Handles that need a MezzeSuspend built
            // around the body continuation — force-able under a wider
            // chain — don't fit this inline pattern (would need a
            // MakeSuspended op capturing bodyNode as a sub-CallTarget).
            // perf loss, correct semantics.
            if (!h.getStaticallyFullyDischarges()) {
                markReject(node, "partial-handle-under-lazy-effects");
                return false;
            }
            for (HandlerBindIR hb : h.getHandlers()) {
                if (hb.getParams() != null && !supportsNode(hb.getParams(), argCount)) {
                    return false;
                }
            }
            return supportsNode(h.getBody(), argCount);
        }
        if (node instanceof WriteLocalIR w) {
            return supportsNode(w.getValue(), argCount);
        }
        if (node instanceof InlineBinaryOpIR bin) {
            // All 15 InlineOps proxy through the AST's DirectXxxNode
            // @Specialization logic — see @OperationProxy annotations
            // on MezzeBytecodeRootNode.
            InlineOp op = bin.getOp();
            boolean unary = op == InlineOp.NEG || op == InlineOp.NOT;
            if (!supportsNode(bin.getLeft(), argCount)) return false;
            if (!unary && !supportsNode(bin.getRight(), argCount)) return false;
            return true;
        }
        if (node instanceof IfIR i) {
            return supportsNode(i.getCond(), argCount)
                && supportsNode(i.getThenBr(), argCount)
                && supportsNode(i.getElseBr(), argCount);
        }
        if (node instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                NodeIR child = (s instanceof StmtIR.Let l) ? l.getValue()
                             : ((StmtIR.Expr) s).getNode();
                if (!supportsNode(child, argCount)) return false;
                // #361 — non-tail Expr stmts that could yield MezzeSuspend
                // are handled at runtime by CheckSuspendedThrow at emit time
                // and an outer TryCatch(SuspendedInBlockException) wrapping
                // the function body (see beginRoot emit). AST's BlockNode
                // wraps remaining stmts in a ComposedBlockThunk on suspend;
                // BDSL propagates the suspended value as the fn's return.
                // Suspend-in-middle-of-block is rare in practice — Path E
                // (LAZY_EFFECTS on) already installs a Handle over most
                // effect uses, so unhandled Perform mid-block is the
                // uncommon shape. No reject here anymore.
            }
            return supportsNode(blk.getResult(), argCount);
        }
        if (node instanceof CallDirectIR c) {
            // Any arity accepted (variadic CallDirectN). Tail-call
            // variant throws TailCallException for the trampoline.
            //
            // Record-ABI: args has exactly one MezzeRecord element; the
            // callee (AST fn) unpacks fields by name. BDSL emits it as
            // a plain 1-arg CallDirectN without a scalar marker — the
            // AST callee's normalizeArgs sees a single record and
            // routes to the record-ABI path.
            //
            // #304 / #391 Step 6: prefer eager SlotTable CT before minting
            // LazyStub. Under MEZZE_EAGER (default on), most callees are
            // pre-published; falling back to LazyStub only for the residual
            // few not yet in the slot table when supportsNode fires.
            // #514: treat unattached trampolines as unresolved when they
            // came from an EXTERNAL source (SlotTable/externalResolver) —
            // those are foreign compiles whose attachInner may race with
            // execute under SVM AOT. A locally-pre-registered trampoline
            // (see compileWithCallees) is safe — its attachInner runs
            // synchronously before the enclosing compile() returns, so
            // any self-tail-call site baking it will find it attached at
            // execute time.
            boolean local = compiledCallTargets.containsKey(c.getTargetHash());
            CallTarget existing = resolveCallTarget(c.getTargetHash());
            boolean needSwap = existing == null
                    || (!local && isUnattachedTrampoline(existing));
            if (needSwap) {
                CallTarget ct = null;
                if (language != null) {
                    com.mezze.interpreter.runtime.SlotEntry slot =
                            language.getSlotTable().get(c.getTargetHash());
                    if (slot != null && slot.isPublished()
                            && !isUnattachedTrampoline(slot.getCallTarget())) {
                        ct = slot.getCallTarget();
                    }
                    if (ct == null) {
                        ct = language.getOrMintUserStub(c.getTargetHash(), "callee");
                    }
                }
                if (ct != null) {
                    compiledCallTargets.put(c.getTargetHash(), ct);
                } else if (existing == null) {
                    markReject(node, "target-not-compiled"); return false;
                }
                // If existing was an unattached trampoline and we couldn't
                // upgrade it (stub not mintable — e.g., language null in
                // tests), keep the trampoline — it may attach before
                // execute. Emit will re-check.
            }
            for (NodeIR arg : c.getArgs()) {
                if (!supportsNode(arg, argCount)) return false;
            }
            return true;
        }
        if (node instanceof MakeRecordIR m) {
            // Records support both the pure-fields case (MakeRecord op)
            // and the spread case (SpreadRecord op — dispatched at emit
            // time based on presence of __spread__ entries).
            for (FieldIR f : m.getFields()) {
                if (!supportsNode(f.getValue(), argCount)) return false;
            }
            return true;
        }
        if (node instanceof ReadFieldIR r) {
            return supportsNode(r.getRecord(), argCount);
        }
        if (node instanceof RecordUpdateIR ru) {
            if (!supportsNode(ru.getRecord(), argCount)) return false;
            for (FieldIR f : ru.getUpdates()) {
                // __spread__ entries now supported via SpreadRecord emit
                // (mirrors MakeRecordIR's spread handling).
                if (!supportsNode(f.getValue(), argCount)) return false;
            }
            return true;
        }
        if (node instanceof MakeVariantIR mv) {
            NodeIR payload = mv.getPayload();
            return payload == null || supportsNode(payload, argCount);
        }
        if (node instanceof InterpolatedStringIR is) {
            for (StringPartIR p : is.getParts()) {
                if (p instanceof StringPartIR.Expr e) {
                    if (!supportsNode(e.getNode(), argCount)) return false;
                    // Suspend-capable parts are wrapped in
                    // CheckSuspendedPassThrough at emit time (see emit
                    // path below), so a MezzeSuspend flowing through
                    // an interp part escapes as SuspendedInBlockException
                    // caught at the fn root's outer TryCatch (#361).
                    // Group B scalar-receiver dispatch (Long.to_dbg{}
                    // etc.) is now handled by tryFoldInspectMethod +
                    // InlineScalarInspect (#346), so the previous blocker
                    // is gone.
                }
            }
            return true;
        }
        if (node instanceof MakeLambdaIR ml) {
            // Captures are slot reads in the caller's frame; they
            // resolve to LoadArgument, LoadLocal, or LoadNullConstant
            // at emit time (see the MakeLambdaIR branch in emitNode).
            //
            // Emit-time-resolved target: fast path uses MakeLambda with
            // CallTarget baked as constant operand.
            //
            // Target-not-compiled: mint a lazy stub via
            // MezzeLanguage.getOrMintUserStub. The stub is a real
            // CallTarget with stable identity — bake-as-constant works
            // — that inflates on first execute (via the AST compile
            // path, bypassing BDSL to avoid re-entering this compiler
            // while it's mid-emit). #304.
            //
            // Recursive-let captures: `let f = {n} -> ...` may capture
            // `f` itself (slot >= argCount, slot for f not yet written
            // when MakeLambda executes). AST handles this via
            // frame.getValue returning null on an unwritten slot; BDSL's
            // LoadLocal throws FrameSlotTypeException on Illegal. We
            // emit LoadNullConstant for those captures — the lambda's
            // captured f will be null, but recursive calls emit a fresh
            // MakeLambda (see TypedASTCompiler.compileVar recursiveLet
            // branch) so the null is never actually read.
            // #391 Step 6: prefer eager SlotTable CT before minting LazyStub
            // (same pattern as CallDirect above).
            // #514: treat unattached trampolines as unresolved when they
            // came from an EXTERNAL source — see matching notes at the
            // CallDirectIR supports path.
            boolean localMl = compiledCallTargets.containsKey(ml.getFunctionHash());
            CallTarget existingMl = resolveCallTarget(ml.getFunctionHash());
            boolean needSwapMl = existingMl == null
                    || (!localMl && isUnattachedTrampoline(existingMl));
            if (needSwapMl) {
                CallTarget ct = null;
                if (language != null) {
                    com.mezze.interpreter.runtime.SlotEntry slot =
                            language.getSlotTable().get(ml.getFunctionHash());
                    if (slot != null && slot.isPublished()
                            && !isUnattachedTrampoline(slot.getCallTarget())) {
                        ct = slot.getCallTarget();
                    }
                    if (ct == null) {
                        ct = language.getOrMintUserStub(ml.getFunctionHash(), "lambda");
                    }
                }
                if (ct != null) {
                    compiledCallTargets.put(ml.getFunctionHash(), ct);
                } else if (existingMl == null) {
                    markReject(node, "target-not-compiled");
                    return false;
                }
                // Same as CallDirect: keep unattached trampoline if we
                // couldn't upgrade (language null in tests). Emit re-checks.
            }
            return true;
        }
        if (node instanceof MakeSeqIR ms) {
            // The 63 belongs to MakeSeqWithSpread, which packs its isSpread
            // flags into a long. A literal with NO spread is emitted through
            // plain MakeSeq, a variadic with no such limit — so applying the
            // cap to every literal rejected `[1, 2, … 100]`, which has nothing
            // to pack. It cost four tests: the comment this replaces said
            // larger literals "fall back to AST", and that fallback was deleted
            // with the AST tree, turning a quiet degrade into a hard failure.
            boolean hasSpread = false;
            for (SeqElementIR el : ms.getElements()) {
                if (el instanceof SeqElementIR.Spread) { hasSpread = true; break; }
            }
            if (hasSpread && ms.getElements().size() > 63) {
                markReject(node, "spread-over-63");
                return false;
            }
            for (SeqElementIR el : ms.getElements()) {
                if (el instanceof SeqElementIR.Spread sp) {
                    if (!supportsNode(sp.getNode(), argCount)) return false;
                    continue;
                }
                SeqElementIR.Regular reg = (SeqElementIR.Regular) el;
                if (!supportsNode(reg.getNode(), argCount)) return false;
            }
            return true;
        }
        if (node instanceof SeqGetIR sg) {
            return supportsNode(sg.getSeq(), argCount)
                && supportsNode(sg.getIndex(), argCount);
        }
        if (node instanceof CallIndirectIR ci) {
            return supportsNode(ci.getFunction(), argCount)
                && supportsNode(ci.getArg(), argCount);
        }
        if (node instanceof com.mezze.language.truffleir.InspectIR ins) {
            // #394: type-driven Inspect — supported iff its receiver is.
            return supportsNode(ins.getValue(), argCount);
        }
        if (node instanceof CallIndirectScalarIR ci) {
            // Synthesized as CallIndirect(function, MakeRecord(...)) —
            // we lose the InvokeScalarNNode typed-payload fast path but
            // gain BDSL routing (record-ABI dispatch remains correct).
            if (!supportsNode(ci.getFunction(), argCount)) return false;
            for (NodeIR arg : ci.getArgs()) {
                if (!supportsNode(arg, argCount)) return false;
            }
            return true;
        }
        if (node instanceof DictMethodCallIR dm) {
            // Dict method call = read impl fn from a slot, invoke with arg.
            // Synthesized at emit time as (LoadArgument/LoadLocal, arg,
            // CallIndirect).
            return supportsNode(dm.getArg(), argCount);
        }
        if (node instanceof PartialApplyDirectIR pa) {
            // Non-tail-call only: tail-call variants of partial-apply are
            // niche and BDSL's MonomorphicCall op doesn't yet participate
            // in the trampoline.
            if (pa.isTailCall()) { markReject(node, "tail-call"); return false; }
            for (FieldIR f : pa.getPartialFields()) {
                if ("__spread__".equals(f.getName())) { markReject(node, "spread"); return false; }
                if (!supportsNode(f.getValue(), argCount)) return false;
            }
            return true;
        }
        if (node instanceof CallLocalIR cl) {
            return supportsNode(cl.getArg(), argCount);
        }
        if (node instanceof CallNativeIR cn) {
            // Scalar-ABI natives — both regular and needsNode variants
            // (Async, HOF-callback consumers) are supported. The emit
            // side dispatches to CallNativeScalar or
            // CallNativeScalarWithNode based on needsNode.
            if (!cn.isScalarABI()) {
                // Record-ABI fallback path: any registered NativeDispatch
                // handler is accepted. Body still runs through BDSL; the
                // native call itself pays the record-unpack tax but
                // dispatches via the same handler table as the AST.
                if (!com.mezze.interpreter.nodes.builtin.NativeDispatch.isNativeFunction(cn.getName())) {
                    markReject(node, "no-handler:" + cn.getName()); return false;
                }
                if (!supportsNode(cn.getArg(), argCount)) return false;
                return true;
            }
            if (NativeHandlers.INSTANCE.handlerOf(cn.getName()) == null) {
                markReject(node, "no-handler:" + cn.getName()); return false;
            }
            for (NodeIR arg : cn.getArgs()) {
                if (!supportsNode(arg, argCount)) return false;
            }
            return true;
        }
        if (node instanceof MatchIR m) {
            if (!supportsNode(m.getScrutinee(), argCount)) return false;
            // Phase 2 match subset: variant-tag + wildcard patterns
            // only, no guards, no if-let bindings, no record/literal/
            // seq/string-interp patterns. Wildcard is only allowed as
            // the trailing case (canonical "match Some / None / _" shape),
            // AND the trailing case MUST be a wildcard — no "throw on
            // fall-through" op yet, so we can't compile exhaustive-
            // by-coverage matches without a wildcard until Phase 3.
            var cases = m.getCases();
            if (cases.isEmpty()) { markReject(node, "empty-cases"); return false; }
            // Determine the effective terminal case: emitMatchChain
            // terminates at the FIRST Wildcard or Record pattern that
            // is TRULY unconditional (no guard, no iflet bindings).
            // A guard or iflet can fall through, so cases after remain
            // reachable. Without this both-conditions check, `k if ...`
            // (Wildcard-shape with guard) would eat the fallthrough
            // case at supports-time and emit would then drop it.
            int effectiveEnd = cases.size();
            for (int i = 0; i < cases.size(); i++) {
                CaseIR ci = cases.get(i);
                PatternIR p = ci.getPattern();
                boolean unconditional = (p instanceof PatternIR.Wildcard || p instanceof PatternIR.Record)
                        && ci.getGuard() == null
                        && ci.getIfLetBindings().isEmpty();
                if (unconditional) {
                    effectiveEnd = i + 1;
                    break;
                }
            }
            // Trailing case (of the effective, dead-code-trimmed list):
            // Wildcard/Record → truly unconditional. Variant/Literal/Seq/
            // StringInterp → refutable, so we emit a NonExhaustiveMatch
            // throw as the tail.
            {
                PatternIR lastPat = cases.get(effectiveEnd - 1).getPattern();
                if (!(lastPat instanceof PatternIR.Wildcard)
                    && !(lastPat instanceof PatternIR.Record)
                    && !(lastPat instanceof PatternIR.Variant)
                    && !(lastPat instanceof PatternIR.Literal)
                    && !(lastPat instanceof PatternIR.Seq)
                    && !(lastPat instanceof PatternIR.StringInterp)) {
                    markReject(node, "non-terminal-last:" + lastPat.getClass().getSimpleName());
                    return false;
                }
            }
            for (int i = 0; i < effectiveEnd; i++) {
                CaseIR c = cases.get(i);
                if (c.getGuard() != null) {
                    if (!supportsNode(c.getGuard(), argCount)) return false;
                }
                for (var ilb : c.getIfLetBindings()) {
                    if (!supportsNode(ilb.getExpr(), argCount)) return false;
                    PatternIR ilpat = ilb.getPattern();
                    if (ilpat instanceof PatternIR.Variant iv) {
                        // Fine — variant iflet: match tag, extract fields.
                    } else if (ilpat instanceof PatternIR.Record) {
                        // Fine — unconditional bind.
                    } else if (ilpat instanceof PatternIR.Wildcard) {
                        // Fine — unconditional bind to fieldSlots[0] if present.
                    } else {
                        markReject(node, "iflet:" + ilpat.getClass().getSimpleName());
                        return false;
                    }
                }
                PatternIR pat = c.getPattern();
                if (pat instanceof PatternIR.Variant v) {
                    // Supported: any number of fieldNames — each becomes
                    // a ReadFieldByName on the unwrapped payload record.
                    // supports() defers to the runtime for record-shape
                    // presence; missing fields ClassCast or throw.
                } else if (pat instanceof PatternIR.Literal lit) {
                    if (!supportsNode(lit.getValue(), argCount)) return false;
                } else if (pat instanceof PatternIR.Record) {
                    // Record pattern is unconditional; if guarded or
                    // iflet-bound, emit routes through
                    // emitCaseGuardsAndBody which handles fallthrough
                    // to cases[i+1] just like Variant.
                } else if (pat instanceof PatternIR.Seq) {
                    // Fine — length check + element extract, optional rest.
                } else if (pat instanceof PatternIR.Wildcard) {
                    // Same as Record — emit delegates to
                    // emitCaseGuardsAndBody after slot-binding, so
                    // guard/iflet work identically.
                } else if (pat instanceof PatternIR.StringInterp) {
                    // #360 — MatchStringInterp op runs the same
                    // recursive matcher as AST's MatchBranchNode, emits
                    // captures to their slots + falls through on null.
                } else {
                    markReject(node, "pat:" + pat.getClass().getSimpleName());
                    return false;
                }
                if (!supportsNode(c.getBody(), argCount)) return false;
            }
            return true;
        }
        markReject(node, null);
        return false;
    }

    // ── Captured-lambda pre-analysis ────────────────────────────────
    // Scan each function's body to discover when a child MakeLambdaIR
    // captures a let-bound lambda from the parent. Records the binding
    // in capturedLambdaDevirt so compile() can devirtualize
    // CallIndirect(ReadCapture(idx)) → CallDirect.

    /**
     * Pre-analyze all functions to build the {@link #capturedLambdaDevirt}
     * map. Must be called before any {@link #compile(FunctionIR)} call.
     */
    public void preAnalyzeCapturedLambdas(java.util.List<FunctionIR> functions) {
        capturedLambdaDevirt.clear();
        for (FunctionIR fn : functions) {
            // Step 1: find slot → lambda-hash for let-bound lambdas
            var lambdaSlots = new java.util.HashMap<Integer, Hash>();
            var lambdaCaptureCounts = new java.util.HashMap<Integer, Integer>();
            collectLambdaBindings(fn.getBody(), lambdaSlots, lambdaCaptureCounts);

            // Step 2: find MakeLambdaIR nodes whose captures reference
            //         lambda slots — those are captured-lambda bindings.
            collectCapturedLambdas(fn.getBody(), lambdaSlots, lambdaCaptureCounts);
        }
    }

    /**
     * Walk the IR tree to find {@code StmtIR.Let(slot, MakeLambdaIR(...))}
     * bindings. Records slot → lambda hash and slot → lambda capture count.
     */
    private void collectLambdaBindings(NodeIR node,
            java.util.Map<Integer, Hash> lambdaSlots,
            java.util.Map<Integer, Integer> lambdaCaptureCounts) {
        if (node instanceof BlockIR blk) {
            for (StmtIR stmt : blk.getStmts()) {
                if (stmt instanceof StmtIR.Let l && l.getValue() instanceof MakeLambdaIR ml) {
                    lambdaSlots.put(l.getSlot(), ml.getFunctionHash());
                    lambdaCaptureCounts.put(l.getSlot(), ml.getCaptures().size());
                }
                // Recurse into the value to find nested bindings
                collectLambdaBindings(
                        stmt instanceof StmtIR.Let let ? let.getValue() : ((StmtIR.Expr) stmt).getNode(),
                        lambdaSlots, lambdaCaptureCounts);
            }
            collectLambdaBindings(blk.getResult(), lambdaSlots, lambdaCaptureCounts);
        } else if (node instanceof IfIR ie) {
            collectLambdaBindings(ie.getCond(), lambdaSlots, lambdaCaptureCounts);
            collectLambdaBindings(ie.getThenBr(), lambdaSlots, lambdaCaptureCounts);
            collectLambdaBindings(ie.getElseBr(), lambdaSlots, lambdaCaptureCounts);
        }
        // MakeLambdaIR, calls, etc. don't contain nested let bindings
        // that would be in THIS function's scope — inner lambdas are
        // separate FunctionIRs.
    }

    /**
     * Walk the IR tree to find {@code MakeLambdaIR} nodes that capture
     * slots which are known lambda bindings. Records in
     * {@link #capturedLambdaDevirt}.
     */
    private void collectCapturedLambdas(NodeIR node,
            java.util.Map<Integer, Hash> lambdaSlots,
            java.util.Map<Integer, Integer> lambdaCaptureCounts) {
        if (node instanceof MakeLambdaIR ml) {
            java.util.HashMap<Integer, CapturedLambdaBinding> captureMap = null;
            for (int i = 0; i < ml.getCaptures().size(); i++) {
                CaptureIR cap = ml.getCaptures().get(i);
                Hash lambdaHash = lambdaSlots.get(cap.getSlot());
                if (lambdaHash != null) {
                    if (captureMap == null) captureMap = new java.util.HashMap<>();
                    captureMap.put(i, new CapturedLambdaBinding(
                            lambdaHash,
                            lambdaCaptureCounts.get(cap.getSlot())));
                }
            }
            if (captureMap != null) {
                capturedLambdaDevirt.put(ml.getFunctionHash(), captureMap);
            }
            // Don't recurse into MakeLambdaIR's children — captures are
            // slot references, not nested IR.
            return;
        }
        if (node instanceof BlockIR blk) {
            for (StmtIR stmt : blk.getStmts()) {
                NodeIR val = stmt instanceof StmtIR.Let l ? l.getValue() : ((StmtIR.Expr) stmt).getNode();
                collectCapturedLambdas(val, lambdaSlots, lambdaCaptureCounts);
            }
            collectCapturedLambdas(blk.getResult(), lambdaSlots, lambdaCaptureCounts);
        } else if (node instanceof IfIR ie) {
            collectCapturedLambdas(ie.getCond(), lambdaSlots, lambdaCaptureCounts);
            collectCapturedLambdas(ie.getThenBr(), lambdaSlots, lambdaCaptureCounts);
            collectCapturedLambdas(ie.getElseBr(), lambdaSlots, lambdaCaptureCounts);
        } else if (node instanceof CallIndirectScalarIR ci) {
            collectCapturedLambdas(ci.getFunction(), lambdaSlots, lambdaCaptureCounts);
            for (NodeIR arg : ci.getArgs()) {
                collectCapturedLambdas(arg, lambdaSlots, lambdaCaptureCounts);
            }
        }
        // Other node types don't contain MakeLambdaIR in positions
        // relevant to this analysis.
    }

    /**
     * Convenience: compile a list of {@code FunctionIR}s in dependency
     * order (callees first). Populates {@link #compiledCallTargets} as
     * each compiles, so later entries' CallDirectIRs resolve. Returns
     * the compiled {@link RootNode}s in the same order.
     *
     * <p>Caller is responsible for ordering — this method doesn't
     * topologically sort. Passing them in a bad order gets a clear
     * failure from {@link #supports} on the mis-ordered function.
     */
    public java.util.List<RootNode> compileWithCallees(java.util.List<FunctionIR> functions) {
        preAnalyzeCapturedLambdas(functions);
        var out = new java.util.ArrayList<RootNode>(functions.size());
        for (FunctionIR ir : functions) {
            // Pre-register a placeholder trampoline ONLY when this
            // function has a tail call in its body — that's the only case
            // where the body can self-reference (via a self-tail-call whose
            // CallDirectIR needs the target-hash resolved during
            // supports()/emit()). Non-tail-calling functions can't
            // self-reference in a way that needs pre-registration.
            if (hasTailCall(ir.getBody())) {
                final int cc = ir.getCaptureSlots().size();
                final int pc = ir.getParamSlots().size();
                final String[] pn = ir.getParamNames().toArray(new String[0]);
                compiledCallTargets.computeIfAbsent(ir.getHash(),
                        h -> new TrampolineRootNode(
                                language, ir.getName(), cc, pc, pn).getCallTarget());
            }
            if (!supports(ir)) {
                throw new IllegalArgumentException(
                    "BytecodeCompiler cannot lower FunctionIR '" + ir.getName()
                    + "' — it uses an unsupported IR node type "
                    + "(or references a not-yet-compiled callee).");
            }
            RootNode root = compile(ir);
            compiledCallTargets.put(ir.getHash(), root.getCallTarget());
            out.add(root);
        }
        return out;
    }

    /**
     * #374 entry-point compile. Same as {@link #compile} but marks the
     * immediate {@code BlockIR} statements as "top-level" — every
     * {@code StmtIR.Let} in {@code __entry__.body}'s outermost block
     * publishes to MezzeContext globals + test-inspection maps via the
     * BDSL RegisterGlobal op (mirrors the AST
     * {@code WriteLocalWithContextNode} semantics).
     *
     * <p>Nested blocks inside handlers, lambdas, match arms, etc. still
     * write frame slots only — RegisterGlobal fires only for the very
     * top-level lets recorded here by identity.
     */
    public RootNode compileEntryFn(FunctionIR ir) {
        entryTopLevelLets.clear();
        if (ir.getBody() instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                if (s instanceof StmtIR.Let l) {
                    entryTopLevelLets.put(l, Boolean.TRUE);
                }
            }
        }
        try {
            return compile(ir);
        } finally {
            entryTopLevelLets.clear();
        }
    }

    /**
     * Lower {@code ir} to a BDSL root node. Precondition:
     * {@link #supports} returned true and any callees are already in
     * {@link #compiledCallTargets}. Violating either throws.
     */
    public RootNode compile(FunctionIR ir) {
        // Per-function scope: clear local lambda tracking so entries from
        // a previous compile() don't pollute this function's slot namespace.
        localLambdas.clear();
        currentFunctionHash = ir.getHash();

        // Frame layout [captures..., params..., locals...] — captures
        // and params both come from LoadArgument in the closure calling
        // convention (see MezzeFunctionRootNode).
        final int argCount = ir.getCaptureSlots().size() + ir.getParamSlots().size();
        final int localCount = ir.getLocalSlots().size();

        // Wrap the raw BDSL root in a trampoline when EITHER:
        //   (a) the body has tail calls (need the LoopNode + catch loop),
        //   OR
        //   (b) the function has parameters (need the outer node to
        //       normalise record ABI into the flat scalar layout BDSL
        //       expects — see TrampolineRootNode.normalizeArgs).
        // Zero-param zero-tail-call bodies skip the wrapper entirely.
        final boolean hasTail = hasTailCall(ir.getBody());
        final int captureCount = ir.getCaptureSlots().size();
        final int paramCount = ir.getParamSlots().size();
        final boolean needsTrampoline = hasTail || paramCount > 0;

        // Self-tail-loop: when every tail-call site is a scalar-ABI
        // CallDirectIR targeting this function's own hash, compile the
        // body as a BDSL While loop instead of using the trampoline's
        // LoopNode + TailCallException.  This eliminates exception
        // allocation, throw/catch overhead, and re-entry through
        // DirectCallNode per iteration — the entire loop compiles to
        // a single Graal compilation unit.
        final boolean selfTailLoop = hasTail && paramCount > 0
                && allTailCallsAreSelfScalar(ir.getBody(), ir.getHash());
        // Two-phase construction to support self-recursion:
        //   1. Create (or reuse a pre-registered) trampoline WITHOUT its
        //      inner, register its CallTarget under ir.getHash() so
        //      self-tail-calls in the body resolve to it during BDSL
        //      emission (baked as a ConstantOperand of TailCallDirectN).
        //   2. Build the BDSL inner.
        //   3. Wire the inner in via attachInner.
        // The trampoline's TrampolineRepeatingNode redirects self-target
        // TailCallExceptions to a DirectCallNode on the inner, so
        // self-recursion stays stack-flat.
        //
        // Pre-registration: compileWithCallees may have already registered
        // a placeholder trampoline before calling supports() so mutual /
        // self references resolve. Reuse that trampoline here so the
        // CallTarget baked into TailCallDirectN matches the one that will
        // dispatch at runtime.
        // If this function has tail calls, allocate the trampoline first
        // and register its CallTarget under ir.getHash() so self-tail-calls
        // in the body resolve to it during BDSL emission. Otherwise skip.
        //
        // Note: even if THIS function doesn't tail-call itself, callers
        // of it may tail-call to it. Those callers throw TailCallException
        // carrying THIS function's CallTarget, caught by the caller's own
        // trampoline. So the "no tail call in body" fast path is safe —
        // the caller's trampoline handles the cross-fn case regardless of
        // whether the callee is trampolined.
        TrampolineRootNode trampoline = null;
        if (needsTrampoline) {
            CallTarget registered = compiledCallTargets.get(ir.getHash());
            TrampolineRootNode existing = null;
            if (registered instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof TrampolineRootNode t
                    && t.getInnerTarget() == null) {
                existing = t;
            }
            String[] paramNames = ir.getParamNames().toArray(new String[0]);
            if (existing != null) {
                trampoline = existing;
            } else {
                trampoline = new TrampolineRootNode(
                        language, ir.getName(), captureCount, paramCount, paramNames);
                compiledCallTargets.put(ir.getHash(), trampoline.getCallTarget());
            }
        }

        // #356 — when a debugger is attached, use COMPLETE so source
        // sections, statement tags and named locals materialize. Off by
        // default keeps the hot path lean (no per-op source bytes).
        final boolean debug = MezzeLanguage.isBdslDebugInstrumentation();
        BytecodeConfig config = debug ? BytecodeConfig.COMPLETE : BytecodeConfig.DEFAULT;
        final com.oracle.truffle.api.source.Source source =
                debug ? resolveSource(ir.getSourceFile()) : null;
        final int[] rootRange = (source != null)
                ? toCharRange(source, ir.getSourceSection())
                : null;
        this.currentSource = source;
        var nodes = MezzeBytecodeRootNodeGen.create(language, config, b -> {
            if (source != null) {
                b.beginSource(source);
                if (rootRange != null) {
                    b.beginSourceSection(rootRange[0], rootRange[1]);
                }
            }
            b.beginRoot();

            // Allocate a BDSL local for each IR local slot. The IR uses
            // a flat frame layout [captures..., params..., locals...]
            // with slot numbers indexing into it. Under BDSL params
            // come from LoadArgument(i) and locals from BytecodeLocal
            // objects — we translate at emit time.
            //
            // #356 — pass the source-level local name to createLocal so
            // debugger scopes surface a "Locals" section with the
            // user's names, matching the AST path
            // (PipelineRunner.compileFromIR line 246).
            BytecodeLocal[] localMap = new BytecodeLocal[localCount];
            java.util.List<String> localNames = ir.getLocalNames();
            for (int i = 0; i < localCount; i++) {
                String name = (i < localNames.size()) ? localNames.get(i) : ("l" + i);
                localMap[i] = b.createLocal(name, null);
            }

            // #359 — when a debugger is attached (debug=true), allocate
            // shadow named BDSL locals for each capture + param and
            // copy the arg into the shadow at function entry. BDSL body
            // emit still reads LoadArgument(i) for these — the shadow
            // is write-only from body's perspective, so no runtime cost
            // after entry. Makes params/captures visible in the DAP
            // Locals scope alongside the user's `let` locals.
            //
            // Safe under the DEBUG_EPOCH cache-invalidation machinery
            // in MezzeLanguage.compileOne — flag flips clear all
            // caches so we never mix debug/non-debug CallTargets.
            java.util.List<String> paramNamesIR = ir.getParamNames();
            java.util.List<String> captureNamesIR = ir.getCaptureNames();
            if (debug) {
                BytecodeLocal[] captureShadows = new BytecodeLocal[captureCount];
                for (int i = 0; i < captureCount; i++) {
                    String name = (i < captureNamesIR.size())
                            ? captureNamesIR.get(i) : ("_capture_" + i);
                    captureShadows[i] = b.createLocal(name, null);
                }
                BytecodeLocal[] paramShadows = new BytecodeLocal[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    String name = (i < paramNamesIR.size())
                            ? paramNamesIR.get(i) : ("_param_" + i);
                    paramShadows[i] = b.createLocal(name, null);
                }
                for (int i = 0; i < captureCount; i++) {
                    b.beginStoreLocal(captureShadows[i]);
                    b.emitLoadArgument(i);
                    b.endStoreLocal();
                }
                for (int i = 0; i < paramCount; i++) {
                    b.beginStoreLocal(paramShadows[i]);
                    b.emitLoadArgument(captureCount + i);
                    b.endStoreLocal();
                }
            }

            // #361 — wrap body in TryCatch(SuspendedInBlockException).
            // On catch, extract the MezzeSuspend via UnwrapSuspendedInBlock
            // and return it as the fn's result. The exception is thrown by
            // CheckSuspendedThrow emitted after non-tail block Expr stmts
            // whose result could yield suspended (see BlockIR emit above).
            // Non-suspended exceptions are rethrown by UnwrapSuspendedInBlock.
            b.beginTryCatch();
            //   try: normal return of the body
            b.beginReturn();

            if (selfTailLoop) {
                // ── Self-tail-loop: compile body as BDSL While loop ──
                // Instead of throwing TailCallException per iteration,
                // self-tail-calls update param locals and continue the
                // loop.  The trampoline still handles ABI unpacking for
                // the initial call but has hasTailCalls=false (no LoopNode).

                // Allocate param locals (params are mutable in the loop).
                var stlParams = new BytecodeLocal[paramCount];
                java.util.List<String> pNames = ir.getParamNames();
                for (int i = 0; i < paramCount; i++) {
                    String pn = (i < pNames.size()) ? pNames.get(i) : ("_stl_p" + i);
                    stlParams[i] = b.createLocal("_stl_" + pn, null);
                }

                b.beginBlock();

                // Copy initial param values from args into param locals.
                for (int i = 0; i < paramCount; i++) {
                    b.beginStoreLocal(stlParams[i]);
                    b.emitLoadArgument(captureCount + i);
                    b.endStoreLocal();
                }

                // Set self-tail-loop context — enables param read
                // remapping in emitNode (ReadArgIR → LoadLocal(stlParam)).
                this.inSelfTailLoop = true;
                this.selfTailParamLocals = stlParams;
                this.selfTailCaptureCount = captureCount;
                this.selfTailHash = ir.getHash();

                // ── Try fused long tail-loop ──
                // When body = IfIR(cond, ReadArgIR, selfTailCall) and
                // all params are longs with simple arithmetic updates,
                // emit a single FusedLongTailLoop BDSL operation that
                // executes the entire loop in Java — zero per-iteration
                // BDSL dispatch, frame reads, or temp copies.
                boolean emittedDirect = false;
                NodeIR body = ir.getBody();

                if (body instanceof IfIR ifBody) {
                    if (paramCount == 1) {
                        emittedDirect = tryEmitFusedLongTailLoop1P(
                                ifBody, ir, captureCount,
                                b, argCount, localMap);
                    } else if (paramCount == 2) {
                        emittedDirect = tryEmitFusedLongTailLoop(
                                ifBody, ir, paramCount, captureCount,
                                b, argCount, localMap);
                    }
                }

                // ── Try direct-while optimization ──
                // When body = IfIR(cond, base, selfTailCall), emit a
                // tight While loop using the source condition directly
                // as the loop guard, eliminating the flag variable,
                // inner conditional, identity param copies, and dummy
                // values.  Saves ~16 BDSL ops per iteration.
                if (!emittedDirect && body instanceof IfIR ifBody) {
                    NodeIR cond = ifBody.getCond();
                    NodeIR thenBr = ifBody.getThenBr();
                    NodeIR elseBr = ifBody.getElseBr();

                    CallDirectIR tailCall = null;
                    NodeIR baseExpr = null;
                    boolean tailInElse = false;

                    if (elseBr instanceof CallDirectIR ec && ec.isTailCall()
                            && ec.getTargetHash().equals(ir.getHash())
                            && ec.getArgs().size() == paramCount) {
                        tailCall = ec;
                        baseExpr = thenBr;
                        tailInElse = true;
                    } else if (thenBr instanceof CallDirectIR tc && tc.isTailCall()
                            && tc.getTargetHash().equals(ir.getHash())
                            && tc.getArgs().size() == paramCount) {
                        tailCall = tc;
                        baseExpr = elseBr;
                        tailInElse = false;
                    }

                    if (tailCall != null && stlIsSimpleExpr(baseExpr)) {
                        emittedDirect = true;
                        // selfTailContinueFlag stays null — no flag.

                        b.beginWhile();

                        // Condition: loop while base case is NOT met.
                        if (tailInElse) {
                            // if cond then base else tailCall → loop
                            // while NOT cond.
                            stlEmitInvertedCond(cond, b, argCount, localMap);
                        } else {
                            // if cond then tailCall else base → loop
                            // while cond.
                            emitNode(cond, b, argCount, localMap);
                        }

                        // Body: update params from tail-call args.
                        stlEmitParamUpdate(tailCall.getArgs(), stlParams,
                                b, argCount, localMap);

                        b.endWhile();

                        // After loop: base case expression.
                        emitNode(baseExpr, b, argCount, localMap);
                    }
                }

                if (!emittedDirect) {
                    // ── Fallback: flag-based self-tail-loop ──
                    BytecodeLocal stlFlag = b.createLocal("_stl_cont", null);
                    BytecodeLocal stlResult = b.createLocal("_stl_res", null);
                    this.selfTailContinueFlag = stlFlag;

                    b.beginStoreLocal(stlFlag);
                    b.emitLoadLongConstant(1L);
                    b.endStoreLocal();

                    b.beginWhile();
                      b.beginDirectLessThan();
                        b.emitLoadLongConstant(0L);
                        b.emitLoadLocal(stlFlag);
                      b.endDirectLessThan();
                      b.beginBlock();
                        b.beginStoreLocal(stlFlag);
                          b.emitLoadLongConstant(0L);
                        b.endStoreLocal();
                        b.beginStoreLocal(stlResult);
                          emitNode(ir.getBody(), b, argCount, localMap);
                        b.endStoreLocal();
                      b.endBlock();
                    b.endWhile();

                    b.emitLoadLocal(stlResult);
                }

                // Clean up context.
                this.inSelfTailLoop = false;
                this.selfTailParamLocals = null;
                this.selfTailContinueFlag = null;
                this.selfTailHash = null;

                b.endBlock();
            } else {
                emitNode(ir.getBody(), b, argCount, localMap);
            }

            b.endReturn();
            //   catch: extract MezzeSuspend and return it
            b.beginReturn();
            b.beginUnwrapSuspendedInBlock();
            b.emitLoadException();
            b.endUnwrapSuspendedInBlock();
            b.endReturn();
            b.endTryCatch();

            b.endRoot();
            if (source != null) {
                if (rootRange != null) {
                    b.endSourceSection();
                }
                b.endSource();
            }
        });
        RootNode inner = nodes.getNode(0);
        if (trampoline != null) {
            // For self-tail-loop, the loop is inside the BDSL root —
            // tell the trampoline there are no tail calls so it uses
            // the fast path (DirectCallNode, no LoopNode).
            trampoline.attachInner(inner, hasTail && !selfTailLoop);
            return trampoline;
        }
        // No tail calls in body — return raw BDSL root, skip trampoline overhead.
        return inner;
    }

    /**
     * Static analysis: does the IR body contain any tail-call site
     * (CallDirectIR with isTailCall=true or CallIndirectIR with
     * isTailCall=true)? Only functions whose bodies produce
     * TailCallException need the outer trampoline wrapper.
     */
    private boolean hasTailCall(NodeIR node) {
        if (node == null) return false;
        if (node instanceof CallDirectIR c) {
            if (c.isTailCall()) return true;
            for (NodeIR arg : c.getArgs()) if (hasTailCall(arg)) return true;
            return false;
        }
        if (node instanceof CallIndirectIR ci) {
            if (ci.isTailCall()) return true;
            return hasTailCall(ci.getFunction()) || hasTailCall(ci.getArg());
        }
        if (node instanceof CallLocalIR cl) {
            return hasTailCall(cl.getArg());
        }
        if (node instanceof CallIndirectScalarIR ci) {
            if (ci.isTailCall()) return true;
            if (hasTailCall(ci.getFunction())) return true;
            for (NodeIR arg : ci.getArgs()) if (hasTailCall(arg)) return true;
            return false;
        }
        if (node instanceof PartialApplyDirectIR pa) {
            if (pa.isTailCall()) return true;
            for (FieldIR f : pa.getPartialFields()) if (hasTailCall(f.getValue())) return true;
            return false;
        }
        if (node instanceof DictMethodCallIR dm) {
            return hasTailCall(dm.getArg());
        }
        if (node instanceof CallNativeIR cn) {
            for (NodeIR arg : cn.getArgs()) if (hasTailCall(arg)) return true;
            return hasTailCall(cn.getArg());
        }
        if (node instanceof InlineBinaryOpIR bin) {
            return hasTailCall(bin.getLeft()) || hasTailCall(bin.getRight());
        }
        if (node instanceof IfIR i) {
            return hasTailCall(i.getCond()) || hasTailCall(i.getThenBr()) || hasTailCall(i.getElseBr());
        }
        if (node instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                NodeIR child = (s instanceof StmtIR.Let l) ? l.getValue()
                             : ((StmtIR.Expr) s).getNode();
                if (hasTailCall(child)) return true;
            }
            return hasTailCall(blk.getResult());
        }
        if (node instanceof MakeRecordIR m) {
            for (FieldIR f : m.getFields()) if (hasTailCall(f.getValue())) return true;
            return false;
        }
        if (node instanceof ReadFieldIR r) return hasTailCall(r.getRecord());
        if (node instanceof RecordUpdateIR ru) {
            if (hasTailCall(ru.getRecord())) return true;
            for (FieldIR f : ru.getUpdates()) if (hasTailCall(f.getValue())) return true;
            return false;
        }
        if (node instanceof MakeVariantIR mv) {
            return mv.getPayload() != null && hasTailCall(mv.getPayload());
        }
        if (node instanceof MakeSeqIR seq) {
            for (SeqElementIR e : seq.getElements()) {
                if (e instanceof SeqElementIR.Regular reg && hasTailCall(reg.getNode())) return true;
                if (e instanceof SeqElementIR.Spread sp && hasTailCall(sp.getNode())) return true;
            }
            return false;
        }
        // MakeLambdaIR: captures are frame slot indices, no NodeIR children.
        // (A tail call inside a lambda body sits in a different FunctionIR
        // that we'll analyze separately when it's compiled.)
        if (node instanceof MakeLambdaIR) return false;
        if (node instanceof MatchIR m) {
            if (hasTailCall(m.getScrutinee())) return true;
            for (CaseIR c : m.getCases()) {
                if (c.getGuard() != null && hasTailCall(c.getGuard())) return true;
                if (hasTailCall(c.getBody())) return true;
            }
            return false;
        }
        if (node instanceof WriteLocalIR w) return hasTailCall(w.getValue());
        if (node instanceof PerformOpIR p) return hasTailCall(p.getArg());
        if (node instanceof HandleIR h) {
            if (hasTailCall(h.getBody())) return true;
            for (HandlerBindIR hb : h.getHandlers()) {
                if (hb.getParams() != null && hasTailCall(hb.getParams())) return true;
            }
            return false;
        }
        if (node instanceof EffectBindingIR) return false;
        if (node instanceof InterpolatedStringIR is) {
            for (StringPartIR p : is.getParts()) {
                if (p instanceof StringPartIR.Expr ep && hasTailCall(ep.getNode())) return true;
            }
            return false;
        }
        // Literals, ReadLocal, Unit — no children.
        return false;
    }

    /**
     * Returns true iff every tail-call site in the subtree is a
     * scalar-ABI {@link CallDirectIR} targeting {@code selfHash}.
     * Vacuously true when there are no tail-calls at all — caller
     * should gate on {@link #hasTailCall} first.
     *
     * <p>When this returns true the body can be compiled as a BDSL
     * {@code While} loop: self-tail-calls become local-variable
     * updates + loop continue, eliminating TailCallException overhead.
     */
    private boolean allTailCallsAreSelfScalar(NodeIR node, Hash selfHash) {
        if (node == null) return true;
        if (node instanceof CallDirectIR c) {
            if (c.isTailCall()) {
                return c.isScalarABI() && c.getTargetHash().equals(selfHash);
            }
            for (NodeIR arg : c.getArgs()) if (!allTailCallsAreSelfScalar(arg, selfHash)) return false;
            return true;
        }
        if (node instanceof CallIndirectIR ci) {
            if (ci.isTailCall()) return false;
            return allTailCallsAreSelfScalar(ci.getFunction(), selfHash)
                    && allTailCallsAreSelfScalar(ci.getArg(), selfHash);
        }
        if (node instanceof CallLocalIR cl) {
            return allTailCallsAreSelfScalar(cl.getArg(), selfHash);
        }
        if (node instanceof CallIndirectScalarIR ci) {
            if (ci.isTailCall()) return false;
            if (!allTailCallsAreSelfScalar(ci.getFunction(), selfHash)) return false;
            for (NodeIR arg : ci.getArgs()) if (!allTailCallsAreSelfScalar(arg, selfHash)) return false;
            return true;
        }
        if (node instanceof PartialApplyDirectIR pa) {
            if (pa.isTailCall()) return false;
            for (FieldIR f : pa.getPartialFields()) if (!allTailCallsAreSelfScalar(f.getValue(), selfHash)) return false;
            return true;
        }
        if (node instanceof DictMethodCallIR dm) {
            return allTailCallsAreSelfScalar(dm.getArg(), selfHash);
        }
        if (node instanceof CallNativeIR cn) {
            for (NodeIR arg : cn.getArgs()) if (!allTailCallsAreSelfScalar(arg, selfHash)) return false;
            return allTailCallsAreSelfScalar(cn.getArg(), selfHash);
        }
        if (node instanceof InlineBinaryOpIR bin) {
            return allTailCallsAreSelfScalar(bin.getLeft(), selfHash)
                    && allTailCallsAreSelfScalar(bin.getRight(), selfHash);
        }
        if (node instanceof IfIR i) {
            return allTailCallsAreSelfScalar(i.getCond(), selfHash)
                    && allTailCallsAreSelfScalar(i.getThenBr(), selfHash)
                    && allTailCallsAreSelfScalar(i.getElseBr(), selfHash);
        }
        if (node instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                NodeIR child = (s instanceof StmtIR.Let l) ? l.getValue()
                        : ((StmtIR.Expr) s).getNode();
                if (!allTailCallsAreSelfScalar(child, selfHash)) return false;
            }
            return allTailCallsAreSelfScalar(blk.getResult(), selfHash);
        }
        if (node instanceof MakeRecordIR m) {
            for (FieldIR f : m.getFields()) if (!allTailCallsAreSelfScalar(f.getValue(), selfHash)) return false;
            return true;
        }
        if (node instanceof ReadFieldIR r) return allTailCallsAreSelfScalar(r.getRecord(), selfHash);
        if (node instanceof RecordUpdateIR ru) {
            if (!allTailCallsAreSelfScalar(ru.getRecord(), selfHash)) return false;
            for (FieldIR f : ru.getUpdates()) if (!allTailCallsAreSelfScalar(f.getValue(), selfHash)) return false;
            return true;
        }
        if (node instanceof MakeVariantIR mv) {
            return mv.getPayload() == null || allTailCallsAreSelfScalar(mv.getPayload(), selfHash);
        }
        if (node instanceof MakeSeqIR seq) {
            for (SeqElementIR e : seq.getElements()) {
                if (e instanceof SeqElementIR.Regular reg && !allTailCallsAreSelfScalar(reg.getNode(), selfHash)) return false;
                if (e instanceof SeqElementIR.Spread sp && !allTailCallsAreSelfScalar(sp.getNode(), selfHash)) return false;
            }
            return true;
        }
        if (node instanceof MakeLambdaIR) return true;
        if (node instanceof MatchIR m) {
            if (!allTailCallsAreSelfScalar(m.getScrutinee(), selfHash)) return false;
            for (CaseIR c : m.getCases()) {
                if (c.getGuard() != null && !allTailCallsAreSelfScalar(c.getGuard(), selfHash)) return false;
                if (!allTailCallsAreSelfScalar(c.getBody(), selfHash)) return false;
            }
            return true;
        }
        if (node instanceof WriteLocalIR w) return allTailCallsAreSelfScalar(w.getValue(), selfHash);
        if (node instanceof PerformOpIR p) return allTailCallsAreSelfScalar(p.getArg(), selfHash);
        if (node instanceof HandleIR h) {
            if (!allTailCallsAreSelfScalar(h.getBody(), selfHash)) return false;
            for (HandlerBindIR hb : h.getHandlers()) {
                if (hb.getParams() != null && !allTailCallsAreSelfScalar(hb.getParams(), selfHash)) return false;
            }
            return true;
        }
        if (node instanceof EffectBindingIR) return true;
        if (node instanceof InterpolatedStringIR is) {
            for (StringPartIR p : is.getParts()) {
                if (p instanceof StringPartIR.Expr ep && !allTailCallsAreSelfScalar(ep.getNode(), selfHash)) return false;
            }
            return true;
        }
        return true;
    }

    /** Emit a load of a capture slot — same logic as MakeLambdaIR capture emission
     *  but without LoadLocalOrNull (captures are guaranteed written at devirtualized call sites). */
    private void emitCapture(CaptureIR cap, MezzeBytecodeRootNodeGen.Builder b,
                             int argCount, BytecodeLocal[] localMap) {
        int slot = cap.getSlot();
        if (slot < argCount) {
            if (inSelfTailLoop && slot >= selfTailCaptureCount) {
                b.emitLoadLocal(selfTailParamLocals[slot - selfTailCaptureCount]);
            } else {
                b.emitLoadArgument(slot);
            }
        } else {
            b.emitLoadLocal(localMap[slot - argCount]);
        }
    }

    private void emitNode(NodeIR node, MezzeBytecodeRootNodeGen.Builder b,
                          int argCount, BytecodeLocal[] localMap) {
        if (node instanceof LongLitIR lit) {
            b.emitLoadLongConstant(lit.getValue());
            return;
        }
        if (node instanceof DoubleLitIR lit) {
            b.emitLoadDoubleConstant(lit.getValue());
            return;
        }
        if (node instanceof BoolLitIR lit) {
            b.emitLoadBooleanConstant(lit.getValue());
            return;
        }
        if (node instanceof StringLitIR lit) {
            b.emitLoadObjectConstant(lit.getValue());
            return;
        }
        if (node instanceof CharLitIR lit) {
            // Mezze chars box as Character; the runtime treats primitive
            // Character values uniformly with unboxed comparisons.
            b.emitLoadObjectConstant(Character.valueOf(lit.getValue()));
            return;
        }
        if (node instanceof UnitLitIR) {
            b.emitLoadObjectConstant(com.mezze.interpreter.values.MezzeRecord.empty());
            return;
        }
        if (node instanceof com.mezze.language.truffleir.InspectIR ins) {
            // #394: type-driven Inspect — bake the static PrintType as a
            // constant operand and evaluate the receiver as the dynamic operand.
            b.beginTypedInspect(ins.getPrintType(), ins.getDbgStyle());
            emitNode(ins.getValue(), b, argCount, localMap);
            b.endTypedInspect();
            return;
        }
        if (node instanceof ReadLocalIR r) {
            // IR frame layout: [captures..., params..., locals...].
            // Captures + params are both LoadArgument slots — see the
            // caller convention in MezzeFunctionRootNode (arg[0..
            // captureCount-1] = closure values, arg[captureCount..] =
            // user params). Anything at or above argCount is a local.
            int slot = r.getSlot();
            if (slot < argCount) {
                // Self-tail-loop: params live in BDSL locals (updated
                // by the loop), not in immutable arguments.
                if (inSelfTailLoop && slot >= selfTailCaptureCount) {
                    b.emitLoadLocal(selfTailParamLocals[slot - selfTailCaptureCount]);
                } else {
                    b.emitLoadArgument(slot);
                }
            } else {
                b.emitLoadLocal(localMap[slot - argCount]);
            }
            return;
        }
        if (node instanceof ReadGlobalByHashIR g) {
            b.emitReadGlobalByHash(g.getHash());
            return;
        }
        if (node instanceof ReadGlobalFnByHashIR g) {
            b.emitReadGlobalFnByHash(g.getHash());
            return;
        }
        if (node instanceof ReadArgIR r) {
            // ReadArgIR reads a captured/param slot by raw arg index.
            // The BDSL layout puts captures + params at args[0..argCount).
            int idx = r.getIndex();
            if (inSelfTailLoop && idx >= selfTailCaptureCount) {
                b.emitLoadLocal(selfTailParamLocals[idx - selfTailCaptureCount]);
            } else {
                b.emitLoadArgument(idx);
            }
            return;
        }
        if (node instanceof ReadCaptureIR r) {
            // ReadCaptureIR reads a capture at args[index] — captures
            // are laid out at the head of the argument list.
            b.emitLoadArgument(r.getIndex());
            return;
        }
        if (node instanceof FracLitIR f) {
            // Build the {numerator, denominator} record at emit time and
            // bake it as an object constant. This matches
            // FracLiteralNode.execute which caches on first call — we
            // avoid the runtime cache-check for a smaller hot path.
            String[] fields = new String[]{"numerator", "denominator"};
            com.mezze.interpreter.values.MezzeRecord frac =
                com.mezze.interpreter.values.MezzeRecord.fromFields(
                    fields, new Object[]{f.getNumerator(), f.getDenominator()});
            b.emitLoadObjectConstant(frac);
            return;
        }
        if (node instanceof WriteLocalIR w) {
            int slot = w.getSlot();
            if (slot < argCount) {
                throw new UnsupportedOperationException(
                    "Writing to an argument slot isn't supported "
                    + "(WriteLocalIR to slot " + slot + " where argCount=" + argCount + ")");
            }
            b.beginStoreLocal(localMap[slot - argCount]);
            emitNode(w.getValue(), b, argCount, localMap);
            b.endStoreLocal();
            return;
        }
        if (node instanceof InlineBinaryOpIR bin) {
            // Dispatch to a DirectXxx @OperationProxy for the callee's op.
            // Each of these forwards to the AST's DirectXxxNode
            // @Specialization logic (long/double/string/etc.). Unary ops
            // (NEG, NOT) only emit the left child.
            InlineOp op = bin.getOp();
            switch (op) {
                case ADD -> {
                    b.beginDirectAdd();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectAdd();
                }
                case SUB -> {
                    b.beginDirectSubtract();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectSubtract();
                }
                case MUL -> {
                    b.beginDirectMultiply();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectMultiply();
                }
                case DIV -> {
                    b.beginDirectDivide();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectDivide();
                }
                case MOD -> {
                    b.beginDirectModulo();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectModulo();
                }
                case EQ -> {
                    b.beginDirectEqual();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectEqual();
                }
                case NEQ -> {
                    b.beginDirectNotEqual();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectNotEqual();
                }
                case LT -> {
                    b.beginDirectLessThan();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectLessThan();
                }
                case LE -> {
                    b.beginDirectLessThanOrEqual();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectLessThanOrEqual();
                }
                case GT -> {
                    b.beginDirectGreaterThan();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectGreaterThan();
                }
                case GE -> {
                    b.beginDirectGreaterThanOrEqual();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectGreaterThanOrEqual();
                }
                case AND -> {
                    b.beginDirectAnd();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectAnd();
                }
                case OR -> {
                    b.beginDirectOr();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    emitNode(bin.getRight(), b, argCount, localMap);
                    b.endDirectOr();
                }
                case NEG -> {
                    // Unary — rightIR is unused, per InlineOp comment.
                    b.beginDirectNegate();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    b.endDirectNegate();
                }
                case NOT -> {
                    b.beginDirectNot();
                    emitNode(bin.getLeft(), b, argCount, localMap);
                    b.endDirectNot();
                }
            }
            return;
        }
        if (node instanceof IfIR i) {
            // Mezze if-then-else is an expression — use Conditional so
            // BDSL treats both branches as value-producing (IfThenElse
            // would pop them as statements).
            b.beginConditional();
            emitNode(i.getCond(), b, argCount, localMap);
            emitNode(i.getThenBr(), b, argCount, localMap);
            emitNode(i.getElseBr(), b, argCount, localMap);
            b.endConditional();
            return;
        }
        if (node instanceof BlockIR blk) {
            b.beginBlock();
            final boolean debug = currentSource != null;
            // #443 Day 10 — track slot→let-init at THIS block scope so the
            // fusion peel can resolve `let xs = seq::range{…}; iter::fold(
            // iter::from_seq(xs), …)` by replacing ReadLocal(xs's slot)
            // with the range expr. Save any pre-existing entries so nested
            // blocks that shadow don't corrupt the outer scope.
            java.util.List<Integer> localAddedSlots = new java.util.ArrayList<>();
            java.util.Map<Integer, NodeIR> savedShadows = new java.util.HashMap<>();
            for (StmtIR s : blk.getStmts()) {
                if (s instanceof StmtIR.Let l) {
                    // A `let` in Mezze IR is a WriteLocal to the let's
                    // slot; the AST compiler models it that way too.
                    // Emit the write, discarding the pushed value
                    // isn't needed because StoreLocal already pops.
                    //
                    // #356 — wrap in SourceSection + StatementTag so
                    // the debugger sees a breakpointable line boundary
                    // pinned to the let's location.
                    //
                    // #374 — top-level lets in `__entry__.body` also
                    // publish to MezzeContext globals so later
                    // ReadGlobalByHash and test-inspection can see them.
                    // Detected via entryTopLevelLets (populated by
                    // compileEntryFn); empty for ordinary function bodies.
                    final boolean topLevel = entryTopLevelLets.containsKey(l);
                    int[] range = debug ? toCharRange(currentSource, l.getSrc()) : null;
                    if (range != null) b.beginSourceSection(range[0], range[1]);
                    if (debug) b.beginTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class);
                    b.beginStoreLocal(localMap[l.getSlot() - argCount]);
                    if (topLevel) {
                        // Wrap: StoreLocal(RegisterGlobal(hash, name, value)).
                        // RegisterGlobal is transparent (1 in → 1 out) so the
                        // outer StoreLocal still fills the frame slot.
                        b.beginRegisterGlobal(l.getContentHash(), l.getName());
                        emitNode(l.getValue(), b, argCount, localMap);
                        b.endRegisterGlobal();
                    } else {
                        emitNode(l.getValue(), b, argCount, localMap);
                    }
                    b.endStoreLocal();
                    if (debug) b.endTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class);
                    if (range != null) b.endSourceSection();
                    // #443 Day 10 — record the binding so later peel steps
                    // can resolve ReadLocal → let init. Only pure locals,
                    // not top-level global-published lets (those are also
                    // read via ReadGlobalByHash from elsewhere and the
                    // value is already visible via the global path).
                    if (!topLevel) {
                        int slot = l.getSlot();
                        if (currentLetInits.containsKey(slot)) {
                            savedShadows.put(slot, currentLetInits.get(slot));
                        }
                        currentLetInits.put(slot, l.getValue());
                        localAddedSlots.add(slot);
                        // Track local lambda bindings for devirtualization.
                        if (l.getValue() instanceof MakeLambdaIR ml) {
                            localLambdas.put(slot, ml);
                        }
                    }
                } else if (s instanceof StmtIR.Expr e) {
                    // Bare expression statement — evaluate for side
                    // effects, drop the result. BDSL doesn't expose an
                    // explicit "drop" op inside a block. However, a
                    // top-level operation in the middle of a block IS
                    // treated as a discarded expression by the DSL —
                    // its return value is popped implicitly at the
                    // next instruction.
                    //
                    // #361 — when the stmt could yield MezzeSuspend,
                    // wrap the child in CheckSuspendedThrow so the
                    // value is intercepted before it's dropped. If it's
                    // a MezzeSuspend, CheckSuspendedThrow throws
                    // SuspendedInBlockException, which unwinds to the
                    // outer TryCatch at the function root (emitted at
                    // beginRoot), returning the suspended value as the
                    // fn's result. Non-suspended values are dropped as
                    // before (op returns void).
                    int[] range = debug ? toCharRange(currentSource, e.getSrc()) : null;
                    if (range != null) b.beginSourceSection(range[0], range[1]);
                    if (debug) b.beginTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class);
                    if (canYieldSuspended(e.getNode())) {
                        b.beginCheckSuspendedThrow();
                        emitNode(e.getNode(), b, argCount, localMap);
                        b.endCheckSuspendedThrow();
                    } else {
                        emitNode(e.getNode(), b, argCount, localMap);
                    }
                    if (debug) b.endTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class);
                    if (range != null) b.endSourceSection();
                }
            }
            emitNode(blk.getResult(), b, argCount, localMap);
            b.endBlock();
            // #443 Day 10 — restore prior scope: undo our additions.
            // Order: for each slot we added, either restore the shadow or
            // remove the entry entirely. Iterate over localAddedSlots so
            // we only touch what we introduced (no leaks into caller).
            for (int slot : localAddedSlots) {
                if (savedShadows.containsKey(slot)) {
                    currentLetInits.put(slot, savedShadows.get(slot));
                } else {
                    currentLetInits.remove(slot);
                }
            }
            return;
        }
        if (node instanceof FusedChainIR chain) {
            // #446 Phase 4b — canonicalized iter chain from ChainCanonicalizer.
            // See emitCanonicalIterChain for the full policy.
            if (emitCanonicalIterChain(chain, b, argCount, localMap)) return;
            // The emitter can't handle this chain (complex fold body,
            // un-classifiable predicate, etc.). Fall back to the original
            // node preserved by the canonicalizer. This is the safe path:
            // the original CallDirect/CallNative is emitted as if fusion
            // never ran.
            if (chain.getFallback() != null) {
                emitNode(chain.getFallback(), b, argCount, localMap);
                return;
            }
            // No fallback available (legacy serialized data or bug in
            // the canonicalizer). Fail loud so we know which shape to add.
            throw new IllegalStateException(
                "FusedChainIR reached BDSL emit but emitter refused it and no fallback available. " +
                "Source=" + chain.getSource().getClass().getSimpleName() +
                " stages=" + chain.getStages().size() +
                " sink=" + chain.getSink().getClass().getSimpleName());
        }
        if (node instanceof CallDirectIR c) {
            // #446 Phase 5 — CallDirect to a fusable HOF (seq::fold, iter::fold,
            // etc.) is already rewritten to FusedChainIR by ChainCanonicalizer,
            // handled above. The old pattern-matching tryEmitFusedFold path
            // was removed in this phase.
            // #514: route BOTH the missing-callee case AND the FOREIGN
            // unattached-trampoline case through the LazyStub. Baking an
            // unattached trampoline as the CallDirectN operand appeared
            // safe on JVM because BFS ordering happened to attach the
            // inner before any execute reached the site. Under SVM AOT
            // there's no such ordering guarantee: `main` was emitted with
            // `compute`'s bare trampoline shell, execute ran before
            // `compute`'s Phase 2 emit completed attachInner,
            // TrampolineRootNode.execute threw "executed before
            // attachInner". LazyStub inflates on first execute by
            // compiling the callee IR fresh — always yields a fully-
            // attached target regardless of outer emit ordering.
            //
            // BUT: a locally-pre-registered trampoline (compileWithCallees
            // for a self-recursive callee) must be kept — its attachInner
            // runs synchronously before compile() returns, and self-tail-
            // calls must bake the trampoline itself so TailCallException
            // is caught by the trampoline's own loop rather than escaping
            // through a LazyStub wrapper that has no trampoline.
            boolean localTarget = compiledCallTargets.containsKey(c.getTargetHash());
            CallTarget target = resolveCallTarget(c.getTargetHash());
            boolean needSwap = target == null
                    || (!localTarget && isUnattachedTrampoline(target));
            if (needSwap && language != null) {
                CallTarget stub = language.getOrMintUserStub(c.getTargetHash(), "callee");
                if (stub != null) {
                    target = stub;
                    compiledCallTargets.put(c.getTargetHash(), stub);
                }
            }
            if (target == null) {
                throw new IllegalStateException(
                    "CallDirectIR references unknown target hash "
                    + c.getTargetHash().getHex() + " — callee must be compiled first");
            }
            // Trampoline-bypass: when the callee is a BDSL trampoline whose
            // inner body has NO tail-calls, the trampoline is a pure ABI
            // shim for AST callers. A BDSL caller passes flat scalar args
            // that skip normalizeArgs, so the LoopNode + DirectCallNode
            // wrap costs ~13-15ns per call for nothing. Retarget the emit
            // to the inner BDSL root; correctness is preserved because the
            // inner uses the same flat layout BDSL emits.
            //
            // MUST NOT bypass when hasTailCalls is true — a self-tail-call
            // throws TailCallException from inside the inner and the
            // trampoline's catch is the only thing that keeps recursion
            // stack-flat. Bypassing there means the exception escapes.
            //
            // Also MUST NOT bypass for tail-calls emitted here (the caller
            // wants its own trampoline to catch, but the target still needs
            // its trampoline reachable if it's the resume point).
            // Record-ABI callers pass a single MezzeRecord that the
            // callee's trampoline must unpack into positional slots via
            // normalizeArgs. Bypassing the trampoline sends the raw
            // record to LoadArgument(0), leaving other param slots
            // uninitialized → runtime errors (e.g. Non-exhaustive match).
            // #384: guard `tramp.getInnerTarget() != null`. Forward
            // references reserve a trampoline CallTarget before the
            // callee's inner is built; if inner is null we can't retarget
            // to it or we'd emit beginCallDirectN(null). Fall through and
            // target the trampoline itself — correctness is preserved
            // (the trampoline handles both ABIs via normalizeArgs), we
            // just lose the ~13-15 ns bypass on this specific edge case.
            // Bypass the trampoline when the callee is a pure scalar
            // function: route directly to the inner BDSL root, skipping
            // normalizeArgs overhead (~13-15 ns). Guard: closureCount==0
            // because normalizeArgs also injects captured closures into
            // the argument array — skipping it when closures exist causes
            // AIOOBE (the inner root expects [closures..., params...] but
            // receives only [params...]).
            if (c.isScalarABI()
                    && target instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof TrampolineRootNode tramp
                    && !tramp.hasTailCalls()
                    && !c.isTailCall()
                    && tramp.getClosureCount() == 0
                    && tramp.getInnerTarget() != null) {
                target = tramp.getInnerTarget();
            }
            // Scalar-ABI callee-shape rule: paramCount==1 collides with
            // record ABI arity, so the caller MUST push ScalarABIMarker
            // before the value when the callee is AST. AST callers do
            // this in DirectCallScalar*; BDSL's CallDirectN passes args
            // verbatim to the callee's CallTarget; without the marker
            // the AST callee (via MezzeFunctionRootNode.execute)
            // misreads args and throws "Expected record argument".
            //
            // But when the callee is BDSL-owned we know it: the
            // trampoline strips markers anyway, so the marker is dead
            // weight (~5-10ns per hot call from the extra emit + stack
            // push). Skip it, save the round-trip. Check by inspecting
            // the target's RootNode class — TrampolineRootNode
            // (paramCount > 0) or MezzeBytecodeRootNode-generated root
            // (paramCount == 0, no wrap). AST callees use
            // MezzeFunctionRootNode.
            //
            // paramCount==0 (empty args) and paramCount>=2 (arity
            // distinct from record) never need the marker.
            // Scalar-ABI callers with arity==1 need the marker so the
            // AST callee's normalizeArgs treats args[0] as scalar not
            // record. Record-ABI callers already pass a record — the
            // callee is expecting it, so no marker needed.
            // ── KnownMutOps: inline Mut stdlib wrappers directly ──
            // Mut is hermetic — no effect dispatch needed.
            // Three tiers:
            //  1. Typed BDSL ops (MutReadInt, MutSize, MutIncr, MutSwap, MutSet)
            //     — return primitive long/boolean, no boxing, PE-transparent.
            //  2. Generic InlineMutOp1/2/3 — typed MUT_* constants (int_to_mut,
            //     seq_to_mut, etc.) that go through the opKind switch.
            //  3. Generic InlineMutRecord1/2/3/4 — ANY Mut wrapper around a
            //     __native_* call.  Builds a MezzeRecord and calls the handler
            //     directly.  @TruffleBoundary but eliminates effect dispatch.
            if (c.isScalarABI() && !c.isTailCall()) {
                var mutOp = KnownMutOps.lookupOrResolve(c.getTargetHash(), language);
                if (mutOp != null && c.getArgs().size() == mutOp.arity()) {
                    // ── Generic record-ABI bypass (tier 3) ──
                    if (mutOp.isGeneric()) {
                        var handler = com.mezze.interpreter.nodes.builtin.NativeDispatch
                            .recordHandlerOf(mutOp.nativeName());
                        if (handler != null) {
                            switch (mutOp.arity()) {
                                case 1 -> {
                                    b.beginInlineMutRecord1(handler, mutOp.shape());
                                    emitNode(c.getArgs().get(0), b, argCount, localMap);
                                    b.endInlineMutRecord1();
                                }
                                case 2 -> {
                                    b.beginInlineMutRecord2(handler, mutOp.shape());
                                    for (NodeIR arg : c.getArgs())
                                        emitNode(arg, b, argCount, localMap);
                                    b.endInlineMutRecord2();
                                }
                                case 3 -> {
                                    b.beginInlineMutRecord3(handler, mutOp.shape());
                                    for (NodeIR arg : c.getArgs())
                                        emitNode(arg, b, argCount, localMap);
                                    b.endInlineMutRecord3();
                                }
                                case 4 -> {
                                    b.beginInlineMutRecord4(handler, mutOp.shape());
                                    for (NodeIR arg : c.getArgs())
                                        emitNode(arg, b, argCount, localMap);
                                    b.endInlineMutRecord4();
                                }
                            }
                            return;
                        }
                        // Handler not found — fall through to normal call
                    } else {
                        // ── Typed fast paths (tier 1) ──
                        // Each case emits a dedicated @Operation with typed
                        // params/returns (long, double, boolean) — no boxing.
                        Hash mutHash = new Hash(
                            com.mezze.interpreter.nodes.effect.MarkerEffects.MUT_EFFECT_HASH_HEX);
                        switch (mutOp.kind()) {
                            // ── MutInt ──
                            case MezzeBytecodeRootNode.MUT_READ_INT, MezzeBytecodeRootNode.MUT_INT_TO_IMMUT -> {
                                b.beginMutReadInt();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutReadInt();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_INCR -> {
                                b.beginMutIncr();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutIncr();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DECR -> {
                                b.beginMutDecr();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutDecr();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_WRITE_INT -> {
                                b.beginMutWriteInt();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutWriteInt();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MUL_INT -> {
                                b.beginMutMulInt();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutMulInt();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DIV_INT -> {
                                b.beginMutDivInt();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutDivInt();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MOD_INT -> {
                                b.beginMutModInt();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutModInt();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_CAS_INT -> {
                                b.beginMutCasInt();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutCasInt();
                                return;
                            }
                            // ── MutSeq ──
                            case MezzeBytecodeRootNode.MUT_SIZE -> {
                                b.beginMutSize();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutSize();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SWAP -> {
                                b.beginMutSwap();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutSwap();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SET -> {
                                b.beginMutSet();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutSet();
                                return;
                            }
                            // ── MutFloat ──
                            case MezzeBytecodeRootNode.MUT_READ_FLOAT, MezzeBytecodeRootNode.MUT_FLOAT_TO_IMMUT -> {
                                b.beginMutReadFloat();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutReadFloat();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_WRITE_FLOAT -> {
                                b.beginMutFloatMutate(MezzeBytecodeRootNode.MutFloatMutate.WRITE);
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutFloatMutate();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_ADD_FLOAT -> {
                                b.beginMutFloatMutate(MezzeBytecodeRootNode.MutFloatMutate.ADD);
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutFloatMutate();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SUB_FLOAT -> {
                                b.beginMutFloatMutate(MezzeBytecodeRootNode.MutFloatMutate.SUB);
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutFloatMutate();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MUL_FLOAT -> {
                                b.beginMutFloatMutate(MezzeBytecodeRootNode.MutFloatMutate.MUL);
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutFloatMutate();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DIV_FLOAT -> {
                                b.beginMutFloatMutate(MezzeBytecodeRootNode.MutFloatMutate.DIV);
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutFloatMutate();
                                return;
                            }
                            // ── MutBool ──
                            case MezzeBytecodeRootNode.MUT_READ_BOOL, MezzeBytecodeRootNode.MUT_BOOL_TO_IMMUT -> {
                                b.beginMutReadBool();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutReadBool();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_WRITE_BOOL -> {
                                b.beginMutWriteBool();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutWriteBool();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_TOGGLE_BOOL -> {
                                b.beginMutToggleBool();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutToggleBool();
                                return;
                            }
                            // MutChar — Char is String at runtime, MutReadChar/MutWriteChar
                            // are Object-typed @Operations (no primitive return).
                            // Emitting them here bypasses InlineMutOp1 switch overhead.
                            case MezzeBytecodeRootNode.MUT_READ_CHAR, MezzeBytecodeRootNode.MUT_CHAR_TO_IMMUT -> {
                                b.beginMutReadChar();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutReadChar();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_WRITE_CHAR -> {
                                b.beginMutWriteChar();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutWriteChar();
                                return;
                            }
                            // MUT_CHAR_IS_DIGIT, IS_LETTER, IS_WHITESPACE, TO_UPPER, TO_LOWER
                            // fall through to InlineMutOp1 (tier 2)
                            // ── MutMap ──
                            case MezzeBytecodeRootNode.MUT_MAP_SIZE -> {
                                b.beginMutMapSize();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutMapSize();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MAP_CONTAINS -> {
                                b.beginMutMapContains();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutMapContains();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MAP_GET -> {
                                b.beginMutMapGet();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutMapGet();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MAP_PUT -> {
                                b.beginMutMapPut();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutMapPut();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_MAP_REMOVE -> {
                                b.beginMutMapRemove();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutMapRemove();
                                return;
                            }
                            // ── MutSet ──
                            case MezzeBytecodeRootNode.MUT_SET_SIZE -> {
                                b.beginMutCSetSize();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutCSetSize();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SET_CONTAINS -> {
                                b.beginMutCSetContains();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutCSetContains();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SET_ADD -> {
                                b.beginMutCSetAdd();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutCSetAdd();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SET_REMOVE -> {
                                b.beginMutCSetRemove();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutCSetRemove();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_SET_TOGGLE -> {
                                b.beginMutCSetToggle();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutCSetToggle();
                                return;
                            }
                            // ── MutBytes ──
                            case MezzeBytecodeRootNode.MUT_BYTES_LEN -> {
                                b.beginMutBytesLen();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutBytesLen();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_BYTES_GET -> {
                                b.beginMutBytesGet();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutBytesGet();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_BYTES_SET -> {
                                b.beginMutBytesSet();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutBytesSet();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_BYTES_SWAP -> {
                                b.beginMutBytesSwap();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutBytesSwap();
                                return;
                            }
                            // ── MutDeque ──
                            case MezzeBytecodeRootNode.MUT_DEQUE_SIZE -> {
                                b.beginMutDequeSize();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutDequeSize();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_IS_EMPTY -> {
                                b.beginMutDequeIsEmpty();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutDequeIsEmpty();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_PUSH_BACK -> {
                                b.beginMutDequePushBack();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutDequePushBack();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_PUSH_FRONT -> {
                                b.beginMutDequePushFront();
                                for (NodeIR arg : c.getArgs()) emitNode(arg, b, argCount, localMap);
                                b.endMutDequePushFront();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_POP_BACK -> {
                                b.beginMutDequePopBack();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutDequePopBack();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_POP_FRONT -> {
                                b.beginMutDequePopFront();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutDequePopFront();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_PEEK_FRONT -> {
                                b.beginMutDequePeekFront();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutDequePeekFront();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_DEQUE_PEEK_BACK -> {
                                b.beginMutDequePeekBack();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutDequePeekBack();
                                return;
                            }
                            // ── MutString ──
                            case MezzeBytecodeRootNode.MUT_STRING_LEN -> {
                                b.beginMutStringLen();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutStringLen();
                                return;
                            }
                            case MezzeBytecodeRootNode.MUT_STRING_IS_EMPTY -> {
                                b.beginMutStringIsEmpty();
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endMutStringIsEmpty();
                                return;
                            }
                            default -> {}  // fall through to arity-based dispatch
                        }
                        // ── Typed InlineMutOp (tier 2) ──
                        switch (mutOp.arity()) {
                            case 1 -> {
                                b.beginInlineMutOp1(mutOp.kind(), mutHash, c.getTargetHash());
                                emitNode(c.getArgs().get(0), b, argCount, localMap);
                                b.endInlineMutOp1();
                            }
                            case 2 -> {
                                b.beginInlineMutOp2(mutOp.kind(), mutHash, c.getTargetHash());
                                for (NodeIR arg : c.getArgs())
                                    emitNode(arg, b, argCount, localMap);
                                b.endInlineMutOp2();
                            }
                            case 3 -> {
                                b.beginInlineMutOp3(mutOp.kind(), mutHash, c.getTargetHash());
                                for (NodeIR arg : c.getArgs())
                                    emitNode(arg, b, argCount, localMap);
                                b.endInlineMutOp3();
                            }
                            case 4 -> {
                                // CompiledForRange: (start, end, step, body)
                                b.beginCompiledForRange(mutHash, c.getTargetHash());
                                for (NodeIR arg : c.getArgs())
                                    emitNode(arg, b, argCount, localMap);
                                b.endCompiledForRange();
                            }
                        }
                        return;
                    }
                }
            }
            boolean needsScalarMarker = c.isScalarABI()
                    && c.getArgs().size() == 1
                    && !isBdslCallee(target);
            // Total child count: scalar marker (if needed) + actual args
            int totalChildren = (needsScalarMarker ? 1 : 0) + c.getArgs().size();
            if (c.isTailCall() && inSelfTailLoop
                    && selfTailContinueFlag != null
                    && c.getTargetHash().equals(selfTailHash)) {
                // ── Self-tail-loop (flag path): rewrite as param
                // update + continue.  Identity args (param read back
                // to itself) are elided.
                java.util.List<NodeIR> args = c.getArgs();
                b.beginBlock();
                stlEmitParamUpdate(args, selfTailParamLocals,
                        b, argCount, localMap);
                // Set continue flag = 1 (loop).
                b.beginStoreLocal(selfTailContinueFlag);
                b.emitLoadLongConstant(1L);
                b.endStoreLocal();
                // Produce a dummy value for the expression position.
                b.emitLoadLongConstant(0L);
                b.endBlock();
            } else if (c.isTailCall()) {
                b.beginTailCallDirectN(target);
                if (needsScalarMarker) {
                    b.emitLoadObjectConstant(
                            com.mezze.interpreter.nodes.function.ScalarABIMarker.INSTANCE);
                }
                for (NodeIR arg : c.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endTailCallDirectN();
            } else if (!needsScalarMarker && totalChildren == 1) {
                // Fixed-arity call: avoids @Variadic Object[] overhead.
                // canBypass: true iff the call is scalar-ABI, the callee
                // wraps a non-tail-call TrampolineRootNode, and the
                // call site is not itself a tail call. This enables
                // runtime resolve through the trampoline in CallDirect1's
                // @Cached specialization — critical for self-recursive
                // scalar-ABI calls where the emit-time bypass fails
                // (inner target null during two-phase construction).
                boolean canBypass = c.isScalarABI()
                        && target instanceof com.oracle.truffle.api.RootCallTarget rct1
                        && rct1.getRootNode() instanceof TrampolineRootNode tramp1
                        && !tramp1.hasTailCalls()
                        && tramp1.getClosureCount() == 0
                        && !trampolineHasDictParams(tramp1);
                b.beginCallDirect1(target, canBypass);
                emitNode(c.getArgs().get(0), b, argCount, localMap);
                b.endCallDirect1();
            } else if (!needsScalarMarker && totalChildren == 2) {
                boolean canBypass = c.isScalarABI()
                        && target instanceof com.oracle.truffle.api.RootCallTarget rct2
                        && rct2.getRootNode() instanceof TrampolineRootNode tramp2
                        && !tramp2.hasTailCalls()
                        && tramp2.getClosureCount() == 0
                        && !trampolineHasDictParams(tramp2);
                b.beginCallDirect2(target, canBypass);
                for (NodeIR arg : c.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endCallDirect2();
            } else if (!needsScalarMarker && totalChildren == 3) {
                boolean canBypass = c.isScalarABI()
                        && target instanceof com.oracle.truffle.api.RootCallTarget rct3
                        && rct3.getRootNode() instanceof TrampolineRootNode tramp3
                        && !tramp3.hasTailCalls()
                        && tramp3.getClosureCount() == 0
                        && !trampolineHasDictParams(tramp3);
                b.beginCallDirect3(target, canBypass);
                for (NodeIR arg : c.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endCallDirect3();
            } else {
                b.beginCallDirectN(target);
                if (needsScalarMarker) {
                    b.emitLoadObjectConstant(
                            com.mezze.interpreter.nodes.function.ScalarABIMarker.INSTANCE);
                }
                for (NodeIR arg : c.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endCallDirectN();
            }
            return;
        }
        if (node instanceof MakeRecordIR m) {
            boolean hasSpread = false;
            for (FieldIR f : m.getFields()) {
                if ("__spread__".equals(f.getName())) { hasSpread = true; break; }
            }
            String[] fieldNames = new String[m.getFields().size()];
            for (int i = 0; i < m.getFields().size(); i++) {
                fieldNames[i] = m.getFields().get(i).getName();
            }
            if (hasSpread) {
                b.beginSpreadRecord(fieldNames);
                for (FieldIR f : m.getFields()) {
                    emitNode(f.getValue(), b, argCount, localMap);
                }
                b.endSpreadRecord();
            } else if (fieldNames.length == 1) {
                // Single-field record: use the specialized non-variadic
                // operation that avoids Object[] allocation and enables
                // long-quickened unboxed storage for integer values.
                b.beginMakeSingleFieldRecord(fieldNames);
                emitNode(m.getFields().get(0).getValue(), b, argCount, localMap);
                b.endMakeSingleFieldRecord();
            } else {
                b.beginMakeRecord(fieldNames);
                for (FieldIR f : m.getFields()) {
                    emitNode(f.getValue(), b, argCount, localMap);
                }
                b.endMakeRecord();
            }
            return;
        }
        if (node instanceof ReadFieldIR r) {
            b.beginReadFieldByName(r.getFieldName());
            emitNode(r.getRecord(), b, argCount, localMap);
            b.endReadFieldByName();
            return;
        }
        if (node instanceof RecordUpdateIR ru) {
            boolean hasSpread = false;
            for (FieldIR f : ru.getUpdates()) {
                if ("__spread__".equals(f.getName())) { hasSpread = true; break; }
            }
            if (hasSpread) {
                // Update-with-spread lowers to SpreadRecord over
                // (base-record + updates in order). The base record is
                // pushed as a leading "__spread__" entry; each update
                // keeps its original name (regular field or another
                // "__spread__" nested spread). buildSpreadRecord merges
                // in order, so later entries override earlier ones —
                // matching update semantics.
                int total = 1 + ru.getUpdates().size();
                String[] entryNames = new String[total];
                entryNames[0] = "__spread__";
                for (int i = 0; i < ru.getUpdates().size(); i++) {
                    entryNames[i + 1] = ru.getUpdates().get(i).getName();
                }
                b.beginSpreadRecord(entryNames);
                emitNode(ru.getRecord(), b, argCount, localMap);
                for (FieldIR f : ru.getUpdates()) {
                    emitNode(f.getValue(), b, argCount, localMap);
                }
                b.endSpreadRecord();
                return;
            }
            String[] updateNames = new String[ru.getUpdates().size()];
            for (int i = 0; i < ru.getUpdates().size(); i++) {
                updateNames[i] = ru.getUpdates().get(i).getName();
            }
            b.beginUpdateRecord(updateNames);
            emitNode(ru.getRecord(), b, argCount, localMap);
            for (FieldIR f : ru.getUpdates()) {
                emitNode(f.getValue(), b, argCount, localMap);
            }
            b.endUpdateRecord();
            return;
        }
        if (node instanceof MakeVariantIR mv) {
            NodeIR payload = mv.getPayload();
            if (payload == null) {
                // No stack operands -> BDSL generates a single-shot emit*.
                b.emitMakeVariantNoPayload(mv.getTag(), mv.getTagName());
            } else if (payload instanceof MakeRecordIR rec
                    && rec.getFields().size() == 1) {
                // Single-field variant fusion: skip MakeRecord, pass the raw
                // value directly via MakeVariantSingleFieldPayload.  Typed
                // specializations (doLong / doDouble / doObject) store it
                // without boxing.  Eliminates MezzeRecord + Object[1]
                // allocations on the hot path.  The extra fieldName operand
                // lets getPayloadValue() lazily materialize a MezzeRecord for
                // cold-path consumers.  ReadVariantField on the match side
                // reads the raw typed payload directly.
                String fieldName = rec.getFields().get(0).getName();
                b.beginMakeVariantSingleFieldPayload(mv.getTag(), mv.getTagName(), fieldName);
                emitNode(rec.getFields().get(0).getValue(), b, argCount, localMap);
                b.endMakeVariantSingleFieldPayload();
            } else {
                b.beginMakeVariantSinglePayload(mv.getTag(), mv.getTagName());
                emitNode(payload, b, argCount, localMap);
                b.endMakeVariantSinglePayload();
            }
            return;
        }
        if (node instanceof MatchIR m) {
            emitMatch(m, b, argCount, localMap);
            return;
        }
        if (node instanceof SeqGetIR sg) {
            b.beginSeqGet();
            emitNode(sg.getSeq(), b, argCount, localMap);
            emitNode(sg.getIndex(), b, argCount, localMap);
            b.endSeqGet();
            return;
        }
        if (node instanceof MakeSeqIR ms) {
            long isSpreadBits = 0L;
            for (int i = 0; i < ms.getElements().size(); i++) {
                if (ms.getElements().get(i) instanceof SeqElementIR.Spread) {
                    isSpreadBits |= (1L << i);
                }
            }
            if (isSpreadBits == 0L) {
                b.beginMakeSeq();
                for (SeqElementIR el : ms.getElements()) {
                    SeqElementIR.Regular reg = (SeqElementIR.Regular) el;
                    emitNode(reg.getNode(), b, argCount, localMap);
                }
                b.endMakeSeq();
            } else {
                b.beginMakeSeqWithSpread(isSpreadBits);
                for (SeqElementIR el : ms.getElements()) {
                    NodeIR value = (el instanceof SeqElementIR.Spread sp)
                            ? sp.getNode()
                            : ((SeqElementIR.Regular) el).getNode();
                    emitNode(value, b, argCount, localMap);
                }
                b.endMakeSeqWithSpread();
            }
            return;
        }
        if (node instanceof InterpolatedStringIR is) {
            String[] parts = new String[is.getParts().size()];
            for (int i = 0; i < is.getParts().size(); i++) {
                StringPartIR p = is.getParts().get(i);
                parts[i] = (p instanceof StringPartIR.Lit lit) ? lit.getValue() : null;
            }
            b.beginInterpolateString(parts);
            for (StringPartIR p : is.getParts()) {
                if (p instanceof StringPartIR.Expr e) {
                    // Wrap suspend-capable parts so a MezzeSuspend flows
                    // out as SuspendedInBlockException instead of being
                    // toString'd into the assembled string. Caught at fn
                    // root's outer TryCatch (#361). Non-suspending parts
                    // (literals, ReadLocal, etc.) skip the check — cheap.
                    if (canYieldSuspended(e.getNode())) {
                        b.beginCheckSuspendedPassThrough();
                        emitNode(e.getNode(), b, argCount, localMap);
                        b.endCheckSuspendedPassThrough();
                    } else {
                        emitNode(e.getNode(), b, argCount, localMap);
                    }
                }
            }
            b.endInterpolateString();
            return;
        }
        if (node instanceof CallIndirectIR ci) {
            // Peephole: CallIndirect(ReadField(x, opName), MakeRecord({r=y}))
            // is an inline binary op via ability method dispatch. Fold to
            // InlineBinaryOp so the receiver is never read as a field —
            // avoids "Expected record for field 'gt', got: Long" when x
            // is a primitive. Mirrors TruffleCompiler.tryCompileInlineOp.
            if (!ci.isTailCall()) {
                InlineBinaryOpIR folded = tryFoldInlineOp(ci);
                if (folded != null) {
                    emitInlineBinaryOp(folded, b, argCount, localMap);
                    return;
                }
                // #346 Group B: fold `x.to_dbg{}` / `x.to_str{}` on
                // scalar (or any) receiver into an inline stringify.
                // Same shape as tryFoldInlineOp: the desugar is
                // CallIndirect(ReadField(x, "to_dbg"), MakeRecord{it=x}).
                // Without this, the ReadField hits ReadFieldByName's
                // doNotRecord fallback on a Long/Double/Bool/Str/Char
                // receiver and throws. Mirrors DirectInspectNode's
                // AST-side auto-derive.
                NodeIR receiver = tryFoldInspectMethod(ci);
                if (receiver != null) {
                    ReadFieldIR rf = (ReadFieldIR) ci.getFunction();
                    boolean dbgStyle = "to_dbg".equals(rf.getFieldName());
                    b.beginInlineScalarInspect(dbgStyle);
                    emitNode(receiver, b, argCount, localMap);
                    b.endInlineScalarInspect();
                    return;
                }
            }
            if (ci.isTailCall()) {
                b.beginTailCallIndirect();
                emitNode(ci.getFunction(), b, argCount, localMap);
                emitNode(ci.getArg(), b, argCount, localMap);
                b.endTailCallIndirect();
            } else {
                b.beginCallIndirect();
                emitNode(ci.getFunction(), b, argCount, localMap);
                emitNode(ci.getArg(), b, argCount, localMap);
                b.endCallIndirect();
            }
            return;
        }
        if (node instanceof DictMethodCallIR dm) {
            // Dict method dispatch: read impl fn from a dictSlot, call
            // it with the single record arg (the receiver is already
            // injected into that record as "it" upstream).
            // The dict slot holds a MezzeFunction at runtime — either the
            // user's ability impl or a native fallback resolved by
            // TrampolineRootNode.unpackRecordSlow when the caller passed
            // the {} sentinel (pre-mono dead-dict case).
            int slot = dm.getDictSlot();
            b.beginCallIndirect();
            if (slot < argCount) {
                b.emitLoadArgument(slot);
            } else {
                b.emitLoadLocal(localMap[slot - argCount]);
            }
            emitNode(dm.getArg(), b, argCount, localMap);
            b.endCallIndirect();
            return;
        }
        if (node instanceof PartialApplyDirectIR pa) {
            // Materialize the partial-fields record, then hand it to
            // MonomorphicCall which does the has-all-parameters check
            // and returns either a partially-applied MezzeFunction or
            // the completed call's result. See MezzeBytecodeRootNode
            // MonomorphicCall op.
            String[] fieldNames = new String[pa.getPartialFields().size()];
            for (int i = 0; i < pa.getPartialFields().size(); i++) {
                fieldNames[i] = pa.getPartialFields().get(i).getName();
            }
            b.beginMonomorphicCall(pa.getTargetHash());
            b.beginMakeRecord(fieldNames);
            for (FieldIR f : pa.getPartialFields()) {
                emitNode(f.getValue(), b, argCount, localMap);
            }
            b.endMakeRecord();
            b.endMonomorphicCall();
            return;
        }
        if (node instanceof EffectBindingIR eb) {
            // Delegate to a cached EffectBindingNode via the EffectBinding
            // op — reads resume/abort/scope from HandlerChainRegistry.dispatching()
            // or MezzeContext. Zero stack input.
            b.emitEffectBinding(eb.getName());
            return;
        }
        if (node instanceof PerformOpIR p) {
            // Mut.mutate { act = X } fast path: emit PerformMutMutateAct
            // with the act lambda directly, skipping the {act = X} wrapper
            // record. OpCallNode.executeMutMutateActFast builds a minimal
            // EA-scalarizable wrapper at runtime. See #257 marker fast path.
            if (isMutMutateWithActLambda(p)) {
                com.mezze.language.truffleir.MakeRecordIR mr =
                    (com.mezze.language.truffleir.MakeRecordIR) p.getArg();
                b.beginPerformMutMutateAct(p.getEffectHash());
                emitNode(mr.getFields().get(0).getValue(), b, argCount, localMap);
                b.endPerformMutMutateAct();
                return;
            }
            // Path D (docs/perf/bdsl-effects-path-d-2026-07.md): every
            // perform lowers to a BDSL PerformD op — a direct
            // chain-dispatch native call, no Loom fiber, no reflection.
            // resume is fall-through (single-return); abort throws
            // AbortPerformException. See YieldDispatch for semantics.
            b.beginPerformD(p.getEffectHash(), p.getOperationName());
            emitNode(p.getArg(), b, argCount, localMap);
            b.endPerformD();
            return;
        }
        if (node instanceof HandleIR h) {
            emitHandle(h, b, argCount, localMap);
            return;
        }
        if (node instanceof CallIndirectScalarIR ci) {
            // ── Local lambda devirtualization ──
            // When the call target is a ReadLocal that resolves to a let-bound
            // MakeLambdaIR, we know the exact CallTarget at compile time.
            // Emit CallDirect (captures prepended to args) instead of
            // CallIndirect — the ConstantOperand CallTarget lets the JIT
            // inline the callee completely, eliminating the 30×+ PIC overhead.
            if (!ci.isTailCall() && ci.getFunction() instanceof ReadLocalIR readFn) {
                MakeLambdaIR lambdaIR = localLambdas.get(readFn.getSlot());
                if (lambdaIR != null) {
                    CallTarget lambdaTarget = resolveCallTarget(lambdaIR.getFunctionHash());
                    if (lambdaTarget != null) {
                        int captureCount = lambdaIR.getCaptures().size();
                        int totalArgs = captureCount + ci.getArgs().size();
                        // canBypass: safe because we're explicitly passing
                        // captures as positional args — the inner root
                        // receives (captures..., params...) as expected.
                        boolean canBypass = lambdaTarget instanceof com.oracle.truffle.api.RootCallTarget rctL
                                && rctL.getRootNode() instanceof TrampolineRootNode trampL
                                && !trampL.hasTailCalls();
                        if (totalArgs <= 3) {
                            // Emit CallDirect1/2/3 with captures prepended
                            switch (totalArgs) {
                                case 1 -> {
                                    b.beginCallDirect1(lambdaTarget, canBypass);
                                    if (captureCount > 0)
                                        emitCapture(lambdaIR.getCaptures().get(0), b, argCount, localMap);
                                    else
                                        emitNode(ci.getArgs().get(0), b, argCount, localMap);
                                    b.endCallDirect1();
                                }
                                case 2 -> {
                                    b.beginCallDirect2(lambdaTarget, canBypass);
                                    for (var cap : lambdaIR.getCaptures()) {
                                        emitCapture(cap, b, argCount, localMap);
                                    }
                                    for (NodeIR arg : ci.getArgs()) {
                                        emitNode(arg, b, argCount, localMap);
                                    }
                                    b.endCallDirect2();
                                }
                                case 3 -> {
                                    b.beginCallDirect3(lambdaTarget, canBypass);
                                    for (var cap : lambdaIR.getCaptures()) {
                                        emitCapture(cap, b, argCount, localMap);
                                    }
                                    for (NodeIR arg : ci.getArgs()) {
                                        emitNode(arg, b, argCount, localMap);
                                    }
                                    b.endCallDirect3();
                                }
                            }
                        } else {
                            b.beginCallDirectN(lambdaTarget);
                            for (var cap : lambdaIR.getCaptures()) {
                                emitCapture(cap, b, argCount, localMap);
                            }
                            for (NodeIR arg : ci.getArgs()) {
                                emitNode(arg, b, argCount, localMap);
                            }
                            b.endCallDirectN();
                        }
                        return;
                    }
                }
            }
            // ── Captured lambda devirtualization ──
            // When the call target is a ReadCapture whose capture index
            // maps to a known let-bound lambda (pre-analyzed), emit
            // CallDirect with ReadClosureValue ops to extract the
            // captured lambda's own closure values.  Mirrors the local
            // devirtualization above but sources captures from the
            // MezzeFunction's closureValues array instead of the frame.
            if (!ci.isTailCall() && ci.getFunction() instanceof ReadCaptureIR readCap) {
                var capMap = capturedLambdaDevirt.get(currentFunctionHash);
                if (capMap != null) {
                    CapturedLambdaBinding binding = capMap.get(readCap.getIndex());
                    if (binding != null) {
                        CallTarget lambdaTarget = resolveCallTarget(binding.lambdaHash);
                        if (lambdaTarget != null) {
                            int lambdaCapCount = binding.lambdaCaptureCount;
                            int totalArgs = lambdaCapCount + ci.getArgs().size();
                            // canBypass: resolveScalarTarget unwraps the
                            // trampoline at specialization time.
                            boolean canBypass = lambdaTarget instanceof com.oracle.truffle.api.RootCallTarget rctL
                                    && rctL.getRootNode() instanceof TrampolineRootNode trampL
                                    && !trampL.hasTailCalls();
                            if (totalArgs <= 3) {
                                switch (totalArgs) {
                                    case 1 -> {
                                        b.beginCallDirect1(lambdaTarget, canBypass);
                                        if (lambdaCapCount > 0) {
                                            b.beginReadClosureValue(0);
                                            b.emitLoadArgument(readCap.getIndex());
                                            b.endReadClosureValue();
                                        } else {
                                            emitNode(ci.getArgs().get(0), b, argCount, localMap);
                                        }
                                        b.endCallDirect1();
                                    }
                                    case 2 -> {
                                        b.beginCallDirect2(lambdaTarget, canBypass);
                                        for (int i = 0; i < lambdaCapCount; i++) {
                                            b.beginReadClosureValue(i);
                                            b.emitLoadArgument(readCap.getIndex());
                                            b.endReadClosureValue();
                                        }
                                        for (NodeIR arg : ci.getArgs()) {
                                            emitNode(arg, b, argCount, localMap);
                                        }
                                        b.endCallDirect2();
                                    }
                                    case 3 -> {
                                        b.beginCallDirect3(lambdaTarget, canBypass);
                                        for (int i = 0; i < lambdaCapCount; i++) {
                                            b.beginReadClosureValue(i);
                                            b.emitLoadArgument(readCap.getIndex());
                                            b.endReadClosureValue();
                                        }
                                        for (NodeIR arg : ci.getArgs()) {
                                            emitNode(arg, b, argCount, localMap);
                                        }
                                        b.endCallDirect3();
                                    }
                                }
                                return;
                            } else {
                                b.beginCallDirectN(lambdaTarget);
                                for (int i = 0; i < lambdaCapCount; i++) {
                                    b.beginReadClosureValue(i);
                                    b.emitLoadArgument(readCap.getIndex());
                                    b.endReadClosureValue();
                                }
                                for (NodeIR arg : ci.getArgs()) {
                                    emitNode(arg, b, argCount, localMap);
                                }
                                b.endCallDirectN();
                                return;
                            }
                        }
                    }
                }
            }
            // Scalar-ABI indirect call. When the call has exactly one arg
            // and is not a tail call, emit CallIndirectScalar1 — passes the
            // arg value directly, bypassing MakeRecord allocation and
            // trampoline normalization. Falls back to the generic
            // CallIndirect(MakeRecord(...)) path for multi-arg or tail calls.
            if (!ci.isTailCall() && ci.getArgs().size() == 1) {
                String fieldName = ci.getArgFieldNames().get(0);
                b.beginCallIndirectScalar1(fieldName);
                emitNode(ci.getFunction(), b, argCount, localMap);
                emitNode(ci.getArgs().get(0), b, argCount, localMap);
                b.endCallIndirectScalar1();
                return;
            }
            if (!ci.isTailCall() && ci.getArgs().size() == 2) {
                String[] fieldNames2 = ci.getArgFieldNames().toArray(new String[0]);
                b.beginCallIndirectScalar2(fieldNames2);
                emitNode(ci.getFunction(), b, argCount, localMap);
                emitNode(ci.getArgs().get(0), b, argCount, localMap);
                emitNode(ci.getArgs().get(1), b, argCount, localMap);
                b.endCallIndirectScalar2();
                return;
            }
            String[] fieldNames = ci.getArgFieldNames().toArray(new String[0]);
            if (ci.isTailCall()) {
                b.beginTailCallIndirect();
                emitNode(ci.getFunction(), b, argCount, localMap);
                b.beginMakeRecord(fieldNames);
                for (NodeIR arg : ci.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endMakeRecord();
                b.endTailCallIndirect();
            } else {
                b.beginCallIndirect();
                emitNode(ci.getFunction(), b, argCount, localMap);
                b.beginMakeRecord(fieldNames);
                for (NodeIR arg : ci.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endMakeRecord();
                b.endCallIndirect();
            }
            return;
        }
        if (node instanceof CallLocalIR cl) {
            // Sugar for CallIndirect(ReadLocal(slot), arg). Slot layout
            // is the same as ReadLocalIR — argCount split point.
            if (cl.isTailCall()) {
                b.beginTailCallIndirect();
                int slot = cl.getSlot();
                if (slot < argCount) {
                    b.emitLoadArgument(slot);
                } else {
                    b.emitLoadLocal(localMap[slot - argCount]);
                }
                emitNode(cl.getArg(), b, argCount, localMap);
                b.endTailCallIndirect();
            } else {
                b.beginCallIndirect();
                int slot = cl.getSlot();
                if (slot < argCount) {
                    b.emitLoadArgument(slot);
                } else {
                    b.emitLoadLocal(localMap[slot - argCount]);
                }
                emitNode(cl.getArg(), b, argCount, localMap);
                b.endCallIndirect();
            }
            return;
        }
        if (node instanceof MakeLambdaIR ml) {
            // #391 Step 4b Q1 — count closureless vs total MakeLambda sites.
            MAKELAMBDA_TOTAL.incrementAndGet();
            if (ml.getCaptures().isEmpty()) MAKELAMBDA_CLOSURELESS.incrementAndGet();
            // #514: mirror the CallDirectIR emit fix — treat an unattached
            // trampoline the same as a missing callee, EXCEPT when it was
            // locally pre-registered (self-recursion): those attach before
            // compile() returns. A MakeLambda that bakes a foreign
            // unattached target would produce a MezzeFunction whose
            // eventual invoke path routes to TrampolineRootNode.execute
            // before attachInner completes (SVM AOT).
            boolean localTargetMl = compiledCallTargets.containsKey(ml.getFunctionHash());
            CallTarget target = resolveCallTarget(ml.getFunctionHash());
            boolean needSwapMl = target == null
                    || (!localTargetMl && isUnattachedTrampoline(target));
            if (needSwapMl && language != null) {
                CallTarget stub = language.getOrMintUserStub(ml.getFunctionHash(), "lambda");
                if (stub != null) {
                    target = stub;
                    compiledCallTargets.put(ml.getFunctionHash(), stub);
                }
            }
            if (target == null) {
                throw new IllegalStateException(
                    "MakeLambdaIR references unknown target hash "
                    + ml.getFunctionHash().getHex() + " — callee must be compiled first");
            }
            // #374 — thread paramNames from the callee's FunctionIR so
            // MezzeFunction knows its own param list. Without this, partial
            // application (`f {a=1}` returning a curried fn) silently
            // corrupts under BDSL_ENTRY: MezzeFunction.parameterNames stays
            // EMPTY, hasAllParameters trivially true, target called with
            // incomplete args → null slots. Falls back to empty on lookup
            // miss (rare — the FunctionIR was registered by the pipeline
            // before compile).
            String[] paramNames = new String[0];
            if (language != null) {
                var calleeIR = language.lookupFunctionIR(ml.getFunctionHash());
                if (calleeIR != null) {
                    paramNames = calleeIR.getParamNames().toArray(new String[0]);
                }
            }
            b.beginMakeLambda(target, "lambda", paramNames);
            for (var cap : ml.getCaptures()) {
                int slot = cap.getSlot();
                if (slot < argCount) {
                    // Self-tail-loop remapping: when a captured slot is a
                    // param (>= captureCount), read from the loop-local
                    // copy instead of the original argument. Without this,
                    // closures created inside a self-tail-loop always
                    // capture the INITIAL param value from the first
                    // iteration, not the current iteration's value.
                    if (inSelfTailLoop && slot >= selfTailCaptureCount) {
                        b.emitLoadLocal(selfTailParamLocals[slot - selfTailCaptureCount]);
                    } else {
                        b.emitLoadArgument(slot);
                    }
                } else {
                    // Use LoadLocalOrNull — a null-tolerant read that
                    // returns null on an Illegal (unwritten) slot,
                    // matching AST's frame.getValue semantics. Needed
                    // for recursive-let captures (`let f = {n} -> ... f
                    // {n=n-1}`) where the outer let's slot may not be
                    // written yet at MakeLambda-execute time. Emitted
                    // uniformly for all local captures because BDSL
                    // can't statically distinguish "written by now" from
                    // "possibly unwritten" — the two cost the same at
                    // runtime and the null-tolerant read is safe either
                    // way (recursive-let re-emits a fresh MakeLambda at
                    // the call site rather than reading the captured
                    // slot; see TypedASTCompiler.compileVar).
                    b.emitLoadLocalOrNull(localMap[slot - argCount]);
                }
            }
            b.endMakeLambda();
            return;
        }
        if (node instanceof CallNativeIR cn) {
            // #446 Phase 5c — CallNativeIR("__native_seq_sum", …) is
            // rewritten to FusedChainIR(<xs>, [], SinkIR.Sum) by the
            // canonicalizer above and handled by emitCanonicalIterChain,
            // which routes to BuiltinSumLongSeq / BuiltinSumRangeLong.
            // The old tryEmitBuiltinSeqReducer path was removed here.

            // Record-ABI: single record arg on the stack; dispatch via
            // NativeDispatch.HANDLERS by name. Slower than the scalar
            // path (still allocs a MezzeRecord in the caller) but routes
            // the surrounding function through BDSL.
            if (!cn.isScalarABI()) {
                // ── Fast paths: bypass record alloc for known record-ABI natives ──
                // Emit inline BDSL operations directly, skipping MakeRecord
                // allocation and NativeDispatch HashMap lookup.
                String recName = cn.getName();
                if (cn.getArg() instanceof MakeRecordIR mr) {
                    // str::concat { l, r } → DirectAdd (addStrings specialization)
                    if ("__native_string_concat".equals(recName) && mr.getFields().size() == 2) {
                        NodeIR lNode = null, rNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("l".equals(fld.getName())) lNode = fld.getValue();
                            else if ("r".equals(fld.getName())) rNode = fld.getValue();
                        }
                        if (lNode != null && rNode != null) {
                            b.beginDirectAdd();
                            emitNode(lNode, b, argCount, localMap);
                            emitNode(rNode, b, argCount, localMap);
                            b.endDirectAdd();
                            return;
                        }
                    }
                    // {int,float,nat}::to_str { n } → InlineScalarInspect
                    if (("__native_int_to_str".equals(recName)
                            || "__native_float_to_str".equals(recName)
                            || "__native_nat_to_str".equals(recName))
                            && mr.getFields().size() == 1) {
                        NodeIR nNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("n".equals(fld.getName())) nNode = fld.getValue();
                        }
                        if (nNode != null) {
                            b.beginInlineScalarInspect(false);
                            emitNode(nNode, b, argCount, localMap);
                            b.endInlineScalarInspect();
                            return;
                        }
                    }
                    // bool::to_str { b } → InlineScalarInspect (doBool → "True"/"False")
                    if ("__native_bool_to_str".equals(recName) && mr.getFields().size() == 1) {
                        NodeIR bNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("b".equals(fld.getName())) bNode = fld.getValue();
                        }
                        if (bNode != null) {
                            b.beginInlineScalarInspect(false);
                            emitNode(bNode, b, argCount, localMap);
                            b.endInlineScalarInspect();
                            return;
                        }
                    }
                    // str::contains { s, substring } → InlineStringPredicate(CONTAINS)
                    if ("__native_string_contains".equals(recName) && mr.getFields().size() == 2) {
                        NodeIR sNode = null, subNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("s".equals(fld.getName())) sNode = fld.getValue();
                            else if ("substring".equals(fld.getName())) subNode = fld.getValue();
                        }
                        if (sNode != null && subNode != null) {
                            b.beginInlineStringPredicate(MezzeBytecodeRootNode.InlineStringPredicate.CONTAINS);
                            emitNode(sNode, b, argCount, localMap);
                            emitNode(subNode, b, argCount, localMap);
                            b.endInlineStringPredicate();
                            return;
                        }
                    }
                    // str::starts_with { s, prefix } → InlineStringPredicate(STARTS_WITH)
                    if ("__native_string_starts_with".equals(recName) && mr.getFields().size() == 2) {
                        NodeIR sNode = null, prefixNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("s".equals(fld.getName())) sNode = fld.getValue();
                            else if ("prefix".equals(fld.getName())) prefixNode = fld.getValue();
                        }
                        if (sNode != null && prefixNode != null) {
                            b.beginInlineStringPredicate(MezzeBytecodeRootNode.InlineStringPredicate.STARTS_WITH);
                            emitNode(sNode, b, argCount, localMap);
                            emitNode(prefixNode, b, argCount, localMap);
                            b.endInlineStringPredicate();
                            return;
                        }
                    }
                    // str::ends_with { s, suffix } → InlineStringPredicate(ENDS_WITH)
                    if ("__native_string_ends_with".equals(recName) && mr.getFields().size() == 2) {
                        NodeIR sNode = null, suffixNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("s".equals(fld.getName())) sNode = fld.getValue();
                            else if ("suffix".equals(fld.getName())) suffixNode = fld.getValue();
                        }
                        if (sNode != null && suffixNode != null) {
                            b.beginInlineStringPredicate(MezzeBytecodeRootNode.InlineStringPredicate.ENDS_WITH);
                            emitNode(sNode, b, argCount, localMap);
                            emitNode(suffixNode, b, argCount, localMap);
                            b.endInlineStringPredicate();
                            return;
                        }
                    }
                    // ── Single-field { s } string natives ──
                    if (mr.getFields().size() == 1) {
                        NodeIR sNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("s".equals(fld.getName())) sNode = fld.getValue();
                        }
                        if (sNode != null) {
                            // str::size { s } → InlineStringSize (PE-transparent, no boundary)
                            if ("__native_string_size".equals(recName)) {
                                b.beginInlineStringSize();
                                emitNode(sNode, b, argCount, localMap);
                                b.endInlineStringSize();
                                return;
                            }
                            // str::trim/to_upper/to_lower/reverse/trim_start/trim_end → InlineStringUnary
                            int unaryOp = -1;
                            if ("__native_string_trim".equals(recName))            unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TRIM;
                            else if ("__native_string_to_upper".equals(recName))   unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TO_UPPER;
                            else if ("__native_string_to_lower".equals(recName))   unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TO_LOWER;
                            else if ("__native_string_reverse".equals(recName))    unaryOp = MezzeBytecodeRootNode.InlineStringUnary.REVERSE;
                            else if ("__native_string_trim_start".equals(recName)) unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TRIM_START;
                            else if ("__native_string_trim_end".equals(recName))   unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TRIM_END;
                            if (unaryOp >= 0) {
                                b.beginInlineStringUnary(unaryOp);
                                emitNode(sNode, b, argCount, localMap);
                                b.endInlineStringUnary();
                                return;
                            }
                        }
                    }
                    // ── #84: Dynamic iter sink bypasses ──
                    // Replace @TruffleBoundary IterNatives.fold/forEach/sum with
                    // BDSL operations whose fold body is JIT-visible.
                    if ("__native_iter_fold".equals(recName) && mr.getFields().size() == 3) {
                        NodeIR itNode = null, initNode = null, fNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("it".equals(fld.getName())) itNode = fld.getValue();
                            else if ("init".equals(fld.getName())) initNode = fld.getValue();
                            else if ("f".equals(fld.getName())) fNode = fld.getValue();
                        }
                        if (itNode != null && initNode != null && fNode != null) {
                            b.beginIterFoldDynamic();
                            emitNode(itNode, b, argCount, localMap);
                            emitNode(initNode, b, argCount, localMap);
                            emitNode(fNode, b, argCount, localMap);
                            b.endIterFoldDynamic();
                            return;
                        }
                    }
                    if ("__native_iter_for_each".equals(recName) && mr.getFields().size() == 2) {
                        NodeIR itNode = null, fNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("it".equals(fld.getName())) itNode = fld.getValue();
                            else if ("f".equals(fld.getName())) fNode = fld.getValue();
                        }
                        if (itNode != null && fNode != null) {
                            b.beginIterForEachDynamic();
                            emitNode(itNode, b, argCount, localMap);
                            emitNode(fNode, b, argCount, localMap);
                            b.endIterForEachDynamic();
                            return;
                        }
                    }
                    if ("__native_iter_sum".equals(recName) && mr.getFields().size() == 1) {
                        NodeIR itNode = null;
                        for (FieldIR fld : mr.getFields()) {
                            if ("it".equals(fld.getName())) itNode = fld.getValue();
                        }
                        if (itNode != null) {
                            b.beginIterSumDynamic();
                            emitNode(itNode, b, argCount, localMap);
                            b.endIterSumDynamic();
                            return;
                        }
                    }
                }

                // CallNativeRecordWithNode removed — SVM blocklist can't
                // respect @TruffleBoundary on BDSL ops.  All handlers now
                // route through CallNativeRecord (#402).
                var plain = com.mezze.interpreter.nodes.builtin.NativeDispatch
                        .recordHandlerOf(recName);
                if (plain != null) {
                    b.beginCallNativeRecord(plain);
                    emitNode(cn.getArg(), b, argCount, localMap);
                    b.endCallNativeRecord();
                    return;
                }
                throw new IllegalStateException(
                    "CallNativeIR:record-ABI references unregistered native '"
                    + recName + "' — supports() should have rejected this");
            }
            // ── BDSL inline bypasses for scalar-ABI ──
            // These mirror the record-ABI bypasses above but work on
            // positional args.  They fire BEFORE the typed-handler
            // lookups because string natives (Object args) have no
            // typed handler and would fall through to CallNativeScalar
            // whose @TruffleBoundary makes every call PE-opaque.
            String name = cn.getName();
            int arity = cn.getArgs().size();
            if (arity == 1) {
                NodeIR arg0 = cn.getArgs().get(0);
                // str::size → InlineStringSize (PE-transparent)
                if ("__native_string_size".equals(name)) {
                    b.beginInlineStringSize();
                    emitNode(arg0, b, argCount, localMap);
                    b.endInlineStringSize();
                    return;
                }
                // {int,float,nat,bool}::to_str → InlineScalarInspect
                if ("__native_int_to_str".equals(name)
                        || "__native_float_to_str".equals(name)
                        || "__native_nat_to_str".equals(name)
                        || "__native_bool_to_str".equals(name)) {
                    b.beginInlineScalarInspect(false);
                    emitNode(arg0, b, argCount, localMap);
                    b.endInlineScalarInspect();
                    return;
                }
                // str::trim/to_upper/to_lower/reverse/trim_start/trim_end
                int unaryOp = -1;
                if ("__native_string_trim".equals(name))            unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TRIM;
                else if ("__native_string_to_upper".equals(name))   unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TO_UPPER;
                else if ("__native_string_to_lower".equals(name))   unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TO_LOWER;
                else if ("__native_string_reverse".equals(name))    unaryOp = MezzeBytecodeRootNode.InlineStringUnary.REVERSE;
                else if ("__native_string_trim_start".equals(name)) unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TRIM_START;
                else if ("__native_string_trim_end".equals(name))   unaryOp = MezzeBytecodeRootNode.InlineStringUnary.TRIM_END;
                if (unaryOp >= 0) {
                    b.beginInlineStringUnary(unaryOp);
                    emitNode(arg0, b, argCount, localMap);
                    b.endInlineStringUnary();
                    return;
                }
            } else if (arity == 2) {
                // str::concat → DirectAdd
                if ("__native_string_concat".equals(name)) {
                    b.beginDirectAdd();
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    emitNode(cn.getArgs().get(1), b, argCount, localMap);
                    b.endDirectAdd();
                    return;
                }
                // str::contains/starts_with/ends_with → InlineStringPredicate
                int predOp = -1;
                if ("__native_string_contains".equals(name))        predOp = MezzeBytecodeRootNode.InlineStringPredicate.CONTAINS;
                else if ("__native_string_starts_with".equals(name)) predOp = MezzeBytecodeRootNode.InlineStringPredicate.STARTS_WITH;
                else if ("__native_string_ends_with".equals(name))  predOp = MezzeBytecodeRootNode.InlineStringPredicate.ENDS_WITH;
                if (predOp >= 0) {
                    b.beginInlineStringPredicate(predOp);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    emitNode(cn.getArgs().get(1), b, argCount, localMap);
                    b.endInlineStringPredicate();
                    return;
                }
            }
            // Typed fast paths — bypass Object[] alloc + PositionalArgs
            // + NativeHandler indirection when the signature matches
            // one we registered typed via TypedNativeHandlers. Ordered
            // by argument count so we short-circuit early.
            if (arity == 2) {
                var typedLLL = TypedNativeHandlers.lookupLLL(name);
                if (typedLLL != null) {
                    b.beginCallNativeLLL(typedLLL);
                    for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                    b.endCallNativeLLL();
                    return;
                }
                var typedLLB = TypedNativeHandlers.lookupLLB(name);
                if (typedLLB != null) {
                    b.beginCallNativeLLB(typedLLB);
                    for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                    b.endCallNativeLLB();
                    return;
                }
                var typedDDD = TypedNativeHandlers.lookupDDD(name);
                if (typedDDD != null) {
                    b.beginCallNativeDDD(typedDDD);
                    for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                    b.endCallNativeDDD();
                    return;
                }
                var typedDDB = TypedNativeHandlers.lookupDDB(name);
                if (typedDDB != null) {
                    b.beginCallNativeDDB(typedDDB);
                    for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                    b.endCallNativeDDB();
                    return;
                }
                var typedBBB = TypedNativeHandlers.lookupBBB(name);
                if (typedBBB != null) {
                    b.beginCallNativeBBB(typedBBB);
                    for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                    b.endCallNativeBBB();
                    return;
                }
            } else if (arity == 1) {
                var typedLL = TypedNativeHandlers.lookupLL(name);
                if (typedLL != null) {
                    b.beginCallNativeLL(typedLL);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    b.endCallNativeLL();
                    return;
                }
                var typedDD = TypedNativeHandlers.lookupDD(name);
                if (typedDD != null) {
                    b.beginCallNativeDD(typedDD);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    b.endCallNativeDD();
                    return;
                }
                var typedDB = TypedNativeHandlers.lookupDB(name);
                if (typedDB != null) {
                    b.beginCallNativeDB(typedDB);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    b.endCallNativeDB();
                    return;
                }
                var typedLD = TypedNativeHandlers.lookupLD(name);
                if (typedLD != null) {
                    b.beginCallNativeLD(typedLD);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    b.endCallNativeLD();
                    return;
                }
                var typedDL = TypedNativeHandlers.lookupDL(name);
                if (typedDL != null) {
                    b.beginCallNativeDL(typedDL);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    b.endCallNativeDL();
                    return;
                }
                var typedBB = TypedNativeHandlers.lookupBB(name);
                if (typedBB != null) {
                    b.beginCallNativeBB(typedBB);
                    emitNode(cn.getArgs().get(0), b, argCount, localMap);
                    b.endCallNativeBB();
                    return;
                }
            }
            // Untyped fallback.
            NativeHandler handler = NativeHandlers.INSTANCE.handlerOf(name);
            if (handler == null) {
                throw new IllegalStateException(
                    "CallNativeIR references unregistered native '" + name
                    + "' — supports() should have rejected this");
            }
            boolean needsNode = NativeHandlers.INSTANCE.needsNode(name);
            if (needsNode) {
                b.beginCallNativeScalarWithNode(handler);
                for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                b.endCallNativeScalarWithNode();
            } else {
                b.beginCallNativeScalar(handler);
                for (NodeIR arg : cn.getArgs()) emitNode(arg, b, argCount, localMap);
                b.endCallNativeScalar();
            }
            return;
        }
        throw new UnsupportedOperationException(
            "BytecodeCompiler Phase 1 doesn't support IR node "
            + node.getClass().getSimpleName()
            + ". Callers should check `supports(ir)` first.");
    }

    /**
     * Try to fold {@code CallIndirect(ReadField(x, opName), MakeRecord({r=y}))}
     * into an {@link InlineBinaryOpIR}. Returns null if the pattern doesn't
     * match. Mirrors {@code TruffleCompiler.tryCompileInlineOp} — the AST
     * peephole that keeps ability-method dispatch on primitives from
     * hitting the ReadField crash ("Expected record for field 'gt', got:
     * Long"). Without this fold, HOF callbacks like {@code p = {x} -> x > 10}
     * would crash at runtime.
     */
    /**
     * #346 Group B: detect the desugared shape of `x.to_dbg{}` /
     * `x.to_str{}` — {@code CallIndirect(ReadField(x, "to_dbg"),
     * MakeRecord{it = x})} — and return the receiver expression `x`
     * for the caller to feed into {@code InlineScalarInspect}. Returns
     * null when the CallIndirect isn't this shape.
     *
     * The typechecker emits the same `x` node on both sides of the
     * desugar; we return the ReadField's `record` slot (which BDSL
     * will evaluate once). This matches AST's auto-derived Inspect
     * impl behaviour for scalar receivers.
     */
    private static NodeIR tryFoldInspectMethod(CallIndirectIR ci) {
        if (!(ci.getFunction() instanceof ReadFieldIR rf)) return null;
        String name = rf.getFieldName();
        if (!"to_dbg".equals(name) && !"to_str".equals(name)) return null;
        // The arg must be a MakeRecord — we don't actually need to
        // inspect it since the receiver comes from the ReadField's
        // record slot. But require the shape so we don't fold
        // unrelated calls.
        if (!(ci.getArg() instanceof MakeRecordIR)) return null;
        return rf.getRecord();
    }

    private InlineBinaryOpIR tryFoldInlineOp(CallIndirectIR ci) {
        if (!(ci.getFunction() instanceof ReadFieldIR rf)) return null;
        if (!(ci.getArg() instanceof MakeRecordIR mr)) return null;
        InlineOp op = inlineOpFor(rf.getFieldName());
        if (op == null) return null;
        NodeIR left = rf.getRecord();
        boolean unary = (op == InlineOp.NEG || op == InlineOp.NOT);
        if (unary) {
            return new InlineBinaryOpIR(op, left, left, ci.getSrc());
        }
        for (FieldIR f : mr.getFields()) {
            if ("r".equals(f.getName())) {
                return new InlineBinaryOpIR(op, left, f.getValue(), ci.getSrc());
            }
        }
        return null;
    }

    private static InlineOp inlineOpFor(String opName) {
        switch (opName) {
            case "add": return InlineOp.ADD;
            case "sub": return InlineOp.SUB;
            case "mul": return InlineOp.MUL;
            case "div": return InlineOp.DIV;
            case "mod": return InlineOp.MOD;
            // eq/neq: fold to inline op, mirroring AST's tryCompileInlineOp
            // which unconditionally folds these. Without this, code shaped
            // like `let x : Int = ...; if x == 0 then ...` inside a `perform`
            // block emits ReadFieldByName("eq") on the scalar Long, hitting
            // ReadFieldByName.doNotRecord @Fallback → "Expected record for
            // field 'eq', got: Long" at runtime. See task #346.
            case "eq":  return InlineOp.EQ;
            case "neq": return InlineOp.NEQ;
            case "lt":  return InlineOp.LT;
            case "le":  return InlineOp.LE;
            case "gt":  return InlineOp.GT;
            case "ge":  return InlineOp.GE;
            case "and": return InlineOp.AND;
            case "or":  return InlineOp.OR;
            case "neg": return InlineOp.NEG;
            case "not": return InlineOp.NOT;
            default: return null;
        }
    }

    private void emitInlineBinaryOp(InlineBinaryOpIR bin,
                                    MezzeBytecodeRootNodeGen.Builder b,
                                    int argCount, BytecodeLocal[] localMap) {
        emitNode(bin, b, argCount, localMap);
    }

    /**
     * True iff evaluating {@code node} might return a
     * {@link com.mezze.interpreter.values.MezzeSuspend} — the AST's
     * BlockNode uses this signal to wrap remaining statements in a
     * ComposedBlockThunk (see {@code BlockNode.handleSuspendedEffect}).
     * BDSL blocks discard bare Expr values, so a suspend on such a stmt
     * would be silently swallowed. Conservative: any transitive
     * {@link PerformOpIR} makes this true.
     */
    private static boolean canYieldSuspended(NodeIR node) {
        if (node == null) return false;
        if (node instanceof PerformOpIR) return true;
        if (node instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                NodeIR child = (s instanceof StmtIR.Let l) ? l.getValue()
                             : ((StmtIR.Expr) s).getNode();
                if (canYieldSuspended(child)) return true;
            }
            return canYieldSuspended(blk.getResult());
        }
        if (node instanceof IfIR i) {
            return canYieldSuspended(i.getCond())
                || canYieldSuspended(i.getThenBr())
                || canYieldSuspended(i.getElseBr());
        }
        if (node instanceof WriteLocalIR w) return canYieldSuspended(w.getValue());
        if (node instanceof InlineBinaryOpIR bin) {
            return canYieldSuspended(bin.getLeft()) || canYieldSuspended(bin.getRight());
        }
        // CallDirect/CallIndirect/CallLocal/DictMethod could evaluate a
        // callee whose body performs — but we can't see across function
        // boundaries here cheaply. AST's BlockNode handles the returned
        // MezzeSuspend anyway, so a call-site whose value is dropped
        // is what matters. Be conservative: treat calls as
        // suspend-capable.
        if (node instanceof CallDirectIR) return true;
        if (node instanceof CallIndirectIR) return true;
        if (node instanceof CallLocalIR) return true;
        if (node instanceof DictMethodCallIR) return true;
        if (node instanceof CallNativeIR) return true;
        // Everything else — literals, ReadArg/Local/Global, MakeRecord etc.
        // — cannot produce MezzeSuspend.
        return false;
    }

    /**
     * #446 — maps stdlib arith impl-op wrappers to their {@link InlineOp}
     * equivalent so canon-emit can recognize monomorphized folder bodies
     * shaped as {@code CallDirectIR(add@stdlib-std-int.mz, [ReadLocal(0),
     * ReadLocal(1)])}. Populated lazily on first lookup from the stdlib
     * pre-lowered function map; idempotent under racy init.
     */
    private static final class KnownArithOps {
        private static final java.util.Map<String, InlineOp> KEY_TO_OP;
        static {
            var m = new java.util.HashMap<String, InlineOp>();
            m.put("add@stdlib-std-int.mz", InlineOp.ADD);
            m.put("sub@stdlib-std-int.mz", InlineOp.SUB);
            m.put("mul@stdlib-std-int.mz", InlineOp.MUL);
            m.put("div@stdlib-std-int.mz", InlineOp.DIV);
            m.put("mod@stdlib-std-int.mz", InlineOp.MOD);
            // `eq`/`neq` are deliberately absent: `Eq` is compiler-derived for every
            // type but a newtype, so `impl Eq for Int` is no longer written in
            // `std/int.mz` and there is no file left to name. They are recognized by
            // BODY instead (see NATIVE_TO_OP), which is the identity this table was
            // approximating all along — a function IS the op it calls, whoever wrote
            // it and wherever it lives.
            m.put("lt@stdlib-std-int.mz",  InlineOp.LT);
            m.put("le@stdlib-std-int.mz",  InlineOp.LE);
            m.put("gt@stdlib-std-int.mz",  InlineOp.GT);
            m.put("ge@stdlib-std-int.mz",  InlineOp.GE);
            KEY_TO_OP = java.util.Map.copyOf(m);
        }

        /**
         * Natives that ARE an {@link InlineOp}. A one-line wrapper around one of
         * these is that op, so a call to the wrapper folds to the op itself.
         *
         * Scoped to equality because that is what the derived instances are made of;
         * the arithmetic wrappers still come from KEY_TO_OP above, where a file name
         * is still available to key on.
         */
        private static final java.util.Map<String, InlineOp> NATIVE_TO_OP = java.util.Map.of(
            "__native_int_eq", InlineOp.EQ,
            "__native_int_neq", InlineOp.NEQ
        );

        private static volatile java.util.Map<Hash, InlineOp> hashToOp;

        static InlineOp lookup(Hash targetHash) {
            var map = hashToOp;
            if (map == null) {
                map = build();
                hashToOp = map;
            }
            return map.get(targetHash);
        }

        private static java.util.Map<Hash, InlineOp> build() {
            var pre = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE.getPreloweredFunctions();
            var m = new java.util.HashMap<Hash, InlineOp>();
            for (var e : pre.entrySet()) {
                var fn = e.getValue();
                var key = fn.getName() + "@" + basename(fn.getSourceFile());
                var op = KEY_TO_OP.get(key);
                if (op == null) op = bodyOp(fn.getBody());
                if (op != null) m.put(e.getKey(), op);
            }
            return m;
        }

        /** The op a function's whole body is, when its body is exactly one native call. */
        private static InlineOp bodyOp(NodeIR body) {
            return body instanceof CallNativeIR cn ? NATIVE_TO_OP.get(cn.getName()) : null;
        }

        static String basename(String path) {
            if (path == null) return "";
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }
    }

    /**
     * Maps stdlib Mut wrapper functions to their native inline operations.
     * Same pattern as {@link KnownArithOps}: populated lazily from the
     * pre-lowered function map, looked up by Hash during CallDirectIR
     * emission to replace CallDirect with InlineMutOp1/2/3.
     */
    static final class KnownMutOps {
        /** kind = MUT_* constant for typed fast paths, or MUT_GENERIC_NATIVE
         *  for generic record-ABI bypass.  nativeName/shape are set only
         *  for generic entries; shape is pre-cached at resolve time to skip
         *  ShapeCache lookup on every invocation. */
        record MutOp(int kind, int arity, String nativeName,
                     com.mezze.interpreter.values.MezzeRecord.Shape shape) {
            MutOp(int kind, int arity) { this(kind, arity, null, null); }
            boolean isGeneric() { return kind == MUT_GENERIC_NATIVE; }
        }
        static final int MUT_GENERIC_NATIVE = -2;

        private static final java.util.Map<String, MutOp> KEY_TO_OP;
        static {
            var m = new java.util.HashMap<String, MutOp>();
            // ── MutInt ──
            m.put("read_int@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_READ_INT, 1));
            m.put("int_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_INT_TO_MUT, 1));
            m.put("int_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_INT_TO_IMMUT, 1));
            m.put("incr@mutation.mz",      new MutOp(MezzeBytecodeRootNode.MUT_INCR, 2));
            m.put("decr@mutation.mz",      new MutOp(MezzeBytecodeRootNode.MUT_DECR, 2));
            m.put("write_int@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_INT, 2));
            m.put("mul_int@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_MUL_INT, 2));
            m.put("div_int@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_DIV_INT, 2));
            m.put("mod_int@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_MOD_INT, 2));
            m.put("compare_and_swap@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_CAS_INT, 3));
            // ── MutSeq ──
            m.put("size@mutation.mz",      new MutOp(MezzeBytecodeRootNode.MUT_SIZE, 1));
            m.put("seq_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_TO_MUT, 1));
            m.put("seq_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_TO_IMMUT, 1));
            m.put("get@mutation.mz",       new MutOp(MezzeBytecodeRootNode.MUT_GET, 2));
            m.put("set@mutation.mz",       new MutOp(MezzeBytecodeRootNode.MUT_SET, 3));
            m.put("swap@mutation.mz",      new MutOp(MezzeBytecodeRootNode.MUT_SWAP, 3));
            m.put("sort@mutation.mz",      new MutOp(MezzeBytecodeRootNode.MUT_SEQ_SORT, 1));
            m.put("reverse@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_SEQ_REVERSE, 1));
            m.put("clear@mutation.mz",     new MutOp(MezzeBytecodeRootNode.MUT_SEQ_CLEAR, 1));
            m.put("push@mutation.mz",      new MutOp(MezzeBytecodeRootNode.MUT_SEQ_PUSH, 2));
            m.put("pop@mutation.mz",       new MutOp(MezzeBytecodeRootNode.MUT_SEQ_POP, 1));
            m.put("insert@mutation.mz",    new MutOp(MezzeBytecodeRootNode.MUT_SEQ_INSERT, 3));
            m.put("remove@mutation.mz",    new MutOp(MezzeBytecodeRootNode.MUT_SEQ_REMOVE, 2));
            // ── MutFloat ──
            m.put("read_float@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_READ_FLOAT, 1));
            m.put("float_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_FLOAT_TO_MUT, 1));
            m.put("float_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_FLOAT_TO_IMMUT, 1));
            m.put("write_float@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_FLOAT, 2));
            m.put("add_float@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_ADD_FLOAT, 2));
            m.put("sub_float@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_SUB_FLOAT, 2));
            m.put("mul_float@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_MUL_FLOAT, 2));
            m.put("div_float@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_DIV_FLOAT, 2));
            // ── MutBool ──
            m.put("read_bool@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_READ_BOOL, 1));
            m.put("toggle@mutation.mz",     new MutOp(MezzeBytecodeRootNode.MUT_TOGGLE_BOOL, 1));
            m.put("bool_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_BOOL_TO_MUT, 1));
            m.put("bool_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_BOOL_TO_IMMUT, 1));
            m.put("write_bool@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_BOOL, 2));
            m.put("set_true@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_SET_TRUE, 1));
            m.put("set_false@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_SET_FALSE, 1));
            // ── MutChar ──
            m.put("read_char@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_READ_CHAR, 1));
            m.put("char_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_MUT, 1));
            m.put("char_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_IMMUT, 1));
            m.put("write_char@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_CHAR, 2));
            m.put("char_to_upper@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_UPPER, 1));
            m.put("char_to_lower@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_LOWER, 1));
            m.put("char_is_digit@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_CHAR_IS_DIGIT, 1));
            m.put("char_is_letter@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_CHAR_IS_LETTER, 1));
            m.put("char_is_whitespace@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_IS_WHITESPACE, 1));
            // ── MutString ──
            m.put("string_len@mutation.mz",     new MutOp(MezzeBytecodeRootNode.MUT_STRING_LEN, 1));
            m.put("string_is_empty@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_STRING_IS_EMPTY, 1));
            m.put("read_str@mutation.mz",    new MutOp(MezzeBytecodeRootNode.MUT_STRING_READ, 1));
            m.put("string_clear@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_STRING_CLEAR, 1));
            m.put("string_reverse@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_STRING_REVERSE, 1));
            m.put("string_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_STRING_TO_MUT, 1));
            m.put("string_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_STRING_TO_IMMUT, 1));
            m.put("write_str@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_STRING_WRITE, 2));
            m.put("string_append@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_STRING_APPEND, 2));
            m.put("string_prepend@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_STRING_PREPEND, 2));
            // ── MutMap ──
            m.put("map_size@mutation.mz",    new MutOp(MezzeBytecodeRootNode.MUT_MAP_SIZE, 1));
            m.put("map_clear@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_MAP_CLEAR, 1));
            m.put("map_to_mut@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_MAP_TO_MUT, 1));
            m.put("map_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_MAP_TO_IMMUT, 1));
            m.put("map_contains@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_MAP_CONTAINS, 2));
            m.put("map_get@mutation.mz",     new MutOp(MezzeBytecodeRootNode.MUT_MAP_GET, 2));
            m.put("map_remove@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_MAP_REMOVE, 2));
            m.put("map_put@mutation.mz",     new MutOp(MezzeBytecodeRootNode.MUT_MAP_PUT, 3));
            // ── MutSet ──
            m.put("set_size@mutation.mz",    new MutOp(MezzeBytecodeRootNode.MUT_SET_SIZE, 1));
            m.put("set_clear@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_SET_CLEAR, 1));
            m.put("set_to_mut@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_CSET_TO_MUT, 1));
            m.put("set_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_CSET_TO_IMMUT, 1));
            m.put("set_contains@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_SET_CONTAINS, 2));
            m.put("set_add@mutation.mz",     new MutOp(MezzeBytecodeRootNode.MUT_SET_ADD, 2));
            m.put("set_remove@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_SET_REMOVE, 2));
            m.put("set_toggle@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_SET_TOGGLE, 2));
            // ── MutBytes ──
            m.put("bytes_len@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_BYTES_LEN, 1));
            m.put("bytes_clear@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_BYTES_CLEAR, 1));
            m.put("bytes_reverse@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_BYTES_REVERSE, 1));
            m.put("bytes_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_BYTES_TO_MUT, 1));
            m.put("bytes_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_BYTES_TO_IMMUT, 1));
            m.put("bytes_get@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_BYTES_GET, 2));
            m.put("bytes_set@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_BYTES_SET, 3));
            m.put("bytes_swap@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_BYTES_SWAP, 3));
            // ── MutDeque ──
            m.put("deque_size@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_SIZE, 1));
            m.put("deque_is_empty@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_IS_EMPTY, 1));
            m.put("deque_clear@mutation.mz",  new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_CLEAR, 1));
            m.put("deque_reverse@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_REVERSE, 1));
            m.put("deque_pop_front@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_POP_FRONT, 1));
            m.put("deque_pop_back@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_POP_BACK, 1));
            m.put("deque_peek_front@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PEEK_FRONT, 1));
            m.put("deque_peek_back@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PEEK_BACK, 1));
            m.put("deque_to_mut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_TO_MUT, 1));
            m.put("deque_to_immut@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_TO_IMMUT, 1));
            m.put("deque_push_front@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PUSH_FRONT, 2));
            m.put("deque_push_back@mutation.mz", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PUSH_BACK, 2));
            // ── 4-arg: compiled loop ──
            m.put("for_range@mutation.mz",   new MutOp(MezzeBytecodeRootNode.MUT_FOR_RANGE, 4));
            KEY_TO_OP = java.util.Map.copyOf(m);
        }

        /**
         * Secondary map: native function name → MutOp.
         * Used to identify monomorphized Mut operations whose IR name is
         * empty but whose body is a CallNativeIR after MutEraser β-reduction.
         */
        /**
         * Secondary map: native function name → MutOp.
         * Names match the actual {@code __native_*} calls in mutation.mz
         * after MutEraser β-reduces the Mut.mutate wrapper.
         */
        private static final java.util.Map<String, MutOp> NATIVE_NAME_TO_OP;
        static {
            var n = new java.util.HashMap<String, MutOp>();
            // ── MutInt ──
            n.put("__native_mut_int_read", new MutOp(MezzeBytecodeRootNode.MUT_READ_INT, 1));
            n.put("__native_int_to_mut",   new MutOp(MezzeBytecodeRootNode.MUT_INT_TO_MUT, 1));
            n.put("__native_int_to_immut", new MutOp(MezzeBytecodeRootNode.MUT_INT_TO_IMMUT, 1));
            n.put("__native_mut_int_incr", new MutOp(MezzeBytecodeRootNode.MUT_INCR, 2));
            n.put("__native_mut_int_decr", new MutOp(MezzeBytecodeRootNode.MUT_DECR, 2));
            n.put("__native_mut_int_write", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_INT, 2));
            n.put("__native_mut_int_mul",  new MutOp(MezzeBytecodeRootNode.MUT_MUL_INT, 2));
            n.put("__native_mut_int_div",  new MutOp(MezzeBytecodeRootNode.MUT_DIV_INT, 2));
            n.put("__native_mut_int_mod",  new MutOp(MezzeBytecodeRootNode.MUT_MOD_INT, 2));
            n.put("__native_mut_int_cas",  new MutOp(MezzeBytecodeRootNode.MUT_CAS_INT, 3));
            // ── MutSeq ──
            n.put("__native_mut_seq_len",  new MutOp(MezzeBytecodeRootNode.MUT_SIZE, 1));
            n.put("__native_seq_to_mut",   new MutOp(MezzeBytecodeRootNode.MUT_SEQ_TO_MUT, 1));
            n.put("__native_seq_to_immut", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_TO_IMMUT, 1));
            n.put("__native_mut_seq_get",  new MutOp(MezzeBytecodeRootNode.MUT_GET, 2));
            n.put("__native_mut_seq_set",  new MutOp(MezzeBytecodeRootNode.MUT_SET, 3));
            n.put("__native_mut_seq_swap", new MutOp(MezzeBytecodeRootNode.MUT_SWAP, 3));
            n.put("__native_mut_seq_sort", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_SORT, 1));
            n.put("__native_mut_seq_reverse", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_REVERSE, 1));
            n.put("__native_mut_seq_clear", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_CLEAR, 1));
            n.put("__native_mut_seq_push", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_PUSH, 2));
            n.put("__native_mut_seq_pop",  new MutOp(MezzeBytecodeRootNode.MUT_SEQ_POP, 1));
            n.put("__native_mut_seq_insert", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_INSERT, 3));
            n.put("__native_mut_seq_remove", new MutOp(MezzeBytecodeRootNode.MUT_SEQ_REMOVE, 2));
            // ── MutFloat ──
            n.put("__native_mut_float_read", new MutOp(MezzeBytecodeRootNode.MUT_READ_FLOAT, 1));
            n.put("__native_float_to_mut",   new MutOp(MezzeBytecodeRootNode.MUT_FLOAT_TO_MUT, 1));
            n.put("__native_float_to_immut", new MutOp(MezzeBytecodeRootNode.MUT_FLOAT_TO_IMMUT, 1));
            n.put("__native_mut_float_write", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_FLOAT, 2));
            n.put("__native_mut_float_add",  new MutOp(MezzeBytecodeRootNode.MUT_ADD_FLOAT, 2));
            n.put("__native_mut_float_sub",  new MutOp(MezzeBytecodeRootNode.MUT_SUB_FLOAT, 2));
            n.put("__native_mut_float_mul",  new MutOp(MezzeBytecodeRootNode.MUT_MUL_FLOAT, 2));
            n.put("__native_mut_float_div",  new MutOp(MezzeBytecodeRootNode.MUT_DIV_FLOAT, 2));
            // ── MutBool ──
            n.put("__native_mut_bool_read",  new MutOp(MezzeBytecodeRootNode.MUT_READ_BOOL, 1));
            n.put("__native_mut_bool_toggle", new MutOp(MezzeBytecodeRootNode.MUT_TOGGLE_BOOL, 1));
            n.put("__native_bool_to_mut",    new MutOp(MezzeBytecodeRootNode.MUT_BOOL_TO_MUT, 1));
            n.put("__native_bool_to_immut",  new MutOp(MezzeBytecodeRootNode.MUT_BOOL_TO_IMMUT, 1));
            n.put("__native_mut_bool_write", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_BOOL, 2));
            n.put("__native_mut_bool_set_true", new MutOp(MezzeBytecodeRootNode.MUT_SET_TRUE, 1));
            n.put("__native_mut_bool_set_false", new MutOp(MezzeBytecodeRootNode.MUT_SET_FALSE, 1));
            // ── MutChar ──
            n.put("__native_mut_char_read",  new MutOp(MezzeBytecodeRootNode.MUT_READ_CHAR, 1));
            n.put("__native_char_to_mut",    new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_MUT, 1));
            n.put("__native_char_to_immut",  new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_IMMUT, 1));
            n.put("__native_mut_char_write", new MutOp(MezzeBytecodeRootNode.MUT_WRITE_CHAR, 2));
            n.put("__native_mut_char_to_upper", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_UPPER, 1));
            n.put("__native_mut_char_to_lower", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_TO_LOWER, 1));
            n.put("__native_mut_char_is_digit", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_IS_DIGIT, 1));
            n.put("__native_mut_char_is_letter", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_IS_LETTER, 1));
            n.put("__native_mut_char_is_whitespace", new MutOp(MezzeBytecodeRootNode.MUT_CHAR_IS_WHITESPACE, 1));
            // ── MutString ──
            n.put("__native_mut_string_len",  new MutOp(MezzeBytecodeRootNode.MUT_STRING_LEN, 1));
            n.put("__native_mut_string_is_empty", new MutOp(MezzeBytecodeRootNode.MUT_STRING_IS_EMPTY, 1));
            n.put("__native_mut_string_read", new MutOp(MezzeBytecodeRootNode.MUT_STRING_READ, 1));
            n.put("__native_mut_string_clear", new MutOp(MezzeBytecodeRootNode.MUT_STRING_CLEAR, 1));
            n.put("__native_mut_string_reverse", new MutOp(MezzeBytecodeRootNode.MUT_STRING_REVERSE, 1));
            n.put("__native_string_to_mut",   new MutOp(MezzeBytecodeRootNode.MUT_STRING_TO_MUT, 1));
            n.put("__native_string_to_immut", new MutOp(MezzeBytecodeRootNode.MUT_STRING_TO_IMMUT, 1));
            n.put("__native_mut_string_write", new MutOp(MezzeBytecodeRootNode.MUT_STRING_WRITE, 2));
            n.put("__native_mut_string_append", new MutOp(MezzeBytecodeRootNode.MUT_STRING_APPEND, 2));
            n.put("__native_mut_string_prepend", new MutOp(MezzeBytecodeRootNode.MUT_STRING_PREPEND, 2));
            // ── MutMap ──
            n.put("__native_mut_map_size",    new MutOp(MezzeBytecodeRootNode.MUT_MAP_SIZE, 1));
            n.put("__native_mut_map_clear",   new MutOp(MezzeBytecodeRootNode.MUT_MAP_CLEAR, 1));
            n.put("__native_map_to_mut",      new MutOp(MezzeBytecodeRootNode.MUT_MAP_TO_MUT, 1));
            n.put("__native_map_to_immut",    new MutOp(MezzeBytecodeRootNode.MUT_MAP_TO_IMMUT, 1));
            n.put("__native_mut_map_contains", new MutOp(MezzeBytecodeRootNode.MUT_MAP_CONTAINS, 2));
            n.put("__native_mut_map_get",     new MutOp(MezzeBytecodeRootNode.MUT_MAP_GET, 2));
            n.put("__native_mut_map_remove",  new MutOp(MezzeBytecodeRootNode.MUT_MAP_REMOVE, 2));
            n.put("__native_mut_map_put",     new MutOp(MezzeBytecodeRootNode.MUT_MAP_PUT, 3));
            // ── MutSet ──
            n.put("__native_mut_set_size",    new MutOp(MezzeBytecodeRootNode.MUT_SET_SIZE, 1));
            n.put("__native_mut_set_clear",   new MutOp(MezzeBytecodeRootNode.MUT_SET_CLEAR, 1));
            n.put("__native_set_to_mut",      new MutOp(MezzeBytecodeRootNode.MUT_CSET_TO_MUT, 1));
            n.put("__native_set_to_immut",    new MutOp(MezzeBytecodeRootNode.MUT_CSET_TO_IMMUT, 1));
            n.put("__native_mut_set_contains", new MutOp(MezzeBytecodeRootNode.MUT_SET_CONTAINS, 2));
            n.put("__native_mut_set_add",     new MutOp(MezzeBytecodeRootNode.MUT_SET_ADD, 2));
            n.put("__native_mut_set_remove",  new MutOp(MezzeBytecodeRootNode.MUT_SET_REMOVE, 2));
            n.put("__native_mut_set_toggle",  new MutOp(MezzeBytecodeRootNode.MUT_SET_TOGGLE, 2));
            // ── MutBytes ──
            n.put("__native_mut_bytes_len",   new MutOp(MezzeBytecodeRootNode.MUT_BYTES_LEN, 1));
            n.put("__native_mut_bytes_clear", new MutOp(MezzeBytecodeRootNode.MUT_BYTES_CLEAR, 1));
            n.put("__native_mut_bytes_reverse", new MutOp(MezzeBytecodeRootNode.MUT_BYTES_REVERSE, 1));
            n.put("__native_bytes_to_mut",    new MutOp(MezzeBytecodeRootNode.MUT_BYTES_TO_MUT, 1));
            n.put("__native_bytes_to_immut",  new MutOp(MezzeBytecodeRootNode.MUT_BYTES_TO_IMMUT, 1));
            n.put("__native_mut_bytes_get",   new MutOp(MezzeBytecodeRootNode.MUT_BYTES_GET, 2));
            n.put("__native_mut_bytes_set",   new MutOp(MezzeBytecodeRootNode.MUT_BYTES_SET, 3));
            n.put("__native_mut_bytes_swap",  new MutOp(MezzeBytecodeRootNode.MUT_BYTES_SWAP, 3));
            // ── MutDeque ──
            n.put("__native_mut_deque_size",   new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_SIZE, 1));
            n.put("__native_mut_deque_is_empty", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_IS_EMPTY, 1));
            n.put("__native_mut_deque_clear",  new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_CLEAR, 1));
            n.put("__native_mut_deque_reverse", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_REVERSE, 1));
            n.put("__native_mut_deque_pop_front", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_POP_FRONT, 1));
            n.put("__native_mut_deque_pop_back", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_POP_BACK, 1));
            n.put("__native_mut_deque_peek_front", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PEEK_FRONT, 1));
            n.put("__native_mut_deque_peek_back", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PEEK_BACK, 1));
            n.put("__native_deque_to_mut",    new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_TO_MUT, 1));
            n.put("__native_deque_to_immut",  new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_TO_IMMUT, 1));
            n.put("__native_mut_deque_push_front", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PUSH_FRONT, 2));
            n.put("__native_mut_deque_push_back", new MutOp(MezzeBytecodeRootNode.MUT_DEQUE_PUSH_BACK, 2));
            // for_range is not a single native call — identified by name only
            NATIVE_NAME_TO_OP = java.util.Map.copyOf(n);
        }

        private static volatile java.util.Map<Hash, MutOp> hashToOp;
        /** Extra entries discovered at compile time (monomorphized variants). */
        private static final java.util.concurrent.ConcurrentHashMap<Hash, MutOp> dynamicOps =
            new java.util.concurrent.ConcurrentHashMap<>();

        private static final MutOp NEGATIVE = new MutOp(-1, -1);

        static MutOp lookup(Hash targetHash) {
            var map = hashToOp;
            if (map == null) {
                map = build();
                hashToOp = map;
            }
            var op = map.get(targetHash);
            if (op != null) return op;
            op = dynamicOps.get(targetHash);
            return op != null && op != NEGATIVE ? op : null;
        }

        /**
         * Fallback lookup for monomorphized functions: resolve the target
         * FunctionIR by hash and check name+source against KEY_TO_OP.
         * When the name is empty (monomorphized), inspects the function
         * body — after MutEraser β-reduction it is a CallNativeIR whose
         * native name uniquely identifies the Mut operation.
         * Caches the result (positive or negative) in dynamicOps.
         */
        static MutOp lookupOrResolve(Hash targetHash, MezzeLanguage lang) {
            var op = lookup(targetHash);
            if (op != null) return op;
            // Check negative cache
            if (dynamicOps.containsKey(targetHash)) return null;
            // No language context — can't resolve dynamically (unit tests)
            if (lang == null) return null;
            // Try resolving via the language's function registry
            var fn = lang.lookupFunctionIR(targetHash);
            if (fn != null) {
                // First try name-based lookup (works for non-monomorphized)
                var name = fn.getName();
                if (name != null && !name.isEmpty()) {
                    var key = name + "@" + KnownArithOps.basename(fn.getSourceFile());
                    op = KEY_TO_OP.get(key);
                    if (op != null) {
                        dynamicOps.put(targetHash, op);
                        return op;
                    }
                }
                // Monomorphized functions have empty names — identify by
                // the native call in their body. Two shapes:
                // (a) Post-MutEraser: body IS a CallNativeIR directly.
                // (b) Pre-MutEraser (mono'd): body is PerformOpIR("mutate",
                //     MakeRecordIR({act=MakeLambdaIR(lambdaHash)})) — look
                //     through the lambda to find the CallNativeIR inside.
                if (name == null || name.isEmpty()) {
                    op = resolveByBody(fn, lang);
                    if (op != null) {
                        dynamicOps.put(targetHash, op);
                        return op;
                    }
                }
            }
            // Negative cache
            dynamicOps.put(targetHash, NEGATIVE);
            return null;
        }

        /**
         * Identify a Mut operation by its body structure.
         *
         * Shape A (post-MutEraser): body = CallNativeIR("__native_*").
         *   This only matches known typed ops in NATIVE_NAME_TO_OP, because
         *   any function with a CallNativeIR body matches Shape A — not just
         *   Mut wrappers.  (e.g. monomorphized seq::sort_by<Int> also has
         *   CallNativeIR body.)
         *
         * Shape B (monomorphized, pre-erasure): body = PerformOpIR("mutate",
         *   MakeRecordIR({act = MakeLambdaIR(λhash)})).  This is UNAMBIGUOUSLY
         *   a Mut wrapper.  Both typed and generic ops match here.
         */
        private static MutOp resolveByBody(
                com.mezze.language.truffleir.FunctionIR fn, MezzeLanguage lang) {
            var body = fn.getBody();

            // Shape A: direct native call (post-MutEraser)
            // ONLY typed ops — Shape A is ambiguous (matches any native body)
            if (body instanceof com.mezze.language.truffleir.CallNativeIR nir) {
                return NATIVE_NAME_TO_OP.get(nir.getName());
            }

            // Shape B: PerformOpIR("mutate", {act = λ})
            // Both typed AND generic — this is unambiguously a Mut wrapper
            if (body instanceof com.mezze.language.truffleir.PerformOpIR pop
                    && "mutate".equals(pop.getOperationName())
                    && pop.getArg() instanceof com.mezze.language.truffleir.MakeRecordIR mr
                    && mr.getFields().size() == 1
                    && "act".equals(mr.getFields().get(0).getName())
                    && mr.getFields().get(0).getValue()
                        instanceof com.mezze.language.truffleir.MakeLambdaIR ml) {
                var lambdaFn = lang.lookupFunctionIR(ml.getFunctionHash());
                if (lambdaFn != null
                        && lambdaFn.getBody()
                            instanceof com.mezze.language.truffleir.CallNativeIR nir) {
                    // Typed fast path first
                    var typed = NATIVE_NAME_TO_OP.get(nir.getName());
                    if (typed != null) return typed;
                    // Generic: extract field names from the native's record arg
                    var fieldNames = extractFieldNames(nir);
                    if (fieldNames == null) return null;
                    // Check NativeDispatch has a handler for this native
                    var handler = com.mezze.interpreter.nodes.builtin.NativeDispatch
                        .recordHandlerOf(nir.getName());
                    if (handler == null) return null;
                    // Pre-cache the Shape at compile time to skip ShapeCache
                    // lookup on every invocation at runtime
                    var shape = com.mezze.interpreter.values.ShapeCache
                        .getOrCreateAllObjects(fieldNames);
                    return new MutOp(MUT_GENERIC_NATIVE, fieldNames.length,
                        nir.getName(), shape);
                }
            }
            return null;
        }

        /** Extract field names from a record-ABI CallNativeIR's arg. */
        private static String[] extractFieldNames(
                com.mezze.language.truffleir.CallNativeIR nir) {
            // Record-ABI: arg is a MakeRecordIR with named fields
            if (nir.getArg() instanceof com.mezze.language.truffleir.MakeRecordIR mr) {
                var fields = mr.getFields();
                var names = new String[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    names[i] = fields.get(i).getName();
                }
                return names;
            }
            // Scalar-ABI: args are positional, no field names available
            // (would need NativeSignatures, which may not be on classpath)
            return null;
        }

        private static java.util.Map<Hash, MutOp> build() {
            var pre = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE
                .getPreloweredFunctions();
            var m = new java.util.HashMap<Hash, MutOp>();
            for (var e : pre.entrySet()) {
                var fn = e.getValue();
                var key = fn.getName() + "@" + KnownArithOps.basename(fn.getSourceFile());
                var op = KEY_TO_OP.get(key);
                if (op != null) m.put(e.getKey(), op);
            }
            return m;
        }
    }

    private static final class FolderShape {
        final InlineOp op;
        final int leftSlot;
        final int rightSlot;
        // Optional element pre-transform: elem = elemOp(elem, elemRhs).
        // Set when the fold body is compound, e.g. { acc, x } -> acc + x * x.
        // null means no pre-transform (simple fold).
        final InlineOp elemOp;
        final long elemRhs;
        final boolean elemSelfOp; // true for x*x style (both operands are the same param)
        FolderShape(InlineOp op, int l, int r) {
            this(op, l, r, null, 0, false);
        }
        FolderShape(InlineOp op, int l, int r,
                    InlineOp elemOp, long elemRhs, boolean elemSelfOp) {
            this.op = op; this.leftSlot = l; this.rightSlot = r;
            this.elemOp = elemOp; this.elemRhs = elemRhs; this.elemSelfOp = elemSelfOp;
        }
    }

    private static FolderShape classifyFolderBody(NodeIR body) {
        if (body instanceof InlineBinaryOpIR bin) {
            if (bin.getLeft() instanceof ReadLocalIR l && bin.getRight() instanceof ReadLocalIR r) {
                return new FolderShape(bin.getOp(), l.getSlot(), r.getSlot());
            }
            // Compound fold: acc <OP> f(x) where f(x) is a binary expr.
            // E.g. { acc, x } -> acc + x * x  or  { acc, x } -> acc + x * 2
            if (bin.getLeft() instanceof ReadLocalIR accRead
                    && bin.getRight() instanceof InlineBinaryOpIR sub) {
                var elemShape = classifyElemTransform(sub, accRead.getSlot());
                if (elemShape != null) {
                    return new FolderShape(bin.getOp(), accRead.getSlot(), elemShape.elemSlot,
                            elemShape.op, elemShape.rhs, elemShape.selfOp);
                }
            }
            // Swapped: f(x) <OP> acc (rare but possible for commutative ops)
            if (bin.getRight() instanceof ReadLocalIR accRead2
                    && bin.getLeft() instanceof InlineBinaryOpIR sub2) {
                var elemShape = classifyElemTransform(sub2, accRead2.getSlot());
                if (elemShape != null) {
                    return new FolderShape(bin.getOp(), accRead2.getSlot(), elemShape.elemSlot,
                            elemShape.op, elemShape.rhs, elemShape.selfOp);
                }
            }
            return null;
        }
        if (body instanceof CallDirectIR call) {
            if (!call.isScalarABI()) return null;
            if (call.getArgs().size() != 2) return null;
            if (!(call.getArgs().get(0) instanceof ReadLocalIR l)) return null;
            if (!(call.getArgs().get(1) instanceof ReadLocalIR r)) return null;
            var op = KnownArithOps.lookup(call.getTargetHash());
            if (op == null) return null;
            return new FolderShape(op, l.getSlot(), r.getSlot());
        }
        // #431 — dict-method call shape: `acc.add {it=acc, r=x}` from the
        // desugaring of `acc + x` when mono didn't specialize the folder body.
        // Recognize function=ReadFieldIR(record=ReadLocalIR(receiver), name=op)
        // + arg=MakeRecordIR{it=receiver, r=other}, where op ∈ {add, sub, mul, ...}.
        if (body instanceof CallIndirectIR ind) {
            if (!(ind.getFunction() instanceof ReadFieldIR rf)) return null;
            if (!(rf.getRecord() instanceof ReadLocalIR recvRead)) return null;
            var opName = rf.getFieldName();
            var inlineOp = ABILITY_OP_NAMES.get(opName);
            if (inlineOp == null) return null;
            if (!(ind.getArg() instanceof MakeRecordIR mr)) return null;
            if (mr.getFields().size() != 2) return null;
            // Fields: {it = receiver, r = other}. Extract the "r" value.
            NodeIR rExpr = null;
            for (var fld : mr.getFields()) {
                if ("r".equals(fld.getName())) rExpr = fld.getValue();
                // "it" is required to equal recvRead; the arg record encoding
                // always echoes the receiver as "it" — no need to verify.
            }
            if (rExpr instanceof ReadLocalIR rSlot) {
                return new FolderShape(inlineOp, recvRead.getSlot(), rSlot.getSlot());
            }
            // Compound fold: r is itself a dict-method dispatch, e.g.
            // acc.add{it=acc, r=x.mul{it=x, r=x}} for `acc + x * x`.
            var elemXform = classifyElemTransformDict(rExpr, recvRead.getSlot());
            if (elemXform != null) {
                return new FolderShape(inlineOp, recvRead.getSlot(), elemXform.elemSlot,
                        elemXform.op, elemXform.rhs, elemXform.selfOp);
            }
            return null;
        }
        return null;
    }

    /**
     * Classify a dict-method dispatch sub-expression as a unary element transform.
     * Handles: x.mul{it=x, r=x} (self-op, e.g. x*x), x.mul{it=x, r=lit} (lit-op).
     */
    private static ElemTransform classifyElemTransformDict(NodeIR node, int accSlot) {
        if (!(node instanceof CallIndirectIR sub)) return null;
        if (!(sub.getFunction() instanceof ReadFieldIR subRf)) return null;
        if (!(subRf.getRecord() instanceof ReadLocalIR subRecv)) return null;
        if (subRecv.getSlot() == accSlot) return null; // element transform must not reference acc
        var subOp = ABILITY_OP_NAMES.get(subRf.getFieldName());
        if (subOp == null) return null;
        if (!(sub.getArg() instanceof MakeRecordIR subMr)) return null;
        if (subMr.getFields().size() != 2) return null;
        NodeIR subR = null;
        for (var fld : subMr.getFields()) {
            if ("r".equals(fld.getName())) subR = fld.getValue();
        }
        if (subR == null) return null;
        // Self-op: x.mul{it=x, r=x} — both operands are the same param
        if (subR instanceof ReadLocalIR rr && rr.getSlot() == subRecv.getSlot()) {
            return new ElemTransform(subOp, subRecv.getSlot(), 0, true);
        }
        // Lit-op: x.mul{it=x, r=<literal>}
        if (subR instanceof LongLitIR rLit) {
            return new ElemTransform(subOp, subRecv.getSlot(), rLit.getValue(), false);
        }
        return null;
    }

    /** Result of classifying an element pre-transform in a compound fold body. */
    private record ElemTransform(InlineOp op, int elemSlot, long rhs, boolean selfOp) {}

    /**
     * Classify a sub-expression as a unary element transform: x <OP> x,
     * x <OP> lit, or lit <OP> x. Used by classifyFolderBody to decompose
     * compound fold bodies like { acc, x } -> acc + x * x.
     *
     * @param sub the sub-expression (right-hand side of the fold op)
     * @param accSlot the accumulator param slot (to reject if the sub uses acc)
     */
    private static ElemTransform classifyElemTransform(InlineBinaryOpIR sub, int accSlot) {
        NodeIR left = sub.getLeft(), right = sub.getRight();
        // x <OP> x — self-op (e.g. x * x for squaring)
        if (left instanceof ReadLocalIR ll && right instanceof ReadLocalIR rr
                && ll.getSlot() == rr.getSlot() && ll.getSlot() != accSlot) {
            return new ElemTransform(sub.getOp(), ll.getSlot(), 0, true);
        }
        // x <OP> lit
        if (left instanceof ReadLocalIR ll2 && ll2.getSlot() != accSlot
                && right instanceof LongLitIR rLit) {
            return new ElemTransform(sub.getOp(), ll2.getSlot(), rLit.getValue(), false);
        }
        // lit <OP> x
        if (left instanceof LongLitIR lLit && right instanceof ReadLocalIR rr2
                && rr2.getSlot() != accSlot) {
            // Rewrite as x <OP> lit for commutative ops; for non-commutative,
            // this is rare and we bail out.
            var op = sub.getOp();
            if (op == InlineOp.ADD || op == InlineOp.MUL
                    || op == InlineOp.EQ || op == InlineOp.NEQ
                    || op == InlineOp.AND || op == InlineOp.OR) {
                return new ElemTransform(op, rr2.getSlot(), lLit.getValue(), false);
            }
            return null;
        }
        return null;
    }

    /**
     * #431 — ability-method names (as emitted by the desugaring of infix
     * operators like `+`, `-`) mapped to their {@link InlineOp}. Only ops
     * that have direct-inline BDSL equivalents are listed; anything else
     * bails out of fusion and falls through to the general call path.
     */
    private static final java.util.Map<String, InlineOp> ABILITY_OP_NAMES = java.util.Map.ofEntries(
        java.util.Map.entry("add", InlineOp.ADD),
        java.util.Map.entry("sub", InlineOp.SUB),
        java.util.Map.entry("mul", InlineOp.MUL),
        java.util.Map.entry("div", InlineOp.DIV),
        java.util.Map.entry("mod", InlineOp.MOD),
        java.util.Map.entry("eq",  InlineOp.EQ),
        java.util.Map.entry("neq", InlineOp.NEQ),
        java.util.Map.entry("lt",  InlineOp.LT),
        java.util.Map.entry("le",  InlineOp.LE),
        java.util.Map.entry("gt",  InlineOp.GT),
        java.util.Map.entry("ge",  InlineOp.GE),
        java.util.Map.entry("and", InlineOp.AND),
        java.util.Map.entry("or",  InlineOp.OR)
    );


    /**
     * #446 Phase 4b — emit a canonicalized {@link FusedChainIR} directly.
     * The canonicalizer has already resolved (source × stages × sink) into
     * a structural form, so this bypasses the pattern-matching peel and
     * drives {@link #emitFusedLoop} from the classified
     * stages/sink.
     *
     * <p>Returns {@code true} when emission fired; {@code false} to fall
     * through to the throwing intercept, which surfaces canon/emit skew.
     * (Today the canonicalizer refuses to produce anything the emit can't
     * handle — Opaque source with no stages, uncompilable folder body —
     * so a {@code false} return is a real skew bug, not expected.)
     *
     * <p>Supported today:
     * <ul>
     *   <li>Source: RangeSeq (counted loop) or RawSeq (SeqSize/SeqGet loop).
     *       Opaque falls through to false — canonicalizer refuses to
     *       produce it as a fold source.</li>
     *   <li>Stages: any prefix of Map/Filter, at most one of each (MVP).
     *       Multi-map or multi-filter → false (composition is Phase 4c).</li>
     *   <li>Sink: Fold(init, folder) where folder classifies as an
     *       {@link InlineOp} on ({@code acc}, {@code x}) in natural order,
     *       or swapped for a commutative op.</li>
     * </ul>
     *
     * <p>Everything the pre-canon {@link #tryEmitFusedFold} path was
     * doing via pattern peel is now driven by pre-resolved IR:
     * <pre>
     *   pre-canon:  CallDirect(fold, [map(filter(range, p), f), init, folder])
     *               → peel .map / .filter / range → classify folder → emit
     *   canonical:  IterChain(RangeSeq(s,e), [Map(f), Filter(p)], Fold(init, folder))
     *               → classify folder + stages → emit directly
     * </pre>
     */
    private boolean emitCanonicalIterChain(
            FusedChainIR chain,
            MezzeBytecodeRootNodeGen.Builder b,
            int argCount,
            com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {
        boolean dbg = System.getenv("MEZZE_CHAIN_CANON_EMIT_DEBUG") != null;

        // ── Phase 4a: classify the sink into a FusedSinkMode ──
        FusedSinkMode sinkMode = classifySinkMode(chain, dbg);
        if (sinkMode == null) return false;

        // ── Phase 4b: classify pipeline stages ──
        var classifiedStages = new java.util.ArrayList<ClassifiedStage>();
        for (var stage : chain.getStages()) {
            if (stage instanceof StageIR.Map mapStage) {
                var shape = classifyUnaryLiteralLambda(mapStage.getF(), language);
                if (shape == null) {
                    if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable map lambda");
                    return false;
                }
                if (shape.callHash != null) {
                    classifiedStages.add(new ClassifiedStage.MapCall(
                        shape.callHash, shape.callScalarABI, shape.callFieldName));
                } else {
                    switch (shape.op) {
                        case ADD, SUB, MUL, DIV, MOD -> {}
                        default -> {
                            if (dbg) System.err.println("[chain-canon-emit] reject: map op not arith: " + shape.op);
                            return false;
                        }
                    }
                    classifiedStages.add(new ClassifiedStage.MapInline(
                        shape.op, shape.rhsLit, shape.rhsNode, shape.selfOp));
                }
            } else if (stage instanceof StageIR.Filter filterStage) {
                var predShape = classifyUnaryPredicateLambda(filterStage.getP(), language);
                if (predShape == null) {
                    if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable filter lambda");
                    return false;
                }
                classifiedStages.add(new ClassifiedStage.FilterStep(new FilterPeel(
                    predShape.preOp, predShape.preRhs, predShape.preRhsNode,
                    predShape.cmpOp, predShape.cmpRhs, predShape.cmpRhsNode,
                    predShape.callHash, predShape.callScalarABI, predShape.callFieldName,
                    /*innerXs=*/ null)));
            } else if (stage instanceof StageIR.Take takeStage) {
                classifiedStages.add(new ClassifiedStage.TakeStep(takeStage.getN()));
            } else if (stage instanceof StageIR.Drop dropStage) {
                classifiedStages.add(new ClassifiedStage.DropStep(dropStage.getN()));
            } else if (stage instanceof StageIR.TakeWhile twStage) {
                var predShape = classifyUnaryPredicateLambda(twStage.getP(), language);
                if (predShape == null) {
                    if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable take_while lambda");
                    return false;
                }
                classifiedStages.add(new ClassifiedStage.TakeWhileStep(new FilterPeel(
                    predShape.preOp, predShape.preRhs, predShape.preRhsNode,
                    predShape.cmpOp, predShape.cmpRhs, predShape.cmpRhsNode,
                    predShape.callHash, predShape.callScalarABI, predShape.callFieldName,
                    /*innerXs=*/ null)));
            } else if (stage instanceof StageIR.DropWhile dwStage) {
                var predShape = classifyUnaryPredicateLambda(dwStage.getP(), language);
                if (predShape == null) {
                    if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable drop_while lambda");
                    return false;
                }
                classifiedStages.add(new ClassifiedStage.DropWhileStep(new FilterPeel(
                    predShape.preOp, predShape.preRhs, predShape.preRhsNode,
                    predShape.cmpOp, predShape.cmpRhs, predShape.cmpRhsNode,
                    predShape.callHash, predShape.callScalarABI, predShape.callFieldName,
                    /*innerXs=*/ null)));
            }
        }
        java.util.Collections.reverse(classifiedStages);
        boolean hasStages = !classifiedStages.isEmpty();

        // ── Phase 4c: classify source ──
        NodeIR rangeStart = null, rangeEnd = null, rangeStep = null;
        NodeIR rawXsExpr = null;
        NodeIR rawListExpr = null;
        boolean isSumSink = sinkMode instanceof FusedSinkMode.Accumulate a
                && !a.ignoreElement() && a.op() == InlineOp.ADD
                && a.elemOp() == null  // exclude compound folds (e.g. acc + x*x)
                && a.initExpr() instanceof LongLitIR;  // exclude SumFloat (double init)
        if (chain.getSource() instanceof SourceIR.RangeSeq rangeSrc) {
            rangeStart = rangeSrc.getStart();
            rangeEnd = rangeSrc.getEnd();
        } else if (chain.getSource() instanceof SourceIR.RangeStepSeq stepSrc) {
            rangeStart = stepSrc.getStart();
            rangeEnd = stepSrc.getEnd();
            rangeStep = stepSrc.getStep();
        } else if (chain.getSource() instanceof SourceIR.RawSeq rawSrc) {
            rawXsExpr = rawSrc.getXs();
        } else if (chain.getSource() instanceof SourceIR.RawList rawListSrc) {
            rawListExpr = rawListSrc.getXs();
        } else {
            // Opaque source — the runtime value could be Seq, List, Deque, etc.
            // BuiltinSumLongSeq only handles MezzeSeq; passing other types
            // crashes with UnsupportedSpecializationException.  Bail so the
            // unfused native dispatch runs instead.
            if (dbg) System.err.println("[chain-canon-emit] reject: unhandled source/sink combo");
            return false;
        }

        // ── Fast paths — skip the generic loop for simple builtins ──
        if (sinkMode instanceof FusedSinkMode.Accumulate a && !a.ignoreElement()) {
            // #427 fast path: ADD folder over range with no stages → BuiltinSumRangeLong.
            // Guard: init must be a long literal (not double) — SumFloat must not take this path.
            if (rangeStart != null && rangeStep == null && a.op() == InlineOp.ADD && !hasStages
                    && a.initExpr() instanceof LongLitIR) {
                b.beginBuiltinSumRangeLong();
                  emitNode(rangeStart, b, argCount, localMap);
                  emitNode(rangeEnd,   b, argCount, localMap);
                  emitNode(a.initExpr(), b, argCount, localMap);
                b.endBuiltinSumRangeLong();
                if (dbg) System.err.println("[chain-canon-emit] emitted BuiltinSumRangeLong");
                return true;
            }
            // Sum over RawSeq with no stages → BuiltinSumLongSeq.
            // GUARD: BuiltinSumLongSeq (SeqSumLongScalarNode) starts from a
            // hard-coded `long acc = 0` and only accumulates Long/Integer
            // elements — it never reads the fold's `init` and silently drops
            // Float/double elements. So it is only correct when the init is
            // exactly the long identity `0`. For a non-zero init (drops the
            // init → wrong total) or a Float fold (double init/elements read
            // as 0 → returns 0.0), fall through to the generic emitFusedLoop,
            // which threads init and dispatches the add polymorphically.
            boolean longZeroInit = a.initExpr() instanceof LongLitIR ll
                    && ll.getValue() == 0L;
            if (rawXsExpr != null && isSumSink && !hasStages && longZeroInit) {
                b.beginBuiltinSumLongSeq();
                  emitNode(rawXsExpr, b, argCount, localMap);
                b.endBuiltinSumLongSeq();
                if (dbg) System.err.println("[chain-canon-emit] emitted BuiltinSumLongSeq (RawSeq+Sum)");
                return true;
            }
            // Sum over RawList with no stages → ListToLongSeq + BuiltinSumLongSeq.
            if (rawListExpr != null && isSumSink && !hasStages) {
                b.beginBuiltinSumLongSeq();
                  b.beginListToLongSeq();
                    emitNode(rawListExpr, b, argCount, localMap);
                  b.endListToLongSeq();
                b.endBuiltinSumLongSeq();
                if (dbg) System.err.println("[chain-canon-emit] emitted ListToLongSeq+BuiltinSumLongSeq");
                return true;
            }
        }

        // ── Phase 4d-fast: try FusedLongPipeline / FusedLongRangePipeline ──
        // For no-stages cases (plain fold), also allow FLP for list sources
        // since ListToLongSeq+FLP outperforms cons-cell traversal.
        // Also enter FLP when the fold body has a compound element transform
        // (e.g. acc + x*x) — the element op is injected as a virtual map stage.
        boolean hasFoldElemTransform = sinkMode instanceof FusedSinkMode.Accumulate a2
                && a2.elemOp() != null;
        boolean hasShortCircuitSink = sinkMode instanceof FusedSinkMode.ShortCircuit;
        boolean hasReduceSink = sinkMode instanceof FusedSinkMode.ReduceOpt;
        // BUG 8: a Materialize sink (`to_seq` / `to_list`) is TYPE-PRESERVING — it
        // says nothing about the element type, unlike every other sink here (sum,
        // a numeric fold, a reduce with a long op), which only make sense over
        // numbers. On its own it therefore admitted a seq of records into
        // FusedLongPipeline, whose loop reads each element as
        // `((Number) raw).longValue()`: `sorted_set::from_seq { xs = [records] }`
        // then `to_seq` died with "MezzeRecord cannot be cast to Number".
        //
        // With STAGES present the pipeline is constrained back to numbers —
        // tryBuildFusedLongPipeline rejects any stage whose operand is not a long
        // literal — so the sink is only unsafe when it stands alone. Requiring a
        // stage makes this term redundant with `hasStages` and is deliberately
        // written that way: the condition should read as "a Materialize sink is
        // not by itself a reason to fuse".
        boolean hasMaterializeSink = sinkMode instanceof FusedSinkMode.Materialize && hasStages;
        if (hasStages || rawListExpr != null || hasFoldElemTransform || hasShortCircuitSink || hasReduceSink || hasMaterializeSink) {
            var flp = tryBuildFusedLongPipeline(classifiedStages, sinkMode);
            if (flp != null) {
                if (rangeStart != null && rangeStep == null) {
                    // Range source (step=1) → FusedLongRangePipeline (no seq allocation at all)
                    b.beginFusedLongRangePipeline(flp.config);
                      emitNode(rangeStart, b, argCount, localMap);             // child 0: start
                      emitNode(rangeEnd,   b, argCount, localMap);             // child 1: end
                      emitFLPOperand(flp.mapLit, flp.mapNode, b, argCount, localMap);
                      emitFLPOperand(flp.filterPreLit, flp.filterPreNode, b, argCount, localMap);
                      emitFLPOperand(flp.filterCmpLit, flp.filterCmpNode, b, argCount, localMap);
                      emitFLPOperand(flp.sinkPredPreLit, flp.sinkPredPreNode, b, argCount, localMap);
                      emitFLPOperand(flp.sinkPredCmpLit, flp.sinkPredCmpNode, b, argCount, localMap);
                      emitFLPOperand(flp.foldInitLit, flp.foldInitNode, b, argCount, localMap);
                      emitFLPOperand(flp.takeLimitLit, flp.takeLimitNode, b, argCount, localMap);
                    b.endFusedLongRangePipeline();
                    if (dbg) System.err.println("[chain-canon-emit] emitted FusedLongRangePipeline ("
                            + sinkMode.getClass().getSimpleName() + ", " + classifiedStages.size() + " stages)");
                    return true;
                } else if (rawXsExpr != null) {
                    // Seq source → FusedLongPipeline (existing path)
                    b.beginFusedLongPipeline(flp.config);
                      emitNode(rawXsExpr, b, argCount, localMap);              // child 0: seq
                      emitFLPOperand(flp.mapLit, flp.mapNode, b, argCount, localMap);
                      emitFLPOperand(flp.filterPreLit, flp.filterPreNode, b, argCount, localMap);
                      emitFLPOperand(flp.filterCmpLit, flp.filterCmpNode, b, argCount, localMap);
                      emitFLPOperand(flp.sinkPredPreLit, flp.sinkPredPreNode, b, argCount, localMap);
                      emitFLPOperand(flp.sinkPredCmpLit, flp.sinkPredCmpNode, b, argCount, localMap);
                      emitFLPOperand(flp.foldInitLit, flp.foldInitNode, b, argCount, localMap);
                      emitFLPOperand(flp.takeLimitLit, flp.takeLimitNode, b, argCount, localMap);
                    b.endFusedLongPipeline();
                    if (dbg) System.err.println("[chain-canon-emit] emitted FusedLongPipeline ("
                            + sinkMode.getClass().getSimpleName() + ", " + classifiedStages.size() + " stages)");
                    return true;
                } else if (rawListExpr != null) {
                    // List source → ListToLongSeq + FusedLongPipeline.
                    // Convert MezzeList to MezzeSeq (long-backed) first, then
                    // run FLP for scalar-fast fold/sum/count. The O(n) conversion
                    // pays for itself: FLP iteration over contiguous long[] is
                    // much faster than cons-cell traversal with boxing.
                    b.beginFusedLongPipeline(flp.config);
                      b.beginListToLongSeq();
                        emitNode(rawListExpr, b, argCount, localMap);
                      b.endListToLongSeq();
                      emitFLPOperand(flp.mapLit, flp.mapNode, b, argCount, localMap);
                      emitFLPOperand(flp.filterPreLit, flp.filterPreNode, b, argCount, localMap);
                      emitFLPOperand(flp.filterCmpLit, flp.filterCmpNode, b, argCount, localMap);
                      emitFLPOperand(flp.sinkPredPreLit, flp.sinkPredPreNode, b, argCount, localMap);
                      emitFLPOperand(flp.sinkPredCmpLit, flp.sinkPredCmpNode, b, argCount, localMap);
                      emitFLPOperand(flp.foldInitLit, flp.foldInitNode, b, argCount, localMap);
                      emitFLPOperand(flp.takeLimitLit, flp.takeLimitNode, b, argCount, localMap);
                    b.endFusedLongPipeline();
                    if (dbg) System.err.println("[chain-canon-emit] emitted ListToLongSeq+FusedLongPipeline ("
                            + sinkMode.getClass().getSimpleName() + ", " + classifiedStages.size() + " stages)");
                    return true;
                }
            }
        }

        // ── Phase 4d: emit the generic fused loop (fallback) ──
        emitFusedLoop(classifiedStages, rangeStart, rangeEnd, rangeStep, rawXsExpr,
                rawListExpr, sinkMode, b, argCount, localMap);
        if (dbg) System.err.println("[chain-canon-emit] emitted fused loop ("
                + sinkMode.getClass().getSimpleName() + ", " + classifiedStages.size() + " stages)");
        return true;
    }

    /**
     * FusedLongPipeline emit specification. {@code config} is the packed
     * int that becomes a {@code @ConstantOperand}. Each numeric operand
     * has a {@code lit} value (used when {@code node} is null) and an
     * optional {@code node} (emitted as a dynamic child when non-null).
     */
    private static final class FLPSpec {
        final int config;
        final long mapLit;           final NodeIR mapNode;
        final long filterPreLit;     final NodeIR filterPreNode;
        final long filterCmpLit;     final NodeIR filterCmpNode;
        final long sinkPredPreLit;   final NodeIR sinkPredPreNode;
        final long sinkPredCmpLit;   final NodeIR sinkPredCmpNode;
        final long foldInitLit;      final NodeIR foldInitNode;
        final long takeLimitLit;     final NodeIR takeLimitNode;
        FLPSpec(int config,
                long mapLit, NodeIR mapNode,
                long filterPreLit, NodeIR filterPreNode,
                long filterCmpLit, NodeIR filterCmpNode,
                long sinkPredPreLit, NodeIR sinkPredPreNode,
                long sinkPredCmpLit, NodeIR sinkPredCmpNode,
                long foldInitLit, NodeIR foldInitNode,
                long takeLimitLit, NodeIR takeLimitNode) {
            this.config = config;
            this.mapLit = mapLit;               this.mapNode = mapNode;
            this.filterPreLit = filterPreLit;   this.filterPreNode = filterPreNode;
            this.filterCmpLit = filterCmpLit;   this.filterCmpNode = filterCmpNode;
            this.sinkPredPreLit = sinkPredPreLit; this.sinkPredPreNode = sinkPredPreNode;
            this.sinkPredCmpLit = sinkPredCmpLit; this.sinkPredCmpNode = sinkPredCmpNode;
            this.foldInitLit = foldInitLit;     this.foldInitNode = foldInitNode;
            this.takeLimitLit = takeLimitLit;   this.takeLimitNode = takeLimitNode;
        }
    }

    /** Emit a FLP operand: if node is non-null, emit the expression tree;
     *  otherwise emit a LoadConstant with the literal value. */
    private void emitFLPOperand(long lit, NodeIR node,
                                MezzeBytecodeRootNodeGen.Builder b,
                                int argCount,
                                BytecodeLocal[] localMap) {
        if (node != null) {
            emitNode(node, b, argCount, localMap);
        } else {
            b.emitLoadConstant(lit);
        }
    }

    /**
     * Try to build a FusedLongPipeline spec for the classified stages +
     * sink. Returns an {@link FLPSpec} or null if the pipeline can't be
     * expressed as inline long ops. Accepts both compile-time constant
     * operands (LongLitIR) and runtime expressions (any NodeIR that
     * evaluates to long).
     */
    private FLPSpec tryBuildFusedLongPipeline(
            java.util.List<ClassifiedStage> stages, FusedSinkMode mode) {
        if (mode instanceof FusedSinkMode.CallEach) return null;

        int mapOp = -1; long mapLit = 0; NodeIR mapNode = null;
        boolean selfMap = false; boolean filterFirst = false;
        int filterPreOp = -1; long filterPreLit = 0; NodeIR filterPreNode = null;
        int filterCmpOp = -1; long filterCmpLit = 0; NodeIR filterCmpNode = null;
        // Take stage support: at most one take, and it must come BEFORE any
        // filter stage so we can implement it as a simple iteration-count clamp.
        boolean hasTake = false; long takeLimitLit = 0; NodeIR takeLimitNode = null;
        // Track whether we've seen a filter before a map (for stage ordering).
        boolean seenFilter = false;

        for (ClassifiedStage stage : stages) {
            if (stage instanceof ClassifiedStage.MapInline mi) {
                if (mapOp >= 0) return null;
                mapOp = mi.op().ordinal();
                // If we saw a filter before this map, the filter goes first.
                if (seenFilter) filterFirst = true;
                selfMap = mi.selfOp();
                if (!selfMap && mi.rhsNode() != null && !(mi.rhsNode() instanceof LongLitIR)) {
                    mapNode = mi.rhsNode();
                } else if (!selfMap) {
                    mapLit = mi.rhsLit();
                }
            } else if (stage instanceof ClassifiedStage.FilterStep fs) {
                if (filterCmpOp >= 0) return null;
                seenFilter = true;
                FilterPeel fp = fs.peel();
                if (fp.callHash() != null) return null;
                if (fp.preOp() != null) {
                    filterPreOp = fp.preOp().ordinal();
                    if (fp.preRhsNode() != null && !(fp.preRhsNode() instanceof LongLitIR)) {
                        filterPreNode = fp.preRhsNode();
                    } else {
                        filterPreLit = fp.preRhs();
                    }
                }
                if (fp.cmpOp() != null) {
                    filterCmpOp = fp.cmpOp().ordinal();
                    if (fp.cmpRhsNode() != null && !(fp.cmpRhsNode() instanceof LongLitIR)) {
                        filterCmpNode = fp.cmpRhsNode();
                    } else {
                        filterCmpLit = fp.cmpRhs();
                    }
                }
            } else if (stage instanceof ClassifiedStage.TakeStep ts) {
                if (hasTake) return null;          // only one take supported
                if (seenFilter) return null;       // take-after-filter needs counter, not supported
                hasTake = true;
                if (ts.n() instanceof LongLitIR lit) {
                    takeLimitLit = lit.getValue();
                } else {
                    takeLimitNode = ts.n();
                }
            } else {
                return null;
            }
        }

        int sinkKind;
        int foldOp = -1; long foldInitLit = 0; NodeIR foldInitNode = null;
        int sinkPredPreOp = -1; long sinkPredPreLit = 0; NodeIR sinkPredPreNode = null;
        int sinkPredCmpOp = -1; long sinkPredCmpLit = 0; NodeIR sinkPredCmpNode = null;

        if (mode instanceof FusedSinkMode.Accumulate a) {
            sinkKind = a.ignoreElement()
                    ? MezzeBytecodeRootNode.FLP_SINK_COUNT
                    : MezzeBytecodeRootNode.FLP_SINK_SUM;
            foldOp = a.op().ordinal();
            if (a.initExpr() instanceof LongLitIR lit) {
                foldInitLit = lit.getValue();
            } else {
                foldInitNode = a.initExpr();
            }
            // Compound fold body decomposition: if the fold lambda has an
            // element pre-transform (e.g. acc + x*x → elemOp=MUL, elemSelfOp)
            // and no explicit Map stage was in the pipeline, inject it as the map.
            if (a.elemOp() != null && mapOp < 0) {
                mapOp = a.elemOp().ordinal();
                selfMap = a.elemSelfOp();
                if (!selfMap) {
                    mapLit = a.elemRhs();
                }
            }
        } else if (mode instanceof FusedSinkMode.ShortCircuit sc) {
            sinkKind = switch (sc.kind()) {
                case ANY -> MezzeBytecodeRootNode.FLP_SINK_ANY;
                case ALL -> MezzeBytecodeRootNode.FLP_SINK_ALL;
                case FIND -> MezzeBytecodeRootNode.FLP_SINK_FIND;
                case NONE -> MezzeBytecodeRootNode.FLP_SINK_NONE;
                case FIND_INDEX -> -1; // Not supported in FLP
            };
            if (sinkKind < 0) return null;
            FilterPeel sp = sc.predPeel();
            if (sp.callHash() != null) return null;
            sinkPredPreOp = sp.preOp() != null ? sp.preOp().ordinal() : -1;
            sinkPredCmpOp = sp.cmpOp() != null ? sp.cmpOp().ordinal() : -1;
            if (sp.preRhsNode() != null && !(sp.preRhsNode() instanceof LongLitIR)) {
                sinkPredPreNode = sp.preRhsNode();
            } else {
                sinkPredPreLit = sp.preRhs();
            }
            if (sp.cmpRhsNode() != null && !(sp.cmpRhsNode() instanceof LongLitIR)) {
                sinkPredCmpNode = sp.cmpRhsNode();
            } else {
                sinkPredCmpLit = sp.cmpRhs();
            }
        } else if (mode instanceof FusedSinkMode.Materialize mat) {
            sinkKind = switch (mat.target()) {
                case SEQ -> MezzeBytecodeRootNode.FLP_SINK_TO_SEQ;
                case LIST -> MezzeBytecodeRootNode.FLP_SINK_TO_LIST;
            };
        } else if (mode instanceof FusedSinkMode.ReduceOpt red) {
            sinkKind = MezzeBytecodeRootNode.FLP_SINK_REDUCE;
            foldOp = red.op().ordinal();
            // foldInit is unused for reduce (acc = first element), but
            // the operand slot is still passed — 0 is harmless.
        } else {
            return null;
        }

        int config = MezzeBytecodeRootNode.flpPackConfig(
                sinkKind, mapOp, filterPreOp, filterCmpOp,
                sinkPredPreOp, sinkPredCmpOp, foldOp, selfMap, filterFirst, hasTake);
        return new FLPSpec(config,
                mapLit, mapNode, filterPreLit, filterPreNode,
                filterCmpLit, filterCmpNode,
                sinkPredPreLit, sinkPredPreNode,
                sinkPredCmpLit, sinkPredCmpNode,
                foldInitLit, foldInitNode,
                takeLimitLit, takeLimitNode);
    }

    /**
     * #417: map an {@link InlineOp} to the {@link com.mezze.datastructures.nodes.SeqReduceLongSIMDNode}
     * op ordinal, or -1 if the op isn't associative-reducible (e.g. SUB,
     * DIV, MOD, comparisons that produce bool). The compiler only routes
     * fold-fusion to SIMD when this returns a non-negative ordinal.
     */
    private static int mapToSimdOpLong(InlineOp op) {
        return switch (op) {
            case ADD -> com.mezze.datastructures.nodes.SeqReduceLongSIMDNode.OP_ADD;
            case MUL -> com.mezze.datastructures.nodes.SeqReduceLongSIMDNode.OP_MUL;
            // AND/OR here mean logical-and-of-longs, not bitwise; only
            // safe to SIMD-reduce for booleans-as-longs (0/1), which is
            // rare. Skip for now.
            default -> -1;
        };
    }

    /**
     * #407 helper: emit `<accSlot> <op> <xSlot>` using the same
     * BeginDirectXxx ops the {@link InlineBinaryOpIR} handler uses.
     * Kept in sync with that handler's switch.
     */
    private void emitInlineOpTwoLocals(InlineOp op,
                                       com.oracle.truffle.api.bytecode.BytecodeLocal a,
                                       com.oracle.truffle.api.bytecode.BytecodeLocal x,
                                       MezzeBytecodeRootNodeGen.Builder b) {
        switch (op) {
            case ADD -> { b.beginDirectAdd(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectAdd(); }
            case SUB -> { b.beginDirectSubtract(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectSubtract(); }
            case MUL -> { b.beginDirectMultiply(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectMultiply(); }
            case DIV -> { b.beginDirectDivide(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectDivide(); }
            case MOD -> { b.beginDirectModulo(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectModulo(); }
            case EQ  -> { b.beginDirectEqual(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectEqual(); }
            case NEQ -> { b.beginDirectNotEqual(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectNotEqual(); }
            case LT  -> { b.beginDirectLessThan(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectLessThan(); }
            case LE  -> { b.beginDirectLessThanOrEqual(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectLessThanOrEqual(); }
            case GT  -> { b.beginDirectGreaterThan(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectGreaterThan(); }
            case GE  -> { b.beginDirectGreaterThanOrEqual(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectGreaterThanOrEqual(); }
            case AND -> { b.beginDirectAnd(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectAnd(); }
            case OR  -> { b.beginDirectOr(); b.emitLoadLocal(a); b.emitLoadLocal(x); b.endDirectOr(); }
            default -> throw new IllegalStateException("emitInlineOpTwoLocals: unsupported op " + op);
        }
    }

    /**
     * #437 — emit the shared range-fused Block/While loop body. Both
     * {@link #tryEmitFusedFold} and the sum-sink path
     * ({@link #tryEmitBuiltinSeqReducer}) delegate here so map/filter
     * peel + range fusion share one code path.
     *
     * <p>Layout (semantics identical to the folder inline shape):
     * <pre>
     *   Block {
     *     i    = Estart
     *     end  = Eend
     *     acc  = Einit
     *     While (i < end) {
     *       x = i
     *       if (predOp(x, predRhsLit)) {              // if pred peeled
     *         x = x <mapperOp> mapperRhsLit           // if map peeled
     *         acc = acc <op> x
     *       }
     *       i = i + 1
     *     }
     *     acc
     *   }
     * </pre>
     * Peel data-flow order: predicate always sees the raw range element;
     * map runs after filter; fold step runs last on the mapped element.
     *
     * <p>Callers own the outer {@code beginBlock}/{@code endBlock} — this
     * helper emits only the interior (stores, while, final load).
     * @param initEmit — call it to emit the init value expression; use
     *                   a lambda to invoke {@link #emitNode} on any NodeIR,
     *                   or a direct {@code b.emitLoadLongConstant(0L)} for
     *                   the sum case.
     */
    private void emitRangeFusedLoop(MezzeBytecodeRootNodeGen.Builder b,
                                    int argCount,
                                    com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap,
                                    com.oracle.truffle.api.bytecode.BytecodeLocal accSlot,
                                    com.oracle.truffle.api.bytecode.BytecodeLocal iSlot,
                                    com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                    NodeIR rangeStart, NodeIR rangeEnd,
                                    Runnable initEmit,
                                    InlineOp op,
                                    InlineOp mapperOp, long mapperRhsLit, NodeIR mapperRhsNode,
                                    Hash mapperCallHash,
                                    boolean mapperCallScalarABI,
                                    String mapperCallFieldName,
                                    FilterPeel filter) {
        var endSlot = b.createLocal();
        b.beginStoreLocal(iSlot);
        emitNode(rangeStart, b, argCount, localMap);
        b.endStoreLocal();
        b.beginStoreLocal(endSlot);
        emitNode(rangeEnd, b, argCount, localMap);
        b.endStoreLocal();
        b.beginStoreLocal(accSlot);
        initEmit.run();
        b.endStoreLocal();

        // #515 — loop-unroll gate. Env-controlled so we can A/B on/off:
        //   MEZZE_UNROLL=0 or 1   disable (scalar loop only)
        //   MEZZE_UNROLL=N        unroll factor N (default 2)
        // When unroll factor >= 2 we emit a main loop that runs N bodies
        // per trip (each on i+0..i+N-1), then a scalar tail loop for the
        // straggler <N iters. All bodies share the same predicate/mapper/
        // fold semantics; we only replicate to reduce loop overhead and
        // give the JIT wider ILP.
        int unroll = 2;
        String unrollEnv = System.getenv("MEZZE_UNROLL");
        if (unrollEnv != null) {
            try {
                int parsed = Integer.parseInt(unrollEnv.trim());
                if (parsed >= 0) unroll = parsed;
            } catch (NumberFormatException ignore) { /* keep default */ }
        }

        if (unroll >= 2) {
            // Compute end_unroll = end - (unroll - 1) so main loop only
            // runs when at least `unroll` iters remain.
            var endUnrollSlot = b.createLocal();
            b.beginStoreLocal(endUnrollSlot);
              b.beginDirectSubtract();
              b.emitLoadLocal(endSlot);
              b.emitLoadLongConstant((long)(unroll - 1));
              b.endDirectSubtract();
            b.endStoreLocal();

            // Main unrolled loop.
            b.beginWhile();
              b.beginDirectLessThan();
              b.emitLoadLocal(iSlot);
              b.emitLoadLocal(endUnrollSlot);
              b.endDirectLessThan();
              b.beginBlock();
                for (int k = 0; k < unroll; k++) {
                    emitFusedBodyAt(b, iSlot, xSlot, accSlot, (long) k,
                        op, mapperOp, mapperRhsLit, mapperRhsNode,
                        mapperCallHash, mapperCallScalarABI, mapperCallFieldName,
                        filter, argCount, localMap);
                }
                // i = i + unroll
                b.beginStoreLocal(iSlot);
                  b.beginDirectAdd();
                  b.emitLoadLocal(iSlot);
                  b.emitLoadLongConstant((long) unroll);
                  b.endDirectAdd();
                b.endStoreLocal();
              b.endBlock();
            b.endWhile();
        }

        // Scalar (tail) loop — also the sole loop when unroll < 2.
        b.beginWhile();
          b.beginDirectLessThan();
          b.emitLoadLocal(iSlot);
          b.emitLoadLocal(endSlot);
          b.endDirectLessThan();
          b.beginBlock();
            emitFusedBodyAt(b, iSlot, xSlot, accSlot, 0L,
                op, mapperOp, mapperRhsLit, mapperRhsNode,
                mapperCallHash, mapperCallScalarABI, mapperCallFieldName,
                filter, argCount, localMap);
            b.beginStoreLocal(iSlot);
              b.beginDirectAdd();
              b.emitLoadLocal(iSlot);
              b.emitLoadLongConstant(1L);
              b.endDirectAdd();
            b.endStoreLocal();
          b.endBlock();
        b.endWhile();
        b.emitLoadLocal(accSlot);
        if (System.getenv("MEZZE_FOLD_FUSE_DEBUG") != null) {
            System.err.println("[fold-fuse] emitted range-fused loop for op=" + op
                + " mapper=" + (mapperCallHash != null ? "CALL " + mapperCallHash.getHex().substring(0, 8) : mapperOp)
                + " pred=" + describeFilter(filter)
                + " unroll=" + unroll);
        }
    }

    /**
     * #515 — emit one fused body step for element i+offset:
     * <pre>
     *   xSlot = i + offset           (or just i, when offset == 0)
     *   if (pred(xSlot)) {
     *     xSlot = map(xSlot)
     *     acc   = acc <op> xSlot
     *   }
     * </pre>
     * Shared by both the unrolled main loop (offset 0..unroll-1) and the
     * scalar tail loop (offset 0). Predicate/mapper/fold operate on
     * xSlot exactly as in the un-unrolled emit — this is a semantics-
     * preserving replication.
     */
    private void emitFusedBodyAt(MezzeBytecodeRootNodeGen.Builder b,
                                 com.oracle.truffle.api.bytecode.BytecodeLocal iSlot,
                                 com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                 com.oracle.truffle.api.bytecode.BytecodeLocal accSlot,
                                 long offset,
                                 InlineOp op,
                                 InlineOp mapperOp, long mapperRhsLit, NodeIR mapperRhsNode,
                                 Hash mapperCallHash,
                                 boolean mapperCallScalarABI,
                                 String mapperCallFieldName,
                                 FilterPeel filter,
                                 int argCount,
                                 com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {
        // xSlot = i + offset  (offset==0 → xSlot = i)
        b.beginStoreLocal(xSlot);
          if (offset == 0L) {
              b.emitLoadLocal(iSlot);
          } else {
              b.beginDirectAdd();
              b.emitLoadLocal(iSlot);
              b.emitLoadLongConstant(offset);
              b.endDirectAdd();
          }
        b.endStoreLocal();

        if (filter != null) {
            b.beginIfThen();
              emitPredicateCheck(b, xSlot, filter, argCount, localMap);
              b.beginBlock();
                emitMapperStep(b, xSlot, mapperOp, mapperRhsLit, mapperRhsNode,
                    mapperCallHash, mapperCallScalarABI, mapperCallFieldName,
                    argCount, localMap);
                b.beginStoreLocal(accSlot);
                emitInlineOpTwoLocals(op, accSlot, xSlot, b);
                b.endStoreLocal();
              b.endBlock();
            b.endIfThen();
        } else {
            emitMapperStep(b, xSlot, mapperOp, mapperRhsLit, mapperRhsNode,
                mapperCallHash, mapperCallScalarABI, mapperCallFieldName,
                argCount, localMap);
            b.beginStoreLocal(accSlot);
            emitInlineOpTwoLocals(op, accSlot, xSlot, b);
            b.endStoreLocal();
        }
    }

    /**
     * #442 Day 9 — emit the map step inside the range-fused loop body.
     * Three cases:
     *   - No mapper: emit nothing (fold step reads xSlot directly).
     *   - Inline op form (mapperCallHash == null, mapperOp != null):
     *       xSlot = xSlot <mapperOp> mapperRhsLit
     *   - Call form (mapperCallHash != null):
     *       xSlot = user_fn(xSlot)
     *     Emitted as BDSL beginCallDirectN targeting the resolved
     *     CallTarget. Callee must be a pure single-arg function
     *     (isPureSingleArgCallee gate). Scalar-ABI (1 arg) is preferred;
     *     if the callee's ABI needs a record wrapper, we synthesize a
     *     one-field MakeRecord — but that allocs per iter, defeating
     *     the point. In practice, mono specializes to scalar-ABI when
     *     the callee is small and single-typed.
     */
    private void emitMapperStep(MezzeBytecodeRootNodeGen.Builder b,
                                com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                InlineOp mapperOp, long mapperRhsLit, NodeIR mapperRhsNode,
                                Hash mapperCallHash,
                                boolean mapperCallScalarABI,
                                String mapperCallFieldName,
                                int argCount,
                                com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {
        if (mapperCallHash != null) {
            var target = resolveCallTarget(mapperCallHash);
            if (target == null && language != null) {
                target = language.getOrMintUserStub(mapperCallHash, "fused_map_step");
            }
            if (target != null) {
                // #442 Day 9 fix — stage the arg through a fresh local before
                // the call. The naive `beginStoreLocal(xSlot); call(xSlot);
                // endStoreLocal` shape aliases the same slot as read source
                // AND write target across the call. For callees with their
                // own local slots, BDSL's frame model can shift storage
                // during the call, corrupting the arg-load. A fresh
                // `argCopy` local decouples the two roles: argCopy holds the
                // input value for the entire call duration, and xSlot is
                // stored to with the result. This is the general fix that
                // makes the peel correct for arbitrary user-fn callees.
                var argCopy = b.createLocal();
                b.beginStoreLocal(argCopy);
                  b.emitLoadLocal(xSlot);
                b.endStoreLocal();
                b.beginStoreLocal(xSlot);
                  emitCallOneArg(b, target, argCopy, mapperCallScalarABI, mapperCallFieldName);
                b.endStoreLocal();
                return;
            }
            // Fallthrough: hash didn't resolve and stub couldn't be minted —
            // emit nothing, xSlot stays as-is.
        }
        if (mapperOp != null) {
            // Inline op is safe as-is: the emitted op reads xSlot on the
            // BDSL stack, applies the op, produces a value. No callee =
            // no frame-slot aliasing risk.
            // #515 — mapperRhsNode wins over mapperRhsLit when non-null
            // (captured RHS lifted to outer-frame slot).
            b.beginStoreLocal(xSlot);
            emitInlineOpLocalRhs(mapperOp, xSlot, mapperRhsLit, mapperRhsNode,
                                 argCount, localMap, b);
            b.endStoreLocal();
        }
    }

    /**
     * #442 Day 9 — emit `target(xSlot)` in whichever ABI form the callsite
     * originally used. Result is left on the BDSL stack.
     *
     * <ul>
     *   <li>Scalar-ABI ({@code scalarABI == true, fieldName == null}):
     *       {@code beginCallDirectN(target); [ScalarABIMarker if !BDSL]; xSlot; endCallDirectN}
     *   </li>
     *   <li>Record-ABI ({@code scalarABI == false, fieldName != null}):
     *       {@code beginCallDirectN(target); MakeRecord{fieldName = xSlot}; endCallDirectN}
     *       — mimics what the original callsite emitted.
     *   </li>
     * </ul>
     */
    private void emitCallOneArg(MezzeBytecodeRootNodeGen.Builder b,
                                com.oracle.truffle.api.CallTarget target,
                                com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                boolean scalarABI,
                                String fieldName) {
        b.beginCallDirectN(target);
          if (scalarABI) {
              // Same marker rule as the generic CallDirect emit path:
              // AST callees need the marker to disambiguate scalar-ABI
              // 1-arg from record-ABI (arity==1 collision).
              if (!isBdslCallee(target)) {
                  b.emitLoadObjectConstant(
                      com.mezze.interpreter.nodes.function.ScalarABIMarker.INSTANCE);
              }
              b.emitLoadLocal(xSlot);
          } else {
              // Record-ABI: wrap xSlot in a one-field MakeRecord matching
              // the original callsite's field name. This is a per-iter
              // allocation; a proper fix would inline the callee body
              // (deforestation) instead of calling — but that's a larger
              // change; today's win is going from "not fused at all" to
              // "fused with one alloc per iter". Truffle EA often folds
              // the alloc when the callee reads the field once.
              b.beginMakeSingleFieldRecord(new String[] { fieldName });
                b.emitLoadLocal(xSlot);
              b.endMakeSingleFieldRecord();
          }
        b.endCallDirectN();
    }

    /** Debug: describe a FilterPeel as "null" | "cmpOp" | "preOp-then-cmpOp" | "CALL hash8". */
    private static String describeFilter(FilterPeel f) {
        if (f == null) return "null";
        if (f.callHash != null) return "CALL " + f.callHash.getHex().substring(0, 8);
        return f.preOp == null ? f.cmpOp.toString() : (f.preOp + "-then-" + f.cmpOp);
    }

    /**
     * Emit a predicate check that produces a Bool on the stack. For a
     * simple filter {@code x <cmpOp> lit}: emit that op. For a compound
     * {@code (x <preOp> lit1) <cmpOp> lit2}: emit
     * {@code (xSlot <preOp> preRhs) <cmpOp> cmpRhs} inline — no temporary
     * local needed since BDSL operations stack their results.
     */
    private void emitPredicateCheck(MezzeBytecodeRootNodeGen.Builder b,
                                    com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                    FilterPeel filter,
                                    int argCount,
                                    com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {
        if (filter.callHash != null) {
            // #442 Day 9 — call form: user_pred(xSlot) produces Bool on
            // the stack, becoming the IfThen condition. Emit CallDirectN
            // inline using xSlot directly — DO NOT stage through a
            // StoreLocal(argCopy) because emitPredicateCheck may be called
            // as the first child of an IfThen, and StoreLocal is a void
            // operation that the BDSL builder rejects at position 0 of
            // IfThen (which expects a value-producing condition child).
            var target = resolveCallTarget(filter.callHash);
            if (target == null && language != null) {
                target = language.getOrMintUserStub(filter.callHash, "fused_pred_check");
            }
            if (target != null) {
                emitCallOneArg(b, target, xSlot,
                    filter.callScalarABI, filter.callFieldName);
                return;
            }
            // Fallback: hash didn't resolve — emit constant true. Safer
            // than crashing; slightly wrong behavior at runtime (all
            // elements included). Gate should have prevented this.
            b.emitLoadObjectConstant(Boolean.TRUE);
            return;
        }
        if (filter.preOp == null) {
            // Simple: xSlot cmpOp cmpRhs.
            emitInlineOpLocalRhs(filter.cmpOp, xSlot, filter.cmpRhs, filter.cmpRhsNode,
                                 argCount, localMap, b);
        } else {
            // Compound: (xSlot preOp preRhs) cmpOp cmpRhs.
            emitCompareInnerRhs(b, filter.preOp, xSlot, filter.preRhs, filter.preRhsNode,
                                filter.cmpOp, filter.cmpRhs, filter.cmpRhsNode,
                                argCount, localMap);
        }
    }

    /**
     * Emit {@code (xSlot <preOp> preRhs) <cmpOp> cmpRhs} — the compound
     * predicate form. Structure the BDSL calls so the pre-op result is
     * the LHS operand of the comparison, and the cmpRhs literal is the
     * RHS operand.
     */
    private void emitCompareInnerLitLit(MezzeBytecodeRootNodeGen.Builder b,
                                        InlineOp preOp,
                                        com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                        long preRhs,
                                        InlineOp cmpOp, long cmpRhs) {
        switch (cmpOp) {
            case EQ  -> { b.beginDirectEqual(); emitInlineOpLocalLit(preOp, xSlot, preRhs, b); b.emitLoadLongConstant(cmpRhs); b.endDirectEqual(); }
            case NEQ -> { b.beginDirectNotEqual(); emitInlineOpLocalLit(preOp, xSlot, preRhs, b); b.emitLoadLongConstant(cmpRhs); b.endDirectNotEqual(); }
            case LT  -> { b.beginDirectLessThan(); emitInlineOpLocalLit(preOp, xSlot, preRhs, b); b.emitLoadLongConstant(cmpRhs); b.endDirectLessThan(); }
            case LE  -> { b.beginDirectLessThanOrEqual(); emitInlineOpLocalLit(preOp, xSlot, preRhs, b); b.emitLoadLongConstant(cmpRhs); b.endDirectLessThanOrEqual(); }
            case GT  -> { b.beginDirectGreaterThan(); emitInlineOpLocalLit(preOp, xSlot, preRhs, b); b.emitLoadLongConstant(cmpRhs); b.endDirectGreaterThan(); }
            case GE  -> { b.beginDirectGreaterThanOrEqual(); emitInlineOpLocalLit(preOp, xSlot, preRhs, b); b.emitLoadLongConstant(cmpRhs); b.endDirectGreaterThanOrEqual(); }
            default -> throw new IllegalStateException("emitCompareInnerLitLit: unsupported cmp " + cmpOp);
        }
    }

    /**
     * #515 — like {@link #emitCompareInnerLitLit} but each RHS may be
     * supplied as a {@link NodeIR} (typically {@link ReadLocalIR} of an
     * outer-frame capture slot). NodeIR wins over long when non-null.
     */
    private void emitCompareInnerRhs(MezzeBytecodeRootNodeGen.Builder b,
                                     InlineOp preOp,
                                     com.oracle.truffle.api.bytecode.BytecodeLocal xSlot,
                                     long preRhs, NodeIR preRhsNode,
                                     InlineOp cmpOp, long cmpRhs, NodeIR cmpRhsNode,
                                     int argCount,
                                     com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {
        Runnable emitPreOp = () -> emitInlineOpLocalRhs(preOp, xSlot, preRhs, preRhsNode,
                                                        argCount, localMap, b);
        Runnable emitCmpRhs = () -> {
            if (cmpRhsNode != null) emitNode(cmpRhsNode, b, argCount, localMap);
            else b.emitLoadLongConstant(cmpRhs);
        };
        switch (cmpOp) {
            case EQ  -> { b.beginDirectEqual(); emitPreOp.run(); emitCmpRhs.run(); b.endDirectEqual(); }
            case NEQ -> { b.beginDirectNotEqual(); emitPreOp.run(); emitCmpRhs.run(); b.endDirectNotEqual(); }
            case LT  -> { b.beginDirectLessThan(); emitPreOp.run(); emitCmpRhs.run(); b.endDirectLessThan(); }
            case LE  -> { b.beginDirectLessThanOrEqual(); emitPreOp.run(); emitCmpRhs.run(); b.endDirectLessThanOrEqual(); }
            case GT  -> { b.beginDirectGreaterThan(); emitPreOp.run(); emitCmpRhs.run(); b.endDirectGreaterThan(); }
            case GE  -> { b.beginDirectGreaterThanOrEqual(); emitPreOp.run(); emitCmpRhs.run(); b.endDirectGreaterThanOrEqual(); }
            default -> throw new IllegalStateException("emitCompareInnerRhs: unsupported cmp " + cmpOp);
        }
    }

    /**
     * #437 — emit {@code local <op> long-literal}. Used by the range-fused
     * loop when a mapper `{x} -> x <op> lit` or predicate `{x} -> x <cmp> lit`
     * was peeled from xsExpr and needs to be inlined at each iteration.
     * Same op set as {@link #emitInlineOpTwoLocals}.
     */
    private void emitInlineOpLocalLit(InlineOp op,
                                      com.oracle.truffle.api.bytecode.BytecodeLocal x,
                                      long lit,
                                      MezzeBytecodeRootNodeGen.Builder b) {
        switch (op) {
            case ADD -> { b.beginDirectAdd(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectAdd(); }
            case SUB -> { b.beginDirectSubtract(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectSubtract(); }
            case MUL -> { b.beginDirectMultiply(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectMultiply(); }
            case DIV -> { b.beginDirectDivide(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectDivide(); }
            case MOD -> { b.beginDirectModulo(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectModulo(); }
            case EQ  -> { b.beginDirectEqual(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectEqual(); }
            case NEQ -> { b.beginDirectNotEqual(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectNotEqual(); }
            case LT  -> { b.beginDirectLessThan(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectLessThan(); }
            case LE  -> { b.beginDirectLessThanOrEqual(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectLessThanOrEqual(); }
            case GT  -> { b.beginDirectGreaterThan(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectGreaterThan(); }
            case GE  -> { b.beginDirectGreaterThanOrEqual(); b.emitLoadLocal(x); b.emitLoadLongConstant(lit); b.endDirectGreaterThanOrEqual(); }
            default -> throw new IllegalStateException("emitInlineOpLocalLit: unsupported op " + op);
        }
    }

    /**
     * #515 — like {@link #emitInlineOpLocalLit} but RHS may be a
     * {@link NodeIR} (typically {@link ReadLocalIR} on an outer-frame
     * capture slot). NodeIR wins over long when non-null.
     */
    private void emitInlineOpLocalRhs(InlineOp op,
                                      com.oracle.truffle.api.bytecode.BytecodeLocal x,
                                      long lit, NodeIR rhsNode,
                                      int argCount,
                                      com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap,
                                      MezzeBytecodeRootNodeGen.Builder b) {
        if (rhsNode == null) { emitInlineOpLocalLit(op, x, lit, b); return; }
        Runnable emitRhs = () -> emitNode(rhsNode, b, argCount, localMap);
        switch (op) {
            case ADD -> { b.beginDirectAdd(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectAdd(); }
            case SUB -> { b.beginDirectSubtract(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectSubtract(); }
            case MUL -> { b.beginDirectMultiply(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectMultiply(); }
            case DIV -> { b.beginDirectDivide(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectDivide(); }
            case MOD -> { b.beginDirectModulo(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectModulo(); }
            case EQ  -> { b.beginDirectEqual(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectEqual(); }
            case NEQ -> { b.beginDirectNotEqual(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectNotEqual(); }
            case LT  -> { b.beginDirectLessThan(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectLessThan(); }
            case LE  -> { b.beginDirectLessThanOrEqual(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectLessThanOrEqual(); }
            case GT  -> { b.beginDirectGreaterThan(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectGreaterThan(); }
            case GE  -> { b.beginDirectGreaterThanOrEqual(); b.emitLoadLocal(x); emitRhs.run(); b.endDirectGreaterThanOrEqual(); }
            default -> throw new IllegalStateException("emitInlineOpLocalRhs: unsupported op " + op);
        }
    }

    /**
     * Predicate peeled from a seq::filter. Three forms:
     * <ul>
     *   <li><b>Simple inline</b> ({@code preOp==null, callHash==null}):
     *       {@code {x} -> x <cmpOp> cmpRhs}
     *   </li>
     *   <li><b>Compound inline</b> ({@code preOp != null, callHash==null}):
     *       {@code {x} -> (x <preOp> preRhs) <cmpOp> cmpRhs}
     *       — e.g. {@code x % 2 == 0} → preOp=MOD, preRhs=2, cmpOp=EQ, cmpRhs=0.
     *   </li>
     *   <li><b>#442 Day 9 arbitrary-call form</b> ({@code callHash != null}):
     *       {@code {x} -> user_pred {arg = x}} where user_pred is pure and
     *       returns Bool. Fusion emits an inline call as the {@code if}
     *       condition.
     *   </li>
     * </ul>
     */
    /**
     * #515 — extended with optional NodeIR forms for the RHS of preOp/cmpOp.
     * When {@code preRhsNode != null} it is emitted via {@link #emitNode}
     * (typically a {@link ReadLocalIR} pointing at an outer-frame capture
     * slot) INSTEAD of {@code preRhs}. Same for {@code cmpRhsNode}.
     * If both a Node and lit are set, Node wins.
     */
    private record FilterPeel(InlineOp preOp, long preRhs, NodeIR preRhsNode,
                              InlineOp cmpOp, long cmpRhs, NodeIR cmpRhsNode,
                              Hash callHash, boolean callScalarABI, String callFieldName,
                              NodeIR innerXs) {}

    /**
     * Phase 4c — classified pipeline stage, either a map transform or a
     * filter predicate. Built from the canonicalizer's StageIR list.
     */
    private sealed interface ClassifiedStage {
        record MapInline(InlineOp op, long rhsLit, NodeIR rhsNode, boolean selfOp) implements ClassifiedStage {}
        record MapCall(Hash callHash, boolean scalarABI, String fieldName) implements ClassifiedStage {}
        record FilterStep(FilterPeel peel) implements ClassifiedStage {}
        record TakeStep(NodeIR n) implements ClassifiedStage {}
        record DropStep(NodeIR n) implements ClassifiedStage {}
        /** TakeWhile: emit predicate check; if false → break (like filter + early exit). */
        record TakeWhileStep(FilterPeel peel) implements ClassifiedStage {}
        /** DropWhile: boolean state; skip elements while predicate is true. */
        record DropWhileStep(FilterPeel peel) implements ClassifiedStage {}
    }

    // ── Sink mode: the per-element action and post-loop result ──────────

    /** Describes what the fused loop does at each element and what it returns. */
    private sealed interface FusedSinkMode {
        /** acc = init; per element: acc = acc <op> x. Covers Fold/Sum. */
        record Accumulate(InlineOp op, NodeIR initExpr, boolean ignoreElement,
                          InlineOp elemOp, long elemRhs, boolean elemSelfOp) implements FusedSinkMode {
            Accumulate(InlineOp op, NodeIR initExpr, boolean ignoreElement) {
                this(op, initExpr, ignoreElement, null, 0, false);
            }
        }
        /** Call f(x) per element, discard result. */
        record CallEach(com.oracle.truffle.api.CallTarget target, boolean scalarABI,
                        String fieldName) implements FusedSinkMode {}
        /** Short-circuit predicate: Any/All/Find/None. */
        record ShortCircuit(FilterPeel predPeel, ShortCircuitKind kind) implements FusedSinkMode {}
        /** Fold with first element as init; result wrapped in Option. */
        record ReduceOpt(InlineOp op) implements FusedSinkMode {}
        /** Collect elements into a Seq or List. */
        record Materialize(MaterializeTarget target) implements FusedSinkMode {}
        /** First/Last surviving element → Option. FIRST short-circuits; LAST full scan. */
        record ElementCapture(ElementCaptureKind kind) implements FusedSinkMode {}
        /** Min/Max over longs (direct comparison). Full scan → Option. */
        record MinMaxLong(boolean isMax) implements FusedSinkMode {}
        /** Scan: fold that emits each intermediate accumulator into a builder → Seq. */
        record ScanAccum(InlineOp op, NodeIR initExpr) implements FusedSinkMode {}
        /** Min_by/Max_by: call key-extractor f(x), track element with best key → Option. */
        record MinMaxByKey(boolean isMax, com.oracle.truffle.api.CallTarget keyFn,
                           boolean scalarABI, String fieldName) implements FusedSinkMode {}
        /** Partition: predicate split into {pass, fail} record of two Seqs. */
        record PartitionPred(FilterPeel predPeel) implements FusedSinkMode {}

        enum ShortCircuitKind { ANY, ALL, FIND, NONE, FIND_INDEX }
        enum MaterializeTarget { SEQ, LIST }
        enum ElementCaptureKind { FIRST, LAST }
    }

    /**
     * Classify the chain's sink into a {@link FusedSinkMode}, doing
     * lambda/folder classification up front. Returns null on rejection.
     */
    private FusedSinkMode classifySinkMode(FusedChainIR chain, boolean dbg) {
        if (chain.getSink() instanceof SinkIR.Fold foldSink) {
            var folderShape = classifyBinaryFolder(foldSink.getF(), dbg, "folder");
            if (folderShape == null) return null;
            return new FusedSinkMode.Accumulate(folderShape.op, foldSink.getInit(), false,
                    folderShape.elemOp, folderShape.elemRhs, folderShape.elemSelfOp);
        }
        if (chain.getSink() instanceof SinkIR.Sum) {
            return new FusedSinkMode.Accumulate(InlineOp.ADD,
                    new LongLitIR(0L, chain.getSrc()), false);
        }
        if (chain.getSink() instanceof SinkIR.SumFloat) {
            return new FusedSinkMode.Accumulate(InlineOp.ADD,
                    new DoubleLitIR(0.0, chain.getSrc()), false);
        }
        if (chain.getSink() instanceof SinkIR.Product) {
            return new FusedSinkMode.Accumulate(InlineOp.MUL,
                    new LongLitIR(1L, chain.getSrc()), false);
        }
        if (chain.getSink() instanceof SinkIR.ProductFloat) {
            return new FusedSinkMode.Accumulate(InlineOp.MUL,
                    new DoubleLitIR(1.0, chain.getSrc()), false);
        }
        if (chain.getSink() instanceof SinkIR.Count) {
            return new FusedSinkMode.Accumulate(InlineOp.ADD,
                    new LongLitIR(0L, chain.getSrc()), /*ignoreElement=*/ true);
        }
        if (chain.getSink() instanceof SinkIR.ForEach forEachSink) {
            return classifyForEachMode(forEachSink, dbg);
        }
        if (chain.getSink() instanceof SinkIR.Any anySink) {
            return classifyPredicateMode(anySink.getP(), FusedSinkMode.ShortCircuitKind.ANY, dbg);
        }
        if (chain.getSink() instanceof SinkIR.All allSink) {
            return classifyPredicateMode(allSink.getP(), FusedSinkMode.ShortCircuitKind.ALL, dbg);
        }
        if (chain.getSink() instanceof SinkIR.Find findSink) {
            return classifyPredicateMode(findSink.getP(), FusedSinkMode.ShortCircuitKind.FIND, dbg);
        }
        if (chain.getSink() instanceof SinkIR.FindIndex findIndexSink) {
            return classifyPredicateMode(findIndexSink.getP(), FusedSinkMode.ShortCircuitKind.FIND_INDEX, dbg);
        }
        if (chain.getSink() instanceof SinkIR.Reduce reduceSink) {
            var reducerShape = classifyBinaryFolder(reduceSink.getF(), dbg, "reducer");
            if (reducerShape == null) return null;
            return new FusedSinkMode.ReduceOpt(reducerShape.op);
        }
        if (chain.getSink() instanceof SinkIR.ToSeq) {
            return new FusedSinkMode.Materialize(FusedSinkMode.MaterializeTarget.SEQ);
        }
        if (chain.getSink() instanceof SinkIR.ToList) {
            return new FusedSinkMode.Materialize(FusedSinkMode.MaterializeTarget.LIST);
        }
        if (chain.getSink() instanceof SinkIR.None noneSink) {
            return classifyPredicateMode(noneSink.getP(), FusedSinkMode.ShortCircuitKind.NONE, dbg);
        }
        if (chain.getSink() instanceof SinkIR.First) {
            return new FusedSinkMode.ElementCapture(FusedSinkMode.ElementCaptureKind.FIRST);
        }
        if (chain.getSink() instanceof SinkIR.Last) {
            return new FusedSinkMode.ElementCapture(FusedSinkMode.ElementCaptureKind.LAST);
        }
        if (chain.getSink() instanceof SinkIR.Min) {
            return new FusedSinkMode.MinMaxLong(/*isMax=*/ false);
        }
        if (chain.getSink() instanceof SinkIR.Max) {
            return new FusedSinkMode.MinMaxLong(/*isMax=*/ true);
        }
        if (chain.getSink() instanceof SinkIR.Scan scanSink) {
            var folderShape = classifyBinaryFolder(scanSink.getF(), dbg, "scan-folder");
            if (folderShape == null) return null;
            return new FusedSinkMode.ScanAccum(folderShape.op, scanSink.getInit());
        }
        if (chain.getSink() instanceof SinkIR.MinBy minBySink) {
            return classifyMinMaxByMode(minBySink.getF(), /*isMax=*/ false, dbg);
        }
        if (chain.getSink() instanceof SinkIR.MaxBy maxBySink) {
            return classifyMinMaxByMode(maxBySink.getF(), /*isMax=*/ true, dbg);
        }
        if (chain.getSink() instanceof SinkIR.Partition partSink) {
            return classifyPartitionMode(partSink.getP(), dbg);
        }
        if (dbg) System.err.println("[chain-canon-emit] reject: unsupported sink: "
                + chain.getSink().getClass().getSimpleName());
        return null;
    }

    /** Shared classification for Fold and Reduce combining functions. */
    private FolderShape classifyBinaryFolder(MakeLambdaIR lambda, boolean dbg, String label) {
        if (!lambda.getCaptures().isEmpty()) {
            if (dbg) System.err.println("[chain-canon-emit] reject: " + label + " has captures");
            return null;
        }
        var ir = language.lookupFunctionIR(lambda.getFunctionHash());
        if (ir == null || !ir.getCaptureSlots().isEmpty()
                || ir.getParamSlots().size() != 2 || !ir.getLocalSlots().isEmpty()) {
            if (dbg) System.err.println("[chain-canon-emit] reject: " + label + " shape mismatch");
            return null;
        }
        var shape = classifyFolderBody(ir.getBody());
        if (shape == null) {
            if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable " + label + " body");
            return null;
        }
        boolean natural = (shape.leftSlot == 0 && shape.rightSlot == 1);
        boolean swapped = (shape.leftSlot == 1 && shape.rightSlot == 0);
        if (!natural && !swapped) return null;
        InlineOp op = shape.op;
        boolean commutative = (op == InlineOp.ADD || op == InlineOp.MUL
                || op == InlineOp.EQ || op == InlineOp.NEQ
                || op == InlineOp.AND || op == InlineOp.OR);
        if (swapped && !commutative) return null;
        return shape;
    }

    /** Classify a predicate-based sink (Any/All/Find). */
    private FusedSinkMode classifyPredicateMode(
            MakeLambdaIR predicateLambda,
            FusedSinkMode.ShortCircuitKind kind, boolean dbg) {
        var predShape = classifyUnaryPredicateLambda(predicateLambda, language);
        if (predShape == null) {
            if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable "
                    + kind + " predicate");
            return null;
        }
        var predPeel = new FilterPeel(
                predShape.preOp, predShape.preRhs, predShape.preRhsNode,
                predShape.cmpOp, predShape.cmpRhs, predShape.cmpRhsNode,
                predShape.callHash, predShape.callScalarABI, predShape.callFieldName,
                /*innerXs=*/ null);
        return new FusedSinkMode.ShortCircuit(predPeel, kind);
    }

    /** Classify min_by/max_by: resolve the key-extractor function.
     *
     *  Only valid when the KEY was proven directly comparable. The fused sink
     *  compares keys with DirectLessThan, which specializes long/double/String/
     *  Frac and nothing else, while `min_by` itself hands `min_by_with_cmp` a
     *  comparator built from the key's own `Ord` — fusing discards it. For a
     *  record key that crashes; for a newtype key whose Ord disagrees with the
     *  type it wraps it silently returns the WRONG element.
     *
     *  Nothing in the IR used to distinguish those from an Int key: an Int-keyed
     *  and a record-keyed lambda both lower to `ReadFieldIR(ReadLocalIR(slot=0,
     *  OBJECT), fieldName=…)`, differing only in the field NAME. So the compiler
     *  now decides it where the solved type is in hand and carries the answer as
     *  {@link FunctionIR#getReturnOrd()}. Undecided (null) declines the fusion
     *  exactly like INSTANCE — never fuse on a guess.
     *
     *  See known-bugs.md BUG 10; BUG 7 and BUG 8 were the same family. */
    private FusedSinkMode classifyMinMaxByMode(MakeLambdaIR keyLambda, boolean isMax, boolean dbg) {
        var keyIR = language == null ? null : language.lookupFunctionIR(keyLambda.getFunctionHash());
        if (keyIR == null || keyIR.getReturnOrd() != OrdKind.DIRECT) {
            if (dbg) System.err.println("[chain-canon-emit] reject: min/max_by key not proven"
                    + " directly comparable (returnOrd="
                    + (keyIR == null ? "<no ir>" : String.valueOf(keyIR.getReturnOrd())) + ")");
            return null;
        }
        com.oracle.truffle.api.CallTarget target = null;
        boolean scalarABI = false;
        String fieldName = null;

        var shape = classifyUnaryLiteralLambda(keyLambda, language);
        if (shape != null && shape.callHash != null) {
            target = resolveCallTarget(shape.callHash);
            if (target == null && language != null)
                target = language.getOrMintUserStub(shape.callHash, "min_max_by_key");
            if (target != null) { scalarABI = shape.callScalarABI; fieldName = shape.callFieldName; }
        }
        if (target == null) {
            if (keyLambda.getCaptures().isEmpty()) {
                target = resolveCallTarget(keyLambda.getFunctionHash());
                if (target == null && language != null)
                    target = language.getOrMintUserStub(keyLambda.getFunctionHash(), "min_max_by_key");
            }
            if (target == null) {
                if (dbg) System.err.println("[chain-canon-emit] reject: min/max_by key fn not callable");
                return null;
            }
            var ir = language.lookupFunctionIR(keyLambda.getFunctionHash());
            if (ir != null && ir.getParamSlots().size() == 1) { scalarABI = true; fieldName = null; }
        }
        return new FusedSinkMode.MinMaxByKey(isMax, target, scalarABI, fieldName);
    }

    /** Classify partition: resolve the predicate. */
    private FusedSinkMode classifyPartitionMode(MakeLambdaIR predicateLambda, boolean dbg) {
        var predShape = classifyUnaryPredicateLambda(predicateLambda, language);
        if (predShape == null) {
            if (dbg) System.err.println("[chain-canon-emit] reject: unclassifiable partition predicate");
            return null;
        }
        var predPeel = new FilterPeel(
                predShape.preOp, predShape.preRhs, predShape.preRhsNode,
                predShape.cmpOp, predShape.cmpRhs, predShape.cmpRhsNode,
                predShape.callHash, predShape.callScalarABI, predShape.callFieldName,
                /*innerXs=*/ null);
        return new FusedSinkMode.PartitionPred(predPeel);
    }

    /** Classify a ForEach body lambda into a CallEach mode. */
    private FusedSinkMode classifyForEachMode(SinkIR.ForEach sink, boolean dbg) {
        com.oracle.truffle.api.CallTarget target = null;
        boolean scalarABI = false;
        String fieldName = null;

        var shape = classifyUnaryLiteralLambda(sink.getF(), language);
        if (shape != null && shape.callHash != null) {
            target = resolveCallTarget(shape.callHash);
            if (target == null && language != null)
                target = language.getOrMintUserStub(shape.callHash, "for_each_inner");
            if (target != null) { scalarABI = shape.callScalarABI; fieldName = shape.callFieldName; }
        }
        if (target == null) {
            var lambda = sink.getF();
            if (lambda.getCaptures().isEmpty()) {
                target = resolveCallTarget(lambda.getFunctionHash());
                if (target == null && language != null)
                    target = language.getOrMintUserStub(lambda.getFunctionHash(), "for_each_body");
            }
            if (target == null) {
                if (dbg) System.err.println("[chain-canon-emit] reject: ForEach body not callable");
                return null;
            }
            var ir = language.lookupFunctionIR(lambda.getFunctionHash());
            if (ir != null && ir.getParamSlots().size() == 1) { scalarABI = true; fieldName = null; }
        }
        return new FusedSinkMode.CallEach(target, scalarABI, fieldName);
    }

    // ── Generic fused loop emitter ──────────────────────────────────────

    /**
     * Emit a single fused while-loop for any sink mode. The loop
     * scaffold (source init, pipeline stages, counter increment) is
     * shared; only the per-element sink body and post-loop result
     * vary by mode.
     *
     * <pre>
     *   [mode-specific locals]
     *   i = start; end = ...
     *   while (i < end [&& !found]) {
     *     x = element(i)
     *     [stages: filter/map/take/drop]
     *     ── sink body (mode-specific) ──
     *     [close stages; i++]
     *   }
     *   ── result (mode-specific) ──
     * </pre>
     */
    private void emitFusedLoop(
            java.util.List<ClassifiedStage> stages,
            NodeIR rangeStart, NodeIR rangeEnd, NodeIR rangeStep, NodeIR rawXsExpr,
            NodeIR rawListExpr,
            FusedSinkMode mode,
            MezzeBytecodeRootNodeGen.Builder b,
            int argCount,
            com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {

        boolean isRange = rangeStart != null;
        boolean isList = rawListExpr != null;
        boolean useShortCircuitBranch = mode instanceof FusedSinkMode.ShortCircuit;
        boolean hasTakeWhile = stages.stream()
                .anyMatch(s -> s instanceof ClassifiedStage.TakeWhileStep);
        boolean needsEarlyExit = useShortCircuitBranch || hasTakeWhile
                || (mode instanceof FusedSinkMode.ElementCapture ec
                    && ec.kind() == FusedSinkMode.ElementCaptureKind.FIRST);

        b.beginBlock();

        // #442 — forward-branch exit for short-circuit sinks, TakeWhile
        // stages, and First capture. When the condition triggers, we
        // Branch past the While to exitLabel instead of checking !found
        // on every iteration.
        com.oracle.truffle.api.bytecode.BytecodeLabel exitLabel = null;
        if (needsEarlyExit) {
            exitLabel = b.createLabel();
        }

        // ── Common locals ──
        var iSlot = b.createLocal();
        var xSlot = b.createLocal();

        // ── Mode-specific locals ──
        com.oracle.truffle.api.bytecode.BytecodeLocal accSlot     = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal foundSlot   = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal resultSlot  = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal hasAccSlot  = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal builderSlot = null;

        switch (mode) {
            case FusedSinkMode.Accumulate a -> {
                accSlot = b.createLocal();
                b.beginStoreLocal(accSlot);
                  emitNode(a.initExpr(), b, argCount, localMap);
                b.endStoreLocal();
            }
            case FusedSinkMode.CallEach ignored -> { /* no extra state */ }
            case FusedSinkMode.ShortCircuit sc -> {
                foundSlot = b.createLocal();
                b.beginStoreLocal(foundSlot);
                  b.emitLoadObjectConstant(Boolean.FALSE);
                b.endStoreLocal();
                if (sc.kind() == FusedSinkMode.ShortCircuitKind.FIND) {
                    resultSlot = b.createLocal();
                }
                if (sc.kind() == FusedSinkMode.ShortCircuitKind.FIND_INDEX) {
                    // Dedicated 0-based counter — works for all source types
                    // (list cursor, seq index, range value). Incremented in sink
                    // body after the predicate ifThen so it only counts elements
                    // that reach the sink (respects filter stages).
                    resultSlot = b.createLocal();
                    b.beginStoreLocal(resultSlot);
                      b.emitLoadLongConstant(0L);
                    b.endStoreLocal();
                }
            }
            case FusedSinkMode.ReduceOpt ignored -> {
                accSlot = b.createLocal();
                hasAccSlot = b.createLocal();
                b.beginStoreLocal(hasAccSlot);
                  b.emitLoadObjectConstant(Boolean.FALSE);
                b.endStoreLocal();
            }
            case FusedSinkMode.Materialize ignored -> {
                builderSlot = b.createLocal();
            }
            case FusedSinkMode.ElementCapture ignored -> {
                foundSlot = b.createLocal();
                b.beginStoreLocal(foundSlot);
                  b.emitLoadObjectConstant(Boolean.FALSE);
                b.endStoreLocal();
                resultSlot = b.createLocal();
            }
            case FusedSinkMode.MinMaxLong ignored -> {
                foundSlot = b.createLocal();
                b.beginStoreLocal(foundSlot);
                  b.emitLoadObjectConstant(Boolean.FALSE);
                b.endStoreLocal();
                accSlot = b.createLocal();
            }
            case FusedSinkMode.ScanAccum sa -> {
                accSlot = b.createLocal();
                b.beginStoreLocal(accSlot);
                  emitNode(sa.initExpr(), b, argCount, localMap);
                b.endStoreLocal();
                builderSlot = b.createLocal();
            }
            case FusedSinkMode.MinMaxByKey ignored -> {
                // foundSlot: have we seen any element yet?
                // accSlot: current best element
                // resultSlot: current best key (from f(element))
                foundSlot = b.createLocal();
                b.beginStoreLocal(foundSlot);
                  b.emitLoadObjectConstant(Boolean.FALSE);
                b.endStoreLocal();
                accSlot = b.createLocal();       // best element
                resultSlot = b.createLocal();    // best key
            }
            case FusedSinkMode.PartitionPred ignored -> {
                // Two builders: pass and fail
                builderSlot = b.createLocal();   // pass builder
                resultSlot = b.createLocal();    // fail builder
            }
        }

        // ── Source init ──
        com.oracle.truffle.api.bytecode.BytecodeLocal endSlot    = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal stepSlot   = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal seqSlot    = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal sizeSlot   = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal cursorSlot = null;
        boolean hasStep = rangeStep != null;
        // Unboxed long[] iteration is handled by FusedLongPipeline (Phase 4d-fast).
        // The generic fused loop below uses SeqGet (boxed) because the seq may
        // contain non-numeric elements (promises, variants, records, etc.).
        if (isRange) {
            endSlot = b.createLocal();
            b.beginStoreLocal(iSlot);
              emitNode(rangeStart, b, argCount, localMap);
            b.endStoreLocal();
            b.beginStoreLocal(endSlot);
              emitNode(rangeEnd, b, argCount, localMap);
            b.endStoreLocal();
            if (hasStep) {
                stepSlot = b.createLocal();
                b.beginStoreLocal(stepSlot);
                  emitNode(rangeStep, b, argCount, localMap);
                b.endStoreLocal();
            }
        } else if (isList) {
            // List source: direct cons-cell traversal.  Avoids the O(n)
            // ListToArray conversion (allocation + double traversal) by
            // walking the cons chain in place:
            //   cursor = list
            //   while (!ListIsEmpty(cursor)) {
            //       x = ListHead(cursor)
            //       ... body ...
            //       cursor = ListTail(cursor)
            //   }
            cursorSlot = b.createLocal();
            b.beginStoreLocal(cursorSlot);
              emitNode(rawListExpr, b, argCount, localMap);
            b.endStoreLocal();
        } else {
            seqSlot = b.createLocal();
            sizeSlot = b.createLocal();
            b.beginStoreLocal(seqSlot);
              emitNode(rawXsExpr, b, argCount, localMap);
            b.endStoreLocal();
            b.beginStoreLocal(sizeSlot);
              b.beginSeqSize(); b.emitLoadLocal(seqSlot); b.endSeqSize();
            b.endStoreLocal();
            b.beginStoreLocal(iSlot);
              b.emitLoadLongConstant(0L);
            b.endStoreLocal();
        }

        // boundSlot is used by range/seq indexed loops. Cursor-based list
        // loops use ListIsEmpty instead, so boundSlot stays null there.
        var boundSlot = isRange ? endSlot : sizeSlot;

        // ── Init builder for materializing sinks ──
        // RangeSeq sources produce longs — use a long[] builder to avoid
        // boxing. RawSeq sources use the generic ArrayList<Object> builder.
        boolean useLongBuilder = isRange
                && (mode instanceof FusedSinkMode.Materialize || mode instanceof FusedSinkMode.ScanAccum);
        boolean hasFilter = stages.stream().anyMatch(s -> s instanceof ClassifiedStage.FilterStep);
        if (mode instanceof FusedSinkMode.Materialize) {
            // Size hint: range end - start (if range); else seq size.
            // With filters, halve the estimate — most filters pass ~50%
            // of elements; over-alloc wastes zeroing time (80MB→40MB for
            // 10M range). The builder grows if underestimated.
            b.beginStoreLocal(builderSlot);
            if (useLongBuilder) {
                b.beginNewLongSeqBuilder();
                if (hasFilter) {
                    // (end - start) / 2
                    b.beginDirectDivide();
                      b.beginDirectSubtract();
                        b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                      b.endDirectSubtract();
                      b.emitLoadLongConstant(2L);
                    b.endDirectDivide();
                } else {
                    b.beginDirectSubtract();
                      b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                    b.endDirectSubtract();
                }
                b.endNewLongSeqBuilder();
            } else if (isList) {
                // Cursor-based list: no size available upfront.
                // Use a reasonable default — the builder grows if needed.
                b.beginNewSeqBuilder();
                  b.emitLoadLongConstant(64L);
                b.endNewSeqBuilder();
            } else {
                b.beginNewSeqBuilder();
                if (hasFilter) {
                    b.beginDirectDivide();
                      b.emitLoadLocal(sizeSlot);
                      b.emitLoadLongConstant(2L);
                    b.endDirectDivide();
                } else {
                    b.emitLoadLocal(sizeSlot);
                }
                b.endNewSeqBuilder();
            }
            b.endStoreLocal();
        }
        if (mode instanceof FusedSinkMode.ScanAccum) {
            // Scan produces a seq of intermediate accumulator values.
            // Init builder like Materialize but uses the source size directly
            // (scan always emits one value per input element that passes stages).
            b.beginStoreLocal(builderSlot);
            if (useLongBuilder) {
                b.beginNewLongSeqBuilder();
                if (hasFilter) {
                    b.beginDirectDivide();
                      b.beginDirectSubtract();
                        b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                      b.endDirectSubtract();
                      b.emitLoadLongConstant(2L);
                    b.endDirectDivide();
                } else {
                    b.beginDirectSubtract();
                      b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                    b.endDirectSubtract();
                }
                b.endNewLongSeqBuilder();
            } else if (isList) {
                b.beginNewSeqBuilder();
                  b.emitLoadLongConstant(64L);
                b.endNewSeqBuilder();
            } else {
                b.beginNewSeqBuilder();
                if (hasFilter) {
                    b.beginDirectDivide();
                      b.emitLoadLocal(sizeSlot);
                      b.emitLoadLongConstant(2L);
                    b.endDirectDivide();
                } else {
                    b.emitLoadLocal(sizeSlot);
                }
                b.endNewSeqBuilder();
            }
            b.endStoreLocal();
        }
        if (mode instanceof FusedSinkMode.PartitionPred) {
            // Partition: two generic builders (pass + fail). Use half the
            // source size for each — a reasonable starting estimate.
            b.beginStoreLocal(builderSlot);
            b.beginNewSeqBuilder();
            if (isRange) {
                b.beginDirectDivide();
                  b.beginDirectSubtract();
                    b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                  b.endDirectSubtract();
                  b.emitLoadLongConstant(2L);
                b.endDirectDivide();
            } else if (isList) {
                b.emitLoadLongConstant(8L);
            } else {
                b.beginDirectDivide();
                  b.emitLoadLocal(sizeSlot);
                  b.emitLoadLongConstant(2L);
                b.endDirectDivide();
            }
            b.endNewSeqBuilder();
            b.endStoreLocal();
            b.beginStoreLocal(resultSlot);
            b.beginNewSeqBuilder();
            if (isRange) {
                b.beginDirectDivide();
                  b.beginDirectSubtract();
                    b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                  b.endDirectSubtract();
                  b.emitLoadLongConstant(2L);
                b.endDirectDivide();
            } else if (isList) {
                b.emitLoadLongConstant(8L);
            } else {
                b.beginDirectDivide();
                  b.emitLoadLocal(sizeSlot);
                  b.emitLoadLongConstant(2L);
                b.endDirectDivide();
            }
            b.endNewSeqBuilder();
            b.endStoreLocal();
        }

        // ── Pre-allocate Take/Drop counter locals + DropWhile state ──
        var takeDropCounters = new java.util.ArrayList<com.oracle.truffle.api.bytecode.BytecodeLocal>();
        var dropWhileFlags = new java.util.ArrayList<com.oracle.truffle.api.bytecode.BytecodeLocal>();
        for (var stage : stages) {
            if (stage instanceof ClassifiedStage.TakeStep
                    || stage instanceof ClassifiedStage.DropStep) {
                var ctr = b.createLocal();
                b.beginStoreLocal(ctr);
                  b.emitLoadLongConstant(0L);
                b.endStoreLocal();
                takeDropCounters.add(ctr);
            } else if (stage instanceof ClassifiedStage.DropWhileStep) {
                // Boolean flag: starts TRUE (dropping), flips to FALSE permanently
                var flag = b.createLocal();
                b.beginStoreLocal(flag);
                  b.emitLoadObjectConstant(Boolean.TRUE);
                b.endStoreLocal();
                dropWhileFlags.add(flag);
            }
        }

        // ── While loop ──
        b.beginWhile();

        // Condition: cursor-based lists use !ListIsEmpty(cursor);
        // range/seq use the indexed i < bound;
        // step ranges use (step > 0 ? i < end : end < i).
        if (isList) {
            b.beginDirectNot();
              b.beginListIsEmpty();
                b.emitLoadLocal(cursorSlot);
              b.endListIsEmpty();
            b.endDirectNot();
        } else if (hasStep) {
            // step > 0 ? (i < end) : (end < i)
            b.beginConditional();
              b.beginDirectLessThan();
                b.emitLoadLongConstant(0L); b.emitLoadLocal(stepSlot);
              b.endDirectLessThan();
              b.beginDirectLessThan();
                b.emitLoadLocal(iSlot); b.emitLoadLocal(boundSlot);
              b.endDirectLessThan();
              b.beginDirectLessThan();
                b.emitLoadLocal(boundSlot); b.emitLoadLocal(iSlot);
              b.endDirectLessThan();
            b.endConditional();
        } else {
            b.beginDirectLessThan();
              b.emitLoadLocal(iSlot); b.emitLoadLocal(boundSlot);
            b.endDirectLessThan();
        }

        b.beginBlock();

        // Load element into xSlot.
        b.beginStoreLocal(xSlot);
        if (isRange) {
            b.emitLoadLocal(iSlot);
        } else if (isList) {
            b.beginListHead();
              b.emitLoadLocal(cursorSlot);
            b.endListHead();
        } else {
            b.beginSeqGet();
              b.emitLoadLocal(seqSlot); b.emitLoadLocal(iSlot);
            b.endSeqGet();
        }
        b.endStoreLocal();

        // ── Pipeline stages (shared across all modes) ──
        int filterDepth = 0;
        int takeDropIdx = 0;
        var takeCounterPostIncrement = new java.util.ArrayList<com.oracle.truffle.api.bytecode.BytecodeLocal>();
        for (var stage : stages) {
            switch (stage) {
                case ClassifiedStage.FilterStep fs -> {
                    b.beginIfThen();
                      emitPredicateCheck(b, xSlot, fs.peel(), argCount, localMap);
                      b.beginBlock();
                    filterDepth++;
                }
                case ClassifiedStage.MapInline mi -> {
                    if (mi.selfOp()) {
                        b.beginStoreLocal(xSlot);
                        emitInlineOpTwoLocals(mi.op(), xSlot, xSlot, b);
                        b.endStoreLocal();
                    } else {
                        emitMapperStep(b, xSlot, mi.op(), mi.rhsLit(), mi.rhsNode(),
                            null, false, null, argCount, localMap);
                    }
                }
                case ClassifiedStage.MapCall mc -> {
                    emitMapperStep(b, xSlot, null, 0L, null,
                        mc.callHash(), mc.scalarABI(), mc.fieldName(),
                        argCount, localMap);
                }
                case ClassifiedStage.DropStep ds -> {
                    var ctr = takeDropCounters.get(takeDropIdx++);
                    b.beginStoreLocal(ctr);
                      b.beginDirectAdd();
                        b.emitLoadLocal(ctr); b.emitLoadLongConstant(1L);
                      b.endDirectAdd();
                    b.endStoreLocal();
                    b.beginIfThen();
                      b.beginDirectLessThan();
                        emitNode(ds.n(), b, argCount, localMap); b.emitLoadLocal(ctr);
                      b.endDirectLessThan();
                      b.beginBlock();
                    filterDepth++;
                }
                case ClassifiedStage.TakeStep ts -> {
                    var ctr = takeDropCounters.get(takeDropIdx++);
                    b.beginIfThen();
                      b.beginDirectLessThan();
                        b.emitLoadLocal(ctr); emitNode(ts.n(), b, argCount, localMap);
                      b.endDirectLessThan();
                      b.beginBlock();
                    filterDepth++;
                    takeCounterPostIncrement.add(ctr);
                }
                case ClassifiedStage.TakeWhileStep tw -> {
                    // When predicate fails, exit the entire loop immediately.
                    b.beginIfThen();
                      b.beginDirectNot();
                        emitPredicateCheck(b, xSlot, tw.peel(), argCount, localMap);
                      b.endDirectNot();
                      b.beginBlock();
                        b.emitBranch(exitLabel);
                      b.endBlock();
                    b.endIfThen();
                }
                case ClassifiedStage.DropWhileStep dw -> {
                    // Drop elements while the flag is true AND p(x) holds.
                    // Once p(x) fails (or flag is already false), flip the
                    // flag permanently and process the element.
                    //
                    // Emitted as:
                    //   shouldSkip = false
                    //   if (dropping) { if (p(x)) { shouldSkip = true } else { dropping = false } }
                    //   if (!shouldSkip) { ... process element ... }
                    //
                    // Uses a local shouldSkip instead of Conditional to avoid
                    // BDSL ternary semantics issues with boxed Boolean locals.
                    var flag = dropWhileFlags.remove(0);
                    var shouldSkip = b.createLocal();
                    b.beginStoreLocal(shouldSkip);
                      b.emitLoadObjectConstant(Boolean.FALSE);
                    b.endStoreLocal();
                    b.beginIfThen();
                      b.emitLoadLocal(flag);
                      b.beginBlock();
                        b.beginIfThenElse();
                          emitPredicateCheck(b, xSlot, dw.peel(), argCount, localMap);
                          b.beginBlock();
                            // p(x) true: still dropping, mark skip
                            b.beginStoreLocal(shouldSkip);
                              b.emitLoadObjectConstant(Boolean.TRUE);
                            b.endStoreLocal();
                          b.endBlock();
                          b.beginBlock();
                            // p(x) false: stop dropping
                            b.beginStoreLocal(flag);
                              b.emitLoadObjectConstant(Boolean.FALSE);
                            b.endStoreLocal();
                          b.endBlock();
                        b.endIfThenElse();
                      b.endBlock();
                    b.endIfThen();
                    b.beginIfThen();
                      b.beginDirectNot();
                        b.emitLoadLocal(shouldSkip);
                      b.endDirectNot();
                      b.beginBlock();
                    filterDepth++;
                }
            }
        }

        // ── Sink body (mode-specific) ──
        switch (mode) {
            case FusedSinkMode.Accumulate a -> {
                if (a.ignoreElement()) {
                    // Count: acc++
                    b.beginStoreLocal(accSlot);
                      b.beginDirectAdd();
                        b.emitLoadLocal(accSlot); b.emitLoadLongConstant(1L);
                      b.endDirectAdd();
                    b.endStoreLocal();
                } else {
                    // Fold: acc = acc <op> x
                    b.beginStoreLocal(accSlot);
                      emitInlineOpTwoLocals(a.op(), accSlot, xSlot, b);
                    b.endStoreLocal();
                }
            }
            case FusedSinkMode.CallEach ce -> {
                var argCopy = b.createLocal();
                b.beginStoreLocal(argCopy);
                  b.emitLoadLocal(xSlot);
                b.endStoreLocal();
                var discardSlot = b.createLocal();
                b.beginStoreLocal(discardSlot);
                  emitCallOneArg(b, ce.target(), argCopy, ce.scalarABI(), ce.fieldName());
                b.endStoreLocal();
            }
            case FusedSinkMode.ShortCircuit sc -> {
                // Any:  if (p(x))  → found = true; Branch(exit)
                // All:  if (!p(x)) → found = true; Branch(exit)
                // Find: if (p(x))  → found = true; result = x; Branch(exit)
                // The Branch jumps past the While to exitLabel, eliminating
                // the per-iteration !found check entirely.
                b.beginIfThen();
                if (sc.kind() == FusedSinkMode.ShortCircuitKind.ALL) {
                    b.beginDirectNot();
                      emitPredicateCheck(b, xSlot, sc.predPeel(), argCount, localMap);
                    b.endDirectNot();
                } else {
                    emitPredicateCheck(b, xSlot, sc.predPeel(), argCount, localMap);
                }
                  b.beginBlock();
                    b.beginStoreLocal(foundSlot);
                      b.emitLoadObjectConstant(Boolean.TRUE);
                    b.endStoreLocal();
                    if (sc.kind() == FusedSinkMode.ShortCircuitKind.FIND) {
                        b.beginStoreLocal(resultSlot);
                          b.emitLoadLocal(xSlot);
                        b.endStoreLocal();
                    }
                    // FIND_INDEX: resultSlot already holds the 0-based counter
                    // which IS the correct index — no overwrite needed.
                    b.emitBranch(exitLabel);
                  b.endBlock();
                b.endIfThen();
                // Increment FIND_INDEX counter (only reached when predicate
                // was false — Branch(exit) skips this for the matching element).
                if (sc.kind() == FusedSinkMode.ShortCircuitKind.FIND_INDEX) {
                    b.beginStoreLocal(resultSlot);
                      b.beginDirectAdd();
                        b.emitLoadLocal(resultSlot); b.emitLoadLongConstant(1L);
                      b.endDirectAdd();
                    b.endStoreLocal();
                }
            }
            case FusedSinkMode.ReduceOpt r -> {
                // First element → init; subsequent → acc = acc <op> x
                b.beginIfThenElse();
                  b.beginDirectNot();
                    b.emitLoadLocal(hasAccSlot);
                  b.endDirectNot();
                  b.beginBlock();
                    b.beginStoreLocal(accSlot);
                      b.emitLoadLocal(xSlot);
                    b.endStoreLocal();
                    b.beginStoreLocal(hasAccSlot);
                      b.emitLoadObjectConstant(Boolean.TRUE);
                    b.endStoreLocal();
                  b.endBlock();
                  b.beginBlock();
                    b.beginStoreLocal(accSlot);
                      emitInlineOpTwoLocals(r.op(), accSlot, xSlot, b);
                    b.endStoreLocal();
                  b.endBlock();
                b.endIfThenElse();
            }
            case FusedSinkMode.Materialize ignored -> {
                if (useLongBuilder) {
                    b.beginStoreLocal(builderSlot);
                      b.beginLongSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(xSlot);
                      b.endLongSeqBuilderAdd();
                    b.endStoreLocal();
                } else {
                    var discardSlot2 = b.createLocal();
                    b.beginStoreLocal(discardSlot2);
                      b.beginSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(xSlot);
                      b.endSeqBuilderAdd();
                    b.endStoreLocal();
                }
            }
            case FusedSinkMode.ElementCapture ec -> {
                if (ec.kind() == FusedSinkMode.ElementCaptureKind.FIRST) {
                    // First surviving element: capture + exit loop.
                    b.beginStoreLocal(foundSlot);
                      b.emitLoadObjectConstant(Boolean.TRUE);
                    b.endStoreLocal();
                    b.beginStoreLocal(resultSlot);
                      b.emitLoadLocal(xSlot);
                    b.endStoreLocal();
                    b.emitBranch(exitLabel);
                } else {
                    // Last: keep overwriting. foundSlot = true; resultSlot = x.
                    b.beginStoreLocal(foundSlot);
                      b.emitLoadObjectConstant(Boolean.TRUE);
                    b.endStoreLocal();
                    b.beginStoreLocal(resultSlot);
                      b.emitLoadLocal(xSlot);
                    b.endStoreLocal();
                }
            }
            case FusedSinkMode.MinMaxLong mm -> {
                // First element → init; subsequent → update if better.
                //   if (!found || (isMax ? acc < x : x < acc)) { acc = x; found = true }
                b.beginIfThenElse();
                  b.beginDirectNot();
                    b.emitLoadLocal(foundSlot);
                  b.endDirectNot();
                  // Then: first element — unconditionally store.
                  b.beginBlock();
                    b.beginStoreLocal(accSlot);
                      b.emitLoadLocal(xSlot);
                    b.endStoreLocal();
                    b.beginStoreLocal(foundSlot);
                      b.emitLoadObjectConstant(Boolean.TRUE);
                    b.endStoreLocal();
                  b.endBlock();
                  // Else: compare and conditionally update.
                  b.beginBlock();
                    b.beginIfThen();
                    if (mm.isMax()) {
                        // max: update when acc < x
                        b.beginDirectLessThan();
                          b.emitLoadLocal(accSlot); b.emitLoadLocal(xSlot);
                        b.endDirectLessThan();
                    } else {
                        // min: update when x < acc
                        b.beginDirectLessThan();
                          b.emitLoadLocal(xSlot); b.emitLoadLocal(accSlot);
                        b.endDirectLessThan();
                    }
                      b.beginBlock();
                        b.beginStoreLocal(accSlot);
                          b.emitLoadLocal(xSlot);
                        b.endStoreLocal();
                      b.endBlock();
                    b.endIfThen();
                  b.endBlock();
                b.endIfThenElse();
            }
            case FusedSinkMode.ScanAccum sa -> {
                // acc = acc <op> x; builder.add(acc)
                b.beginStoreLocal(accSlot);
                  emitInlineOpTwoLocals(sa.op(), accSlot, xSlot, b);
                b.endStoreLocal();
                if (useLongBuilder) {
                    b.beginStoreLocal(builderSlot);
                      b.beginLongSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(accSlot);
                      b.endLongSeqBuilderAdd();
                    b.endStoreLocal();
                } else {
                    var discardSlot3 = b.createLocal();
                    b.beginStoreLocal(discardSlot3);
                      b.beginSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(accSlot);
                      b.endSeqBuilderAdd();
                    b.endStoreLocal();
                }
            }
            case FusedSinkMode.MinMaxByKey mmb -> {
                // key = f(x); if (!found || key </> bestKey) { bestKey = key; acc = x; found = true }
                var argCopy = b.createLocal();
                b.beginStoreLocal(argCopy);
                  b.emitLoadLocal(xSlot);
                b.endStoreLocal();
                var keySlot = b.createLocal();
                b.beginStoreLocal(keySlot);
                  emitCallOneArg(b, mmb.keyFn(), argCopy, mmb.scalarABI(), mmb.fieldName());
                b.endStoreLocal();
                b.beginIfThenElse();
                  b.beginDirectNot();
                    b.emitLoadLocal(foundSlot);
                  b.endDirectNot();
                  // Then: first element — unconditionally store.
                  b.beginBlock();
                    b.beginStoreLocal(accSlot);
                      b.emitLoadLocal(xSlot);
                    b.endStoreLocal();
                    b.beginStoreLocal(resultSlot);
                      b.emitLoadLocal(keySlot);
                    b.endStoreLocal();
                    b.beginStoreLocal(foundSlot);
                      b.emitLoadObjectConstant(Boolean.TRUE);
                    b.endStoreLocal();
                  b.endBlock();
                  // Else: compare and conditionally update.
                  b.beginBlock();
                    b.beginIfThen();
                    if (mmb.isMax()) {
                        // max_by: update when bestKey < key
                        b.beginDirectLessThan();
                          b.emitLoadLocal(resultSlot); b.emitLoadLocal(keySlot);
                        b.endDirectLessThan();
                    } else {
                        // min_by: update when key < bestKey
                        b.beginDirectLessThan();
                          b.emitLoadLocal(keySlot); b.emitLoadLocal(resultSlot);
                        b.endDirectLessThan();
                    }
                      b.beginBlock();
                        b.beginStoreLocal(accSlot);
                          b.emitLoadLocal(xSlot);
                        b.endStoreLocal();
                        b.beginStoreLocal(resultSlot);
                          b.emitLoadLocal(keySlot);
                        b.endStoreLocal();
                      b.endBlock();
                    b.endIfThen();
                  b.endBlock();
                b.endIfThenElse();
            }
            case FusedSinkMode.PartitionPred pp -> {
                // if p(x) → passBuilder.add(x) else → failBuilder.add(x)
                b.beginIfThenElse();
                  emitPredicateCheck(b, xSlot, pp.predPeel(), argCount, localMap);
                  // Then: pass
                  b.beginBlock();
                    var discardP = b.createLocal();
                    b.beginStoreLocal(discardP);
                      b.beginSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(xSlot);
                      b.endSeqBuilderAdd();
                    b.endStoreLocal();
                  b.endBlock();
                  // Else: fail
                  b.beginBlock();
                    var discardF = b.createLocal();
                    b.beginStoreLocal(discardF);
                      b.beginSeqBuilderAdd();
                        b.emitLoadLocal(resultSlot);
                        b.emitLoadLocal(xSlot);
                      b.endSeqBuilderAdd();
                    b.endStoreLocal();
                  b.endBlock();
                b.endIfThenElse();
            }
        }

        // ── Close stages (shared) ──
        for (var ctr : takeCounterPostIncrement) {
            b.beginStoreLocal(ctr);
              b.beginDirectAdd();
                b.emitLoadLocal(ctr); b.emitLoadLongConstant(1L);
              b.endDirectAdd();
            b.endStoreLocal();
        }
        for (int j = 0; j < filterDepth; j++) {
              b.endBlock();
            b.endIfThen();
        }
        // Advance: cursor = ListTail(cursor) for lists;
        //          i += step for step ranges; i++ otherwise.
        if (isList) {
            b.beginStoreLocal(cursorSlot);
              b.beginListTail();
                b.emitLoadLocal(cursorSlot);
              b.endListTail();
            b.endStoreLocal();
        } else {
            b.beginStoreLocal(iSlot);
              b.beginDirectAdd();
                b.emitLoadLocal(iSlot);
                if (hasStep) {
                    b.emitLoadLocal(stepSlot);
                } else {
                    b.emitLoadLongConstant(1L);
                }
              b.endDirectAdd();
            b.endStoreLocal();
        }

        b.endBlock();
        b.endWhile();

        // #442 — land here when Branch(exitLabel) fires from a ShortCircuit
        // sink, TakeWhile stage, or First capture.
        if (exitLabel != null) {
            b.emitLabel(exitLabel);
        }

        // ── Post-loop result (mode-specific) ──
        switch (mode) {
            case FusedSinkMode.Accumulate ignored ->
                b.emitLoadLocal(accSlot);
            case FusedSinkMode.CallEach ignored ->
                b.emitLoadObjectConstant(com.mezze.interpreter.values.MezzeRecord.empty());
            case FusedSinkMode.ShortCircuit sc -> {
                switch (sc.kind()) {
                    case ANY -> b.emitLoadLocal(foundSlot);
                    case ALL, NONE -> {
                        // ALL: !found (found means a non-match was seen)
                        // NONE: !found (found means a match was seen → none is false)
                        b.beginDirectNot();
                          b.emitLoadLocal(foundSlot);
                        b.endDirectNot();
                    }
                    case FIND, FIND_INDEX -> {
                        b.beginConditional();
                          b.emitLoadLocal(foundSlot);
                          b.beginMakeVariantSingleFieldPayload(SOME_TAG,
                              com.mezze.language.ast.BuiltinTypes.SOME, "val");
                            b.emitLoadLocal(resultSlot);
                          b.endMakeVariantSingleFieldPayload();
                          b.emitMakeVariantNoPayload(NONE_TAG,
                              com.mezze.language.ast.BuiltinTypes.NONE);
                        b.endConditional();
                    }
                }
            }
            case FusedSinkMode.ReduceOpt ignored -> {
                b.beginConditional();
                  b.emitLoadLocal(hasAccSlot);
                  b.beginMakeVariantSingleFieldPayload(SOME_TAG,
                      com.mezze.language.ast.BuiltinTypes.SOME, "val");
                    b.emitLoadLocal(accSlot);
                  b.endMakeVariantSingleFieldPayload();
                  b.emitMakeVariantNoPayload(NONE_TAG,
                      com.mezze.language.ast.BuiltinTypes.NONE);
                b.endConditional();
            }
            case FusedSinkMode.Materialize m -> {
                if (useLongBuilder && m.target() == FusedSinkMode.MaterializeTarget.LIST) {
                    b.beginLongSeqBuilderToList();
                      b.emitLoadLocal(builderSlot);
                    b.endLongSeqBuilderToList();
                } else if (useLongBuilder) {
                    b.beginLongSeqBuilderToSeq();
                      b.emitLoadLocal(builderSlot);
                    b.endLongSeqBuilderToSeq();
                } else if (m.target() == FusedSinkMode.MaterializeTarget.SEQ) {
                    b.beginSeqBuilderToSeq();
                      b.emitLoadLocal(builderSlot);
                    b.endSeqBuilderToSeq();
                } else {
                    b.beginSeqBuilderToList();
                      b.emitLoadLocal(builderSlot);
                    b.endSeqBuilderToList();
                }
            }
            case FusedSinkMode.ElementCapture ignored -> {
                // First/Last → Option: Some{val=result} if found, else None
                b.beginConditional();
                  b.emitLoadLocal(foundSlot);
                  b.beginMakeVariantSingleFieldPayload(SOME_TAG,
                      com.mezze.language.ast.BuiltinTypes.SOME, "val");
                    b.emitLoadLocal(resultSlot);
                  b.endMakeVariantSingleFieldPayload();
                  b.emitMakeVariantNoPayload(NONE_TAG,
                      com.mezze.language.ast.BuiltinTypes.NONE);
                b.endConditional();
            }
            case FusedSinkMode.MinMaxLong ignored -> {
                // Min/Max → Option: Some{val=acc} if found, else None
                b.beginConditional();
                  b.emitLoadLocal(foundSlot);
                  b.beginMakeVariantSingleFieldPayload(SOME_TAG,
                      com.mezze.language.ast.BuiltinTypes.SOME, "val");
                    b.emitLoadLocal(accSlot);
                  b.endMakeVariantSingleFieldPayload();
                  b.emitMakeVariantNoPayload(NONE_TAG,
                      com.mezze.language.ast.BuiltinTypes.NONE);
                b.endConditional();
            }
            case FusedSinkMode.ScanAccum ignored -> {
                // Scan always produces a Seq of accumulated values.
                if (useLongBuilder) {
                    b.beginLongSeqBuilderToSeq();
                      b.emitLoadLocal(builderSlot);
                    b.endLongSeqBuilderToSeq();
                } else {
                    b.beginSeqBuilderToSeq();
                      b.emitLoadLocal(builderSlot);
                    b.endSeqBuilderToSeq();
                }
            }
            case FusedSinkMode.MinMaxByKey ignored -> {
                // Min_by/Max_by → Option: Some{val=acc} if found, else None
                b.beginConditional();
                  b.emitLoadLocal(foundSlot);
                  b.beginMakeVariantSingleFieldPayload(SOME_TAG,
                      com.mezze.language.ast.BuiltinTypes.SOME, "val");
                    b.emitLoadLocal(accSlot);
                  b.endMakeVariantSingleFieldPayload();
                  b.emitMakeVariantNoPayload(NONE_TAG,
                      com.mezze.language.ast.BuiltinTypes.NONE);
                b.endConditional();
            }
            case FusedSinkMode.PartitionPred ignored -> {
                // Partition → {pass = passSeq, fail = failSeq}
                b.beginMakeRecord(new String[] { "pass", "fail" });
                  b.beginSeqBuilderToSeq();
                    b.emitLoadLocal(builderSlot);
                  b.endSeqBuilderToSeq();
                  b.beginSeqBuilderToSeq();
                    b.emitLoadLocal(resultSlot);
                  b.endSeqBuilderToSeq();
                b.endMakeRecord();
            }
        }

        b.endBlock();
    }

    /**
     * #437 — classify a MakeLambdaIR whose body is
     * {@code (ReadLocal(paramSlot) <op> LongLit)} or the equivalent
     * CallDirect-to-arith-op shape (mono-desugared form). Returns null
     * on captures, wrong arity, or any body pattern we don't recognize.
     */
    private static final class UnaryLitShape {
        final InlineOp op; final long rhsLit;
        /** #515 — when non-null, RHS is emitted via {@link #emitNode} instead
         *  of a raw long literal. Used to plumb captured RHS operands
         *  (e.g. {@code {x} -> x * factor} where {@code factor} is captured).
         *  Set to a {@link ReadLocalIR} pointing at the outer frame slot
         *  the lambda's capture[idx].slot refers to. */
        final NodeIR rhsNode;
        /** True when both operands are the parameter itself (e.g. {@code x * x}).
         *  Emit side uses {@code xSlot <op> xSlot} instead of xSlot <op> rhs. */
        final boolean selfOp;
        /** #442 Day 9 — non-null when the mapper body is a call to a pure
         *  user function (rather than {@code x <op> lit}). When set,
         *  {@code op} is null and {@code rhsLit} is ignored;
         *  {@code callScalarABI} and {@code callFieldName} describe the
         *  original callsite ABI: whether the arg was raw scalar or a
         *  MakeRecord{field=arg} wrapper, and (for record-ABI) which
         *  field name held it. */
        final Hash callHash;
        final boolean callScalarABI;
        final String callFieldName;
        UnaryLitShape(InlineOp op, long rhsLit) {
            this.op = op; this.rhsLit = rhsLit; this.rhsNode = null; this.selfOp = false;
            this.callHash = null; this.callScalarABI = false; this.callFieldName = null;
        }
        UnaryLitShape(InlineOp op, NodeIR rhsNode) {
            this.op = op; this.rhsLit = 0L; this.rhsNode = rhsNode; this.selfOp = false;
            this.callHash = null; this.callScalarABI = false; this.callFieldName = null;
        }
        /** Shape E: param <op> param (e.g. x * x). */
        UnaryLitShape(InlineOp op, boolean selfOp) {
            this.op = op; this.rhsLit = 0L; this.rhsNode = null; this.selfOp = selfOp;
            this.callHash = null; this.callScalarABI = false; this.callFieldName = null;
        }
        UnaryLitShape(Hash callHash, boolean callScalarABI, String callFieldName) {
            this.op = null; this.rhsLit = 0L; this.rhsNode = null; this.selfOp = false;
            this.callHash = callHash;
            this.callScalarABI = callScalarABI;
            this.callFieldName = callFieldName;
        }
    }
    /**
     * #438 — classify a unary predicate lambda for filter fusion.
     * Recognizes both simple {@code {x} -> x <cmpOp> lit} and compound
     * {@code {x} -> (x <preOp> lit1) <cmpOp> lit2}. Compound form covers
     * the common {@code x % 2 == 0} shape. Returns null on anything else.
     * Comparison op restricted to EQ/NEQ/LT/LE/GT/GE (Bool-producing);
     * pre-transform op restricted to arith ADD/SUB/MUL/DIV/MOD.
     */
    private static final class PredShape {
        final InlineOp preOp; final long preRhs; final NodeIR preRhsNode;
        final InlineOp cmpOp; final long cmpRhs; final NodeIR cmpRhsNode;
        /** #442 Day 9 — non-null when predicate body is a call to a pure
         *  Bool-returning user function. Other fields null/0 in that case;
         *  {@code callScalarABI} and {@code callFieldName} describe the
         *  original callsite ABI. */
        final Hash callHash;
        final boolean callScalarABI;
        final String callFieldName;
        PredShape(InlineOp preOp, long preRhs, InlineOp cmpOp, long cmpRhs) {
            this(preOp, preRhs, null, cmpOp, cmpRhs, null);
        }
        /** #515 — extended constructor lifting Node forms for captured RHSs. */
        PredShape(InlineOp preOp, long preRhs, NodeIR preRhsNode,
                  InlineOp cmpOp, long cmpRhs, NodeIR cmpRhsNode) {
            this.preOp = preOp; this.preRhs = preRhs; this.preRhsNode = preRhsNode;
            this.cmpOp = cmpOp; this.cmpRhs = cmpRhs; this.cmpRhsNode = cmpRhsNode;
            this.callHash = null; this.callScalarABI = false; this.callFieldName = null;
        }
        PredShape(Hash callHash, boolean callScalarABI, String callFieldName) {
            this.preOp = null; this.preRhs = 0L; this.preRhsNode = null;
            this.cmpOp = null; this.cmpRhs = 0L; this.cmpRhsNode = null;
            this.callHash = callHash;
            this.callScalarABI = callScalarABI;
            this.callFieldName = callFieldName;
        }
    }
    private PredShape classifyUnaryPredicateLambda(MakeLambdaIR ml, MezzeLanguage language) {
        // #515 — captures OK: RHS resolution below lifts ReadCaptureIR(i)
        // to a ReadLocalIR(ml.captures[i].slot) at emit time. Call-form
        // (Shape D) still refuses captures — a captured user_pred callee
        // isn't safe to inline as an if-condition without further work.
        var caps = ml.getCaptures();
        var fnIR = language.lookupFunctionIR(ml.getFunctionHash());
        if (fnIR == null) return null;
        if (fnIR.getCaptureSlots().size() != caps.size()) return null;
        if (fnIR.getParamSlots().size() != 1) return null;
        if (!fnIR.getLocalSlots().isEmpty()) return null;
        // Zero captures + one param → param slot = 0.
        // N captures + one param → param slot = captureCount (frame layout
        //   is [captures..., params..., locals...]). Adjust below.
        int paramSlot = caps.size();
        NodeIR body = fnIR.getBody();
        // #442 Day 9 — Shape D first: arbitrary pure single-arg call.
        //   {x} -> user_pred { arg = x }   returning Bool
        // Same 2 recognized IR forms as classifyUnaryLiteralLambda's Shape D.
        // Purity gate: callee must be pure (no PerformOpIR transitively).
        // We don't verify the return type is Bool here — the peel is used
        // as an `if` condition, so a non-Bool would crash at emit time in
        // BDSL's IfThen. In practice the typechecker rejected the source
        // before we got here if the predicate wasn't Bool-valued.
        if (caps.isEmpty() && body instanceof CallDirectIR call && call.getTargetHash() != null) {
            if (call.isScalarABI() && call.getArgs().size() == 1
                    && call.getArgs().get(0) instanceof ReadLocalIR llp1
                    && llp1.getSlot() == paramSlot
                    && isPureSingleArgCallee(call.getTargetHash())) {
                return new PredShape(call.getTargetHash(),
                    /*callScalarABI=*/ true, /*callFieldName=*/ null);
            }
            if (!call.isScalarABI() && call.getArgs().size() == 1
                    && call.getArgs().get(0) instanceof MakeRecordIR mrp
                    && mrp.getFields().size() == 1
                    && mrp.getFields().get(0).getValue() instanceof ReadLocalIR llp2
                    && llp2.getSlot() == paramSlot
                    && isPureSingleArgCallee(call.getTargetHash())) {
                return new PredShape(call.getTargetHash(),
                    /*callScalarABI=*/ false,
                    /*callFieldName=*/ mrp.getFields().get(0).getName());
            }
        }
        // Extract outer op: (LHS <cmpOp> LongLit-or-Capture). LHS is either
        // ReadLocal(paramSlot) [simple] or another binary op [compound].
        // The inner matcher uses paramSlot=0; rebuild body with param slot
        // shift so `leftIsParam` picks up the correct slot when captures
        // are present. Simpler: adjust the check below to also accept
        // ReadLocal(paramSlot).
        var outer = matchBinaryOpAgainstConstOrCapture(body, caps, paramSlot);
        if (outer == null) {
            // Shape F: general catch-all for any capture-free single-param
            // predicate lambda whose body couldn't be classified inline.
            // Call the lambda function directly — it returns Bool, usable
            // as an if-condition. Same rationale as classifyUnaryLiteralLambda's
            // Shape F. The canonicalizer's isEffectSafe gate validates the chain.
            if (caps.isEmpty()) {
                if (System.getenv("MEZZE_CHAIN_CANON_EMIT_DEBUG") != null)
                    System.err.println("[pred-classify] CALL fallback for body="
                        + body.getClass().getSimpleName());
                return new PredShape(ml.getFunctionHash(),
                        /*callScalarABI=*/ true, /*callFieldName=*/ null);
            }
            return null;
        }
        // Outer must be a comparison op — predicate produces Bool.
        switch (outer.op) {
            case EQ, NEQ, LT, LE, GT, GE -> {}
            default -> { return null; }
        }
        // Simple form: LHS is ReadLocal(paramSlot).
        if (outer.leftIsParam) {
            return new PredShape(null, 0L, null, outer.op, outer.rhsLit, outer.rhsNode);
        }
        // Compound form: LHS is another binary op with the same shape.
        var inner = matchBinaryOpAgainstConstOrCapture(outer.leftNode, caps, paramSlot);
        if (inner == null || !inner.leftIsParam) return null;
        switch (inner.op) {
            case ADD, SUB, MUL, DIV, MOD -> {}
            default -> { return null; }
        }
        return new PredShape(inner.op, inner.rhsLit, inner.rhsNode,
                             outer.op, outer.rhsLit, outer.rhsNode);
    }

    /**
     * #515 — matcher for {@code (ReadLocal(paramSlot) <op> rhs)} where
     * {@code rhs} is a {@link LongLitIR} or a {@link ReadCaptureIR}.
     * The pre-existing {@link #matchBinaryOpAgainstLongLit} assumed
     * paramSlot=0 for its {@code leftIsParam} flag; with captures, the
     * param sits at slot {@code captures.size()}. This version threads
     * paramSlot and capture-lifting through.
     */
    private BinaryLitMatch matchBinaryOpAgainstConstOrCapture(
            NodeIR n,
            java.util.List<com.mezze.language.truffleir.CaptureIR> caps,
            int paramSlot) {
        // Shape A: InlineBinaryOp(?, rhs).
        if (n instanceof InlineBinaryOpIR bin) {
            NodeIR right = bin.getRight();
            boolean lp = bin.getLeft() instanceof ReadLocalIR l && l.getSlot() == paramSlot;
            if (right instanceof LongLitIR rr) {
                return new BinaryLitMatch(bin.getOp(), rr.getValue(), lp, lp ? null : bin.getLeft());
            }
            NodeIR lifted = liftCaptureRhs(right, caps);
            if (lifted != null) {
                return new BinaryLitMatch(bin.getOp(), lifted, lp, lp ? null : bin.getLeft());
            }
            // #442 Shape A′: RHS is any parameter-free expression (e.g.
            // ReadGlobalByHash(n) - 10). The emitter will evaluate it inline
            // in the fused loop; for side-effect-free expressions on globals/
            // literals this is correct and avoids a full function call.
            if (isParamFree(right, paramSlot)) {
                return new BinaryLitMatch(bin.getOp(), right, lp, lp ? null : bin.getLeft());
            }
        }
        // Shape B: CallDirect (scalar-ABI) to a KnownArithOps target.
        if (n instanceof CallDirectIR call
                && call.isScalarABI()
                && call.getArgs().size() == 2) {
            var op = KnownArithOps.lookup(call.getTargetHash());
            if (op == null) return null;
            NodeIR right = call.getArgs().get(1);
            boolean lp = call.getArgs().get(0) instanceof ReadLocalIR l && l.getSlot() == paramSlot;
            if (right instanceof LongLitIR rr) {
                return new BinaryLitMatch(op, rr.getValue(), lp, lp ? null : call.getArgs().get(0));
            }
            NodeIR lifted = liftCaptureRhs(right, caps);
            if (lifted != null) {
                return new BinaryLitMatch(op, lifted, lp, lp ? null : call.getArgs().get(0));
            }
            // #442 Shape B′: parameter-free RHS expression.
            if (isParamFree(right, paramSlot)) {
                return new BinaryLitMatch(op, right, lp, lp ? null : call.getArgs().get(0));
            }
        }
        // Shape C: CallIndirect(function=ReadField(?, opName), arg=MakeRecord{it, r=LongLit-or-Capture}).
        if (n instanceof CallIndirectIR ind
                && ind.getFunction() instanceof ReadFieldIR rf
                && ind.getArg() instanceof MakeRecordIR argMr
                && argMr.getFields().size() == 2) {
            var op = ABILITY_OP_NAMES.get(rf.getFieldName());
            if (op == null) return null;
            NodeIR rExpr = null;
            for (var fld : argMr.getFields()) {
                if ("r".equals(fld.getName())) rExpr = fld.getValue();
            }
            if (rExpr == null) return null;
            NodeIR lhs = rf.getRecord();
            boolean lp = lhs instanceof ReadLocalIR ll && ll.getSlot() == paramSlot;
            if (rExpr instanceof LongLitIR ll2) {
                return new BinaryLitMatch(op, ll2.getValue(), lp, lp ? null : lhs);
            }
            NodeIR lifted = liftCaptureRhs(rExpr, caps);
            if (lifted != null) {
                return new BinaryLitMatch(op, lifted, lp, lp ? null : lhs);
            }
            // #442 Shape C′: parameter-free expression RHS — e.g.
            // (x > (n - 10)) where RHS is CallIndirect(ReadField(n,__sub__),{it=n,r=10}).
            if (isParamFree(rExpr, paramSlot)) {
                return new BinaryLitMatch(op, rExpr, lp, lp ? null : lhs);
            }
        }
        return null;
    }

    /**
     * Result of matching a binary op against a LongLit right operand.
     * {@link #leftIsParam} true iff LHS is {@code ReadLocal(paramSlot=0)}
     * — in that case {@link #leftNode} is not set (null); otherwise
     * {@link #leftNode} is the LHS NodeIR for further inspection.
     *
     * <p>#515 — RHS may also be a {@link ReadCaptureIR} lifted to a
     * {@code ReadLocalIR} on the outer frame. When {@link #rhsNode} is
     * non-null it wins over {@link #rhsLit}.
     */
    private static final class BinaryLitMatch {
        final InlineOp op; final long rhsLit; final NodeIR rhsNode;
        final boolean leftIsParam; final NodeIR leftNode;
        BinaryLitMatch(InlineOp op, long rhsLit, boolean leftIsParam, NodeIR leftNode) {
            this.op = op; this.rhsLit = rhsLit; this.rhsNode = null;
            this.leftIsParam = leftIsParam; this.leftNode = leftNode;
        }
        BinaryLitMatch(InlineOp op, NodeIR rhsNode, boolean leftIsParam, NodeIR leftNode) {
            this.op = op; this.rhsLit = 0L; this.rhsNode = rhsNode;
            this.leftIsParam = leftIsParam; this.leftNode = leftNode;
        }
    }
    /**
     * #515 — matcher for {@code (lhs <op> rhs)} where {@code rhs} is
     * either a {@link LongLitIR} or a {@link ReadCaptureIR} that we
     * resolve against {@code capturesOnEnclosing} into a
     * {@link ReadLocalIR} on the outer frame. Pass an empty list to
     * refuse capture-based RHS.
     */
    private BinaryLitMatch matchBinaryOpAgainstLongLit(NodeIR n,
                                                       java.util.List<com.mezze.language.truffleir.CaptureIR> capturesOnEnclosing) {
        // Shape A: InlineBinaryOp(?, LongLit) — inline arith on a long RHS.
        if (n instanceof InlineBinaryOpIR bin) {
            NodeIR right = bin.getRight();
            NodeIR liftedRhs = liftCaptureRhs(right, capturesOnEnclosing);
            if (right instanceof LongLitIR rr) {
                boolean lp = bin.getLeft() instanceof ReadLocalIR l && l.getSlot() == 0;
                return new BinaryLitMatch(bin.getOp(), rr.getValue(), lp, lp ? null : bin.getLeft());
            }
            if (liftedRhs != null) {
                boolean lp = bin.getLeft() instanceof ReadLocalIR l && l.getSlot() == 0;
                return new BinaryLitMatch(bin.getOp(), liftedRhs, lp, lp ? null : bin.getLeft());
            }
        }
        // Shape B: CallDirect (scalar-ABI) to a KnownArithOps target with 2 args.
        if (n instanceof CallDirectIR call
                && call.isScalarABI()
                && call.getArgs().size() == 2) {
            var op = KnownArithOps.lookup(call.getTargetHash());
            if (op == null) return null;
            NodeIR right = call.getArgs().get(1);
            if (right instanceof LongLitIR rr) {
                boolean lp = call.getArgs().get(0) instanceof ReadLocalIR l && l.getSlot() == 0;
                return new BinaryLitMatch(op, rr.getValue(), lp, lp ? null : call.getArgs().get(0));
            }
            NodeIR liftedRhs = liftCaptureRhs(right, capturesOnEnclosing);
            if (liftedRhs != null) {
                boolean lp = call.getArgs().get(0) instanceof ReadLocalIR l && l.getSlot() == 0;
                return new BinaryLitMatch(op, liftedRhs, lp, lp ? null : call.getArgs().get(0));
            }
        }
        // Shape C: CallIndirect(function=ReadField(?, opName), arg=MakeRecord{it, r=LongLit}).
        if (n instanceof CallIndirectIR ind
                && ind.getFunction() instanceof ReadFieldIR rf
                && ind.getArg() instanceof MakeRecordIR argMr
                && argMr.getFields().size() == 2) {
            var op = ABILITY_OP_NAMES.get(rf.getFieldName());
            if (op == null) return null;
            LongLitIR rLit = null;
            for (var fld : argMr.getFields()) {
                if ("r".equals(fld.getName()) && fld.getValue() instanceof LongLitIR ll2) rLit = ll2;
            }
            if (rLit == null) return null;
            NodeIR lhs = rf.getRecord();
            boolean lp = lhs instanceof ReadLocalIR ll && ll.getSlot() == 0;
            return new BinaryLitMatch(op, rLit.getValue(), lp, lp ? null : lhs);
        }
        return null;
    }
    /** Convenience: refuse captures. */
    private BinaryLitMatch matchBinaryOpAgainstLongLit(NodeIR n) {
        return matchBinaryOpAgainstLongLit(n, java.util.Collections.emptyList());
    }

    /**
     * #515 — if {@code rhs} is {@link ReadCaptureIR(idx)}, resolve
     * capture[idx] against the enclosing lambda's declared captures and
     * return a {@link ReadLocalIR} that reads from the outer frame slot.
     * Returns null if not a capture, or index is out of range.
     */
    private NodeIR liftCaptureRhs(NodeIR rhs,
                                  java.util.List<com.mezze.language.truffleir.CaptureIR> capturesOnEnclosing) {
        if (!(rhs instanceof ReadCaptureIR rc)) return null;
        if (capturesOnEnclosing == null) return null;
        int idx = rc.getIndex();
        if (idx < 0 || idx >= capturesOnEnclosing.size()) return null;
        var cap = capturesOnEnclosing.get(idx);
        // capturesOnEnclosing[i].slot is a frame slot in the ENCLOSING
        // function's `[captures..., params..., locals...]` layout. A
        // plain ReadLocalIR(slot) at emit time thus loads whichever
        // arg/local held the captured value at the MakeLambda site.
        return new ReadLocalIR(cap.getSlot(), cap.getKind(), rc.getSrc(), null);
    }

    /**
     * #442 — returns true iff the expression tree rooted at {@code node}
     * does NOT reference {@link ReadLocalIR} at {@code paramSlot}. Used to
     * determine whether a comparison RHS can be emitted inline in the
     * fused loop body (evaluated each iteration but independent of the
     * loop variable). Conservatively returns false for IR shapes with
     * children we don't walk (e.g. LetIR, MatchIR, BlockIR) — those are
     * too complex to inline safely.
     */
    private static boolean isParamFree(NodeIR node, int paramSlot) {
        if (node instanceof ReadLocalIR rl) return rl.getSlot() != paramSlot;
        if (node instanceof LongLitIR || node instanceof DoubleLitIR
                || node instanceof BoolLitIR || node instanceof StringLitIR
                || node instanceof CharLitIR || node instanceof FracLitIR
                || node instanceof UnitLitIR) return true;
        if (node instanceof ReadGlobalByHashIR || node instanceof ReadGlobalFnByHashIR)
            return true;
        if (node instanceof ReadCaptureIR) return true;
        if (node instanceof InlineBinaryOpIR bin)
            return isParamFree(bin.getLeft(), paramSlot)
                && isParamFree(bin.getRight(), paramSlot);
        if (node instanceof CallDirectIR call) {
            for (var arg : call.getArgs())
                if (!isParamFree(arg, paramSlot)) return false;
            return true;
        }
        // Conservative: anything else might hide a param reference.
        return false;
    }

    private UnaryLitShape classifyUnaryLiteralLambda(MakeLambdaIR ml,
                                                     MezzeLanguage language) {
        // #515 — accept captures for Shape A/B/C only. Call-form (Shape D)
        // still refuses captures — a captured user_fn callee isn't safe
        // to inline as a mapper without further work.
        var caps = ml.getCaptures();
        var fnIR = language.lookupFunctionIR(ml.getFunctionHash());
        if (fnIR == null) return null;
        if (fnIR.getCaptureSlots().size() != caps.size()) return null;
        if (fnIR.getParamSlots().size() != 1) return null;
        if (!fnIR.getLocalSlots().isEmpty()) return null;
        // Frame layout: [captures..., params..., locals...] so the single
        // param sits at slot equal to captures.size().
        int paramSlot = caps.size();
        NodeIR body = fnIR.getBody();
        // Shape A: InlineBinaryOp(ReadLocal(paramSlot), rhs) where rhs is
        // LongLit OR ReadCapture(i).
        if (body instanceof InlineBinaryOpIR bin
                && bin.getLeft() instanceof ReadLocalIR l
                && l.getSlot() == paramSlot) {
            NodeIR right = bin.getRight();
            if (right instanceof LongLitIR r) {
                return new UnaryLitShape(bin.getOp(), r.getValue());
            }
            // Shape E: param <op> param — e.g. {x} -> x * x (squaring).
            if (right instanceof ReadLocalIR rr && rr.getSlot() == paramSlot) {
                return new UnaryLitShape(bin.getOp(), /*selfOp=*/ true);
            }
            NodeIR lifted = liftCaptureRhs(right, caps);
            if (lifted != null) return new UnaryLitShape(bin.getOp(), lifted);
            // #442 Shape A′: parameter-free expression RHS.
            if (isParamFree(right, paramSlot)) return new UnaryLitShape(bin.getOp(), right);
        }
        // Shape B: CallDirect (scalar-ABI) to a KnownArithOps target with
        // (ReadLocal(paramSlot), LongLit|ReadCapture) — mono-desugared arith.
        if (body instanceof CallDirectIR call
                && call.isScalarABI()
                && call.getArgs().size() == 2
                && call.getArgs().get(0) instanceof ReadLocalIR ll
                && ll.getSlot() == paramSlot) {
            var op = KnownArithOps.lookup(call.getTargetHash());
            if (op == null) return null;
            NodeIR right = call.getArgs().get(1);
            if (right instanceof LongLitIR rr) {
                return new UnaryLitShape(op, rr.getValue());
            }
            // Shape B-self: param <op> param — e.g. {x} -> x * x via mono-desugared CallDirect.
            // Mirrors Shape E above for InlineBinaryOp.
            if (right instanceof ReadLocalIR rr2 && rr2.getSlot() == paramSlot) {
                return new UnaryLitShape(op, /*selfOp=*/ true);
            }
            NodeIR lifted = liftCaptureRhs(right, caps);
            if (lifted != null) return new UnaryLitShape(op, lifted);
            // #442 Shape B′: parameter-free expression RHS.
            if (isParamFree(right, paramSlot)) return new UnaryLitShape(op, right);
        }
        // Shape C: mono-not-fired — CallIndirectIR(function=ReadField(receiver,opName),
        //          arg=MakeRecord{it=receiver, r=LongLit|ReadCapture}). Same as
        //          classifyFolderBody's Shape C.
        if (body instanceof CallIndirectIR ind
                && ind.getFunction() instanceof ReadFieldIR rf
                && rf.getRecord() instanceof ReadLocalIR recvRead
                && recvRead.getSlot() == paramSlot
                && ind.getArg() instanceof MakeRecordIR argMr
                && argMr.getFields().size() == 2) {
            var op = ABILITY_OP_NAMES.get(rf.getFieldName());
            if (op == null) return null;
            NodeIR rExpr = null;
            for (var fld : argMr.getFields()) {
                if ("r".equals(fld.getName())) rExpr = fld.getValue();
            }
            if (rExpr == null) return null;
            if (rExpr instanceof LongLitIR ll2) return new UnaryLitShape(op, ll2.getValue());
            // Shape C-self: r=param — e.g. {x} -> x * x via un-monomorphized ability dispatch.
            // The record is {it=x, r=x} where both refer to the single param.
            if (rExpr instanceof ReadLocalIR rr && rr.getSlot() == paramSlot) {
                return new UnaryLitShape(op, /*selfOp=*/ true);
            }
            NodeIR lifted = liftCaptureRhs(rExpr, caps);
            if (lifted != null) return new UnaryLitShape(op, lifted);
        }
        // #442 Day 9 — Shape D: arbitrary pure single-arg call. Refuse
        // when captures are present — the callee inlining path doesn't
        // know how to thread outer captures through.
        if (caps.isEmpty() && body instanceof CallDirectIR call) {
            if (call.getTargetHash() == null) return null;
            if (call.isScalarABI() && call.getArgs().size() == 1
                    && call.getArgs().get(0) instanceof ReadLocalIR ll3
                    && ll3.getSlot() == paramSlot) {
                if (isPureSingleArgCallee(call.getTargetHash())) {
                    return new UnaryLitShape(call.getTargetHash(),
                        /*callScalarABI=*/ true, /*callFieldName=*/ null);
                }
                return null;
            }
            if (!call.isScalarABI() && call.getArgs().size() == 1
                    && call.getArgs().get(0) instanceof MakeRecordIR mr
                    && mr.getFields().size() == 1
                    && mr.getFields().get(0).getValue() instanceof ReadLocalIR ll4
                    && ll4.getSlot() == paramSlot) {
                if (isPureSingleArgCallee(call.getTargetHash())) {
                    return new UnaryLitShape(call.getTargetHash(),
                        /*callScalarABI=*/ false,
                        /*callFieldName=*/ mr.getFields().get(0).getName());
                }
                return null;
            }
        }
        // Shape F: general catch-all for any capture-free single-param lambda
        // whose body the shapes above couldn't classify (e.g. PerformOpIR-based
        // effect calls). Instead of classifying the body's op/rhs, emit a call
        // to the lambda's own compiled function. The lambda's BDSL handles
        // effects, PerformOp, etc. — we just call it with the element value.
        // The canonicalizer's isEffectSafe gate already validated the chain.
        if (caps.isEmpty()) {
            return new UnaryLitShape(ml.getFunctionHash(),
                    /*callScalarABI=*/ true, /*callFieldName=*/ null);
        }
        return null;
    }

    /**
     * #442 Day 9 — purity gate for fusion-inline call peel. Returns true iff
     * we can safely inline a call to this target inside a range-fused loop:
     * the callee must exist, take exactly one parameter, have no captures,
     * and its body must not (transitively) yield a MezzeSuspend — i.e.
     * contain no {@link PerformOpIR}. Uses {@link #canYieldSuspended}.
     *
     * <p><b>Locals are now permitted</b> (fix landed with the fresh-argCopy
     * emit in {@link #emitMapperStep} and {@link #emitPredicateCheck}).
     * The earlier restriction existed because a naive
     * {@code beginStoreLocal(xSlot); call(xSlot); endStoreLocal} shape
     * aliased the induction slot as both call-arg-source AND result-target
     * across a callee that could re-lay-out its own locals; for callees
     * with local slots this corrupted the arg-load and produced wrong
     * results (e.g. `{x} -> let a = x*2 in ... -x` returning 4950 instead
     * of 1338150 on the standard bench). Staging the arg through a fresh
     * {@code argCopy} local decouples the two roles and makes the peel
     * correct for arbitrary user-fn callees with local bindings.
     *
     * <p>Returns false on any unknown/uncompiled target, or any target
     * with captures/effects.
     *
     * <p>Kill-switch: {@code MEZZE_FUSE_CALL_PEEL=0} disables the peel
     * entirely (falls through to the pre-Day 9 path — user_fn callsites
     * materialize an intermediate seq). Default is on.
     */
    private boolean isPureSingleArgCallee(Hash targetHash) {
        if ("0".equals(System.getenv("MEZZE_FUSE_CALL_PEEL"))) return false;
        if (targetHash == null) return false;
        var fn = language.lookupFunctionIR(targetHash);
        if (fn == null) return false;
        if (fn.getParamSlots().size() != 1) return false;
        if (!fn.getCaptureSlots().isEmpty()) return false;
        if (canYieldSuspended(fn.getBody())) return false;
        return true;
    }

    /**
     * Mirror of {@code TruffleCompiler.isMutMutateWithActLambda}: true iff
     * the perform-op is {@code Mut.mutate { act = X }}. In that shape the
     * wrapper record can be elided — see the emit for {@code PerformOpIR}
     * and {@link com.mezze.interpreter.nodes.effect.OpCallNode#createMutMutateActFast}.
     */
    private static boolean isMutMutateWithActLambda(PerformOpIR node) {
        if (!"mutate".equals(node.getOperationName())) return false;
        if (!com.mezze.interpreter.nodes.effect.MarkerEffects.isMutEffect(node.getEffectHash())) return false;
        if (!(node.getArg() instanceof com.mezze.language.truffleir.MakeRecordIR mr)) return false;
        if (mr.getFields().size() != 1) return false;
        return "act".equals(mr.getFields().get(0).getName());
    }

    /**
     * True iff the perform-op is {@code Mut.loop { cond = X, body = Y }}.
     * When this shape is detected, the compiler emits {@code PerformMutLoop}
     * with cond/body as separate operands — at runtime the loop runs compiled
     * with DirectCallNode.forceInlining instead of the interpreted
     * {@code @TruffleBoundary __native_mutation_loop}.
     */
    /**
     * Path D8 endgame — lower a {@link HandleIR} entirely inline in BDSL
     * with zero heap alloc per Handle. See project_d8_handleir_design.md.
     *
     * <p>Shape per handler (nested outermost-first so first handler in list
     * is outermost, matching {@code TruffleCompiler.kt:638}):
     *
     * <pre>
     * Block {
     *   local resultSlot
     *   TryCatchOtherwise(otherwiseGen = { PopHandlerChain }):
     *     try:  Block {
     *             PushHandlerChain(hash, handler)   // handler on stack
     *             StoreLocal(resultSlot, body)
     *           }
     *     catch: Block {
     *              StoreLocal(resultSlot, UnwrapAbortValue(LoadException))
     *              PopHandlerChain
     *            }
     *   LoadLocal(resultSlot)                       // the value
     * }
     * </pre>
     *
     * <p>Both normal and abort exits pop the chain exactly once. Non-abort
     * exceptions rethrow from UnwrapAbortValue without popping the chain —
     * matches AST behavior; the chain is truncated by higher-level frame
     * cleanup on function exit.
     *
     * <p>Nested handlers (perform A in perform B in body) compose by
     * wrapping in reverse: emit the innermost B first as {@code body},
     * then A wraps that.
     */
    private void emitHandle(HandleIR h, MezzeBytecodeRootNodeGen.Builder b,
                            int argCount, BytecodeLocal[] localMap) {
        emitHandleNested(h.getHandlers(), 0, h.getBody(), b, argCount, localMap);
    }

    private void emitHandleNested(java.util.List<HandlerBindIR> handlers, int idx,
                                  NodeIR body,
                                  MezzeBytecodeRootNodeGen.Builder b,
                                  int argCount, BytecodeLocal[] localMap) {
        if (idx >= handlers.size()) {
            emitNode(body, b, argCount, localMap);
            return;
        }
        HandlerBindIR hb = handlers.get(idx);
        boolean isOutermost = idx == 0;
        boolean isScoped = hb.isScoped();
        b.beginBlock();
        BytecodeLocal resultSlot = b.createLocal();
        b.beginTryCatchOtherwise(() -> {
            // otherwise (normal exit): pop the chain
            if (isScoped) {
                b.emitPopHandlerChainScoped();
            } else {
                b.emitPopHandlerChainSimple();
            }
        });
        // === try branch ===
        b.beginBlock();
        // Push handler on chain — scoped vs simple
        if (isScoped) {
            b.beginPushHandlerChainScoped(hb.getEffectHash());
        } else {
            b.beginPushHandlerChainSimple(hb.getEffectHash());
        }
        if (hb.getParams() != null) {
            b.beginLookupParameterizedHandlerByHash(hb.getHandlerHash());
            emitNode(hb.getParams(), b, argCount, localMap);
            b.endLookupParameterizedHandlerByHash();
        } else {
            b.emitLookupHandlerByHash(hb.getHandlerHash());
        }
        if (isScoped) {
            b.endPushHandlerChainScoped();
        } else {
            b.endPushHandlerChainSimple();
        }
        // Body (recursing for next handler / final body). Path E (#336):
        // at the outermost Handle wrap body's result in AutoForceIfSuspended
        // so a lazy MezzeSuspend flowing out of body — e.g. from a
        // top-level `let x = effectful{}` — is forced under this Handle's
        // installed chain before the Pop. supports() rejects partial-
        // discharging Handles, so every BDSL Handle here IS fully-
        // discharging and safe to force unconditionally.
        b.beginStoreLocal(resultSlot);
        if (isOutermost) {
            b.beginAutoForceIfSuspended();
            emitHandleNested(handlers, idx + 1, body, b, argCount, localMap);
            b.endAutoForceIfSuspended();
        } else {
            emitHandleNested(handlers, idx + 1, body, b, argCount, localMap);
        }
        b.endStoreLocal();
        b.endBlock();
        // === catch branch ===
        //
        // Popping the chain (which invokes finalize_scope) is unconditional
        // on any exit from the Handle body. UnwrapAbortValue rethrows
        // anything that isn't an AbortPerformException, so if we ordered
        // PopHandlerChain *after* Unwrap, a body that throws (e.g.
        // MezzeGuestException from division-by-zero) would escape without
        // finalize_scope ever running — leaking scoped resources like
        // Parallel's per-scope ForkJoinPool. Pop first, then let Unwrap
        // decide whether to yield the abort value or rethrow.
        b.beginBlock();
        if (isScoped) {
            b.emitPopHandlerChainScoped();
        } else {
            b.emitPopHandlerChainSimple();
        }
        b.beginStoreLocal(resultSlot);
        b.beginUnwrapAbortValue();
        b.emitLoadException();
        b.endUnwrapAbortValue();
        b.endStoreLocal();
        b.endBlock();
        b.endTryCatchOtherwise();
        b.emitLoadLocal(resultSlot);
        b.endBlock();
    }

    /**
     * Lower a {@link MatchIR} — value-producing expression — to BDSL.
     *
     * <p>MatchIR is an expression: the case body's value is the value of
     * the whole match. Encoding in BDSL: wrap the scrutinee-store and
     * the IfThenElse chain in a Block; a Block's final child is its
     * yielded value. The store is a void-returning side effect,
     * followed by the chained IfThenElse whose then/else branches each
     * yield the case-body value.
     */
    /**
     * Detect and emit the fused {@code match get { seq, i } is Some { val } -> expr, None -> default}
     * pattern.  Returns true if the fusion was applied.
     *
     * <p>Fused form: {@code MutGetRaw(seq, i)} returns raw value or null,
     * then {@code IfThenElse(IsNotNull(raw), some_body, none_body)}.
     * Eliminates: MezzeVariant allocation, VariantTagEquals, ReadVariantField.
     */
    private boolean tryEmitFusedMutGetMatch(MatchIR m, MezzeBytecodeRootNodeGen.Builder b,
                                             int argCount, BytecodeLocal[] localMap) {
        // Check: scrutinee is a MUT_GET call
        NodeIR scrutinee = m.getScrutinee();
        if (!(scrutinee instanceof CallDirectIR c)) return false;
        if (!c.isScalarABI() || c.isTailCall()) return false;
        var mutOp = KnownMutOps.lookupOrResolve(c.getTargetHash(), language);
        if (mutOp == null || mutOp.kind() != MezzeBytecodeRootNode.MUT_GET) return false;
        if (c.getArgs().size() != 2) return false;

        // Check: exactly 2 cases, no guards, no if-let bindings
        var cases = m.getCases();
        if (cases.size() != 2) return false;
        CaseIR someCase = null, noneCase = null;
        for (CaseIR cs : cases) {
            if (cs.getGuard() != null) return false;
            if (!cs.getIfLetBindings().isEmpty()) return false;
            var pat = cs.getPattern();
            if (pat instanceof PatternIR.Variant vp) {
                if ("Some".equals(vp.getTagName()) && vp.getFieldNames().size() == 1) {
                    someCase = cs;
                } else if ("None".equals(vp.getTagName()) && vp.getFieldNames().isEmpty()) {
                    noneCase = cs;
                }
            } else if (pat instanceof PatternIR.Wildcard) {
                noneCase = cs;
            }
        }
        if (someCase == null || noneCase == null) return false;

        // Extract the "val" binding slot from the Some pattern
        PatternIR.Variant somePat = (PatternIR.Variant) someCase.getPattern();
        int valSlot = somePat.getFieldSlots().get(0);

        // Emit fused get + match using the split MutInBounds + MutReadLong
        // pattern.  For MutableLongSeq, MutReadLong returns unboxed long —
        // zero boxing in the entire get+compare path.
        //
        // Structure:
        //   Block {
        //     seqLocal = seq_expr
        //     idxLocal = i_expr
        //     Conditional(
        //       MutInBounds(seqLocal, idxLocal),
        //       Block { val = MutReadLong(seqLocal, idxLocal); someBody },
        //       noneBody
        //     )
        //   }
        b.beginBlock();

        // Evaluate seq and index once, store for reuse
        BytecodeLocal seqLocal = b.createLocal();
        BytecodeLocal idxLocal = b.createLocal();
        b.beginStoreLocal(seqLocal);
        emitNode(c.getArgs().get(0), b, argCount, localMap);
        b.endStoreLocal();
        b.beginStoreLocal(idxLocal);
        emitNode(c.getArgs().get(1), b, argCount, localMap);
        b.endStoreLocal();

        // Zero-init binding slots (same as generic emitMatch)
        java.util.Set<Integer> bindingSlots = new java.util.HashSet<>();
        collectBindingSlots(cases, bindingSlots);
        for (Integer slot : bindingSlots) {
            if (slot != null && slot >= argCount && slot - argCount < localMap.length) {
                b.beginStoreLocal(localMap[slot - argCount]);
                b.emitLoadNullConstant();
                b.endStoreLocal();
            }
        }

        // Conditional: MutInBounds check
        b.beginConditional();
        b.beginMutInBounds();
        b.emitLoadLocal(seqLocal);
        b.emitLoadLocal(idxLocal);
        b.endMutInBounds();
        // Then (Some branch): unchecked read + body
        b.beginBlock();
        // Write the val binding — MutReadLong returns long for MutableLongSeq
        if (valSlot >= argCount && valSlot - argCount < localMap.length) {
            b.beginStoreLocal(localMap[valSlot - argCount]);
            b.beginMutReadLong();
            b.emitLoadLocal(seqLocal);
            b.emitLoadLocal(idxLocal);
            b.endMutReadLong();
            b.endStoreLocal();
        }
        // Emit any case-level bindings for the Some case
        for (var binding : someCase.getBindings()) {
            if (binding.getSlot() >= argCount && binding.getSlot() - argCount < localMap.length) {
                b.beginStoreLocal(localMap[binding.getSlot() - argCount]);
                b.beginMutReadLong();
                b.emitLoadLocal(seqLocal);
                b.emitLoadLocal(idxLocal);
                b.endMutReadLong();
                b.endStoreLocal();
            }
        }
        emitNode(someCase.getBody(), b, argCount, localMap);
        b.endBlock();
        // Else (None branch)
        emitNode(noneCase.getBody(), b, argCount, localMap);
        b.endConditional();
        b.endBlock();
        return true;
    }

    private void emitMatch(MatchIR m, MezzeBytecodeRootNodeGen.Builder b,
                           int argCount, BytecodeLocal[] localMap) {
        // Try fused MutGetRaw + match pattern first
        if (tryEmitFusedMutGetMatch(m, b, argCount, localMap)) return;

        b.beginBlock();
        BytecodeLocal scrutineeLocal = b.createLocal();
        b.beginStoreLocal(scrutineeLocal);
        emitNode(m.getScrutinee(), b, argCount, localMap);
        b.endStoreLocal();
        // Zero-init every binding slot that any case in this match writes.
        // Match arms only write bindings on the matching branch, but a
        // ReadLocal after the match (or in a subsequent stmt) may run on
        // any branch. AST's frame contract is "unset local reads as null";
        // BDSL's LoadLocal throws FrameSlotTypeException. Without this,
        // if-let with record pattern breaks: the else arm never writes the
        // record-field slots, and a later reference to one Illegal-reads.
        java.util.Set<Integer> bindingSlots = new java.util.HashSet<>();
        collectBindingSlots(m.getCases(), bindingSlots);
        for (Integer slot : bindingSlots) {
            if (slot != null && slot >= argCount && slot - argCount < localMap.length) {
                b.beginStoreLocal(localMap[slot - argCount]);
                b.emitLoadNullConstant();
                b.endStoreLocal();
            }
        }
        emitMatchChain(m.getCases(), 0, scrutineeLocal, b, argCount, localMap);
        b.endBlock();
    }

    /**
     * Collect every slot any case in a match might write, INCLUDING
     * pattern bindings, case-level `bindings`, if-let guard bindings,
     * and (recursively) bindings introduced by nested MatchIRs anywhere
     * in each case's body/guard.
     *
     * Why the body recursion is required: nested record patterns like
     * `if let User {name, address: Address {city, zip}} = alice` desugar
     * (in the typechecker's ExprControlFlowConstraints) into an OUTER
     * MatchIR that binds `name`/`address`, and an INNER MatchIR inside
     * the outer's case body that binds `city`/`zip`. The outer match's
     * `else` arm skips the inner match, leaving `city`/`zip` slots
     * Illegal — a later ReadLocal on those slots crashes with
     * FrameSlotTypeException.
     */
    private static void collectBindingSlots(java.util.List<CaseIR> cases,
                                            java.util.Set<Integer> out) {
        for (CaseIR c : cases) {
            collectPatternBindingSlots(c.getPattern(), out);
            for (var b : c.getBindings()) {
                if (b.getSlot() >= 0) out.add(b.getSlot());
            }
            for (var ilb : c.getIfLetBindings()) {
                for (int s : ilb.getFieldSlots()) if (s >= 0) out.add(s);
                // The nested if-let guard also runs an inner pattern.
                collectPatternBindingSlots(ilb.getPattern(), out);
                collectNestedMatchBindings(ilb.getExpr(), out);
            }
            if (c.getGuard() != null) {
                collectNestedMatchBindings(c.getGuard(), out);
            }
            collectNestedMatchBindings(c.getBody(), out);
        }
    }

    private static void collectPatternBindingSlots(PatternIR pat, java.util.Set<Integer> out) {
        if (pat == null) return;
        if (pat instanceof PatternIR.Wildcard w) {
            if (w.getSlot() >= 0) out.add(w.getSlot());
        } else if (pat instanceof PatternIR.Record rp) {
            for (int s : rp.getFieldSlots()) if (s >= 0) out.add(s);
        } else if (pat instanceof PatternIR.Variant vp) {
            for (int s : vp.getFieldSlots()) if (s >= 0) out.add(s);
        } else if (pat instanceof PatternIR.Seq sp) {
            for (int s : sp.getElementSlots()) if (s >= 0) out.add(s);
            if (sp.getRestSlot() >= 0) out.add(sp.getRestSlot());
        } else if (pat instanceof PatternIR.StringInterp si) {
            for (int s : si.getCaptureSlots()) if (s >= 0) out.add(s);
        }
        // Literal has no bindings.
    }

    /** Walk a NodeIR subtree; when we find a nested MatchIR, recurse into its cases. */
    private static void collectNestedMatchBindings(NodeIR node, java.util.Set<Integer> out) {
        if (node == null) return;
        if (node instanceof MatchIR m) {
            collectBindingSlots(m.getCases(), out);
            collectNestedMatchBindings(m.getScrutinee(), out);
            return;
        }
        if (node instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                if (s instanceof StmtIR.Let l) collectNestedMatchBindings(l.getValue(), out);
                else if (s instanceof StmtIR.Expr e) collectNestedMatchBindings(e.getNode(), out);
            }
            collectNestedMatchBindings(blk.getResult(), out);
            return;
        }
        if (node instanceof IfIR i) {
            collectNestedMatchBindings(i.getCond(), out);
            collectNestedMatchBindings(i.getThenBr(), out);
            collectNestedMatchBindings(i.getElseBr(), out);
            return;
        }
        if (node instanceof WriteLocalIR w) {
            collectNestedMatchBindings(w.getValue(), out);
            return;
        }
        // Every other NodeIR shape that can transitively contain a Match
        // — CallDirect/CallIndirect/CallLocal/CallNative/DictMethodCall/
        // InlineBinaryOp/RecordUpdate/MakeRecord/MakeSeq/InterpolatedString/
        // PerformOp/HandleIR — the typechecker does not currently emit
        // MatchIR as a subexpression of these in Mezze (matches are only
        // in tail-of-block, if-branch, or let-value position). The three
        // walkers above cover the reachable positions.
    }

    private void emitMatchChain(java.util.List<CaseIR> cases, int idx,
                                BytecodeLocal scrutineeLocal,
                                MezzeBytecodeRootNodeGen.Builder b,
                                int argCount, BytecodeLocal[] localMap) {
        // Trailing fall-through: emit NonExhaustiveMatch when the caller
        // ran off the end of a variant-typed match. supports() only
        // accepts trailing Wildcard/Record/Variant/Literal/Seq — the
        // wildcard/record shape never reaches this branch (its emit
        // recurses without calling here); the refutable shapes do.
        if (idx >= cases.size()) {
            b.emitNonExhaustiveMatch();
            return;
        }
        CaseIR c = cases.get(idx);
        PatternIR pat = c.getPattern();
        if (pat instanceof PatternIR.Wildcard w) {
            // Wildcard: bind scrutinee to the wildcard slot if requested,
            // then delegate to emitCaseGuardsAndBody so guard + iflet
            // (with fallthrough to cases[idx+1] on failure) work
            // identically to the Variant path. Without a guard/iflet
            // the pattern is truly unconditional and emitCase falls
            // straight through to the body — same shape as before.
            b.beginBlock();
            if (w.getSlot() >= 0) {
                BytecodeLocal target = localMap[w.getSlot() - argCount];
                b.beginStoreLocal(target);
                b.emitLoadLocal(scrutineeLocal);
                b.endStoreLocal();
            }
            emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
            b.endBlock();
            return;
        }
        if (pat instanceof PatternIR.Record rp) {
            // Record: extract captured fields, then delegate to
            // emitCaseGuardsAndBody like Wildcard above. Typechecker
            // guarantees shape compatibility so the field extraction
            // is unconditional; guard + iflet still need the
            // conditional / fallthrough wrap.
            b.beginBlock();
            for (int i = 0; i < rp.getFieldNames().size(); i++) {
                int slot = rp.getFieldSlots().get(i);
                if (slot < 0) continue;  // wildcard field (`_`) — no binding
                BytecodeLocal target = localMap[slot - argCount];
                b.beginStoreLocal(target);
                b.beginReadFieldByName(rp.getFieldNames().get(i));
                b.emitLoadLocal(scrutineeLocal);
                b.endReadFieldByName();
                b.endStoreLocal();
            }
            emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
            b.endBlock();
            return;
        }
        if (pat instanceof PatternIR.Seq sp) {
            // Seq pattern: length check (== N if no rest, >= N with rest),
            // then bind each element into elementSlots[i], plus the drop-N
            // tail into restSlot if restSlot >= 0.
            int elemCount = sp.getElementSlots().size();
            // hasRest = open pattern (length >= N); bindRest = the tail is captured
            // into a slot. `..._` is open (hasRest) but binds nothing (restSlot < 0).
            boolean hasRest = sp.getHasRest();
            boolean bindRest = sp.getRestSlot() >= 0;
            b.beginConditional();
            if (hasRest) {
                b.beginSeqSizeAtLeast(elemCount);
            } else {
                b.beginSeqSizeEquals(elemCount);
            }
            b.emitLoadLocal(scrutineeLocal);
            if (hasRest) {
                b.endSeqSizeAtLeast();
            } else {
                b.endSeqSizeEquals();
            }
            b.beginBlock();
            for (int i = 0; i < elemCount; i++) {
                int slot = sp.getElementSlots().get(i);
                if (slot < 0) continue;  // wildcard element (`_`) — no binding
                BytecodeLocal target = localMap[slot - argCount];
                b.beginStoreLocal(target);
                b.beginSeqGetAt(i);
                b.emitLoadLocal(scrutineeLocal);
                b.endSeqGetAt();
                b.endStoreLocal();
            }
            if (bindRest) {
                BytecodeLocal target = localMap[sp.getRestSlot() - argCount];
                b.beginStoreLocal(target);
                b.beginSeqDrop(elemCount);
                b.emitLoadLocal(scrutineeLocal);
                b.endSeqDrop();
                b.endStoreLocal();
            }
            emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
            b.endBlock();
            emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
            b.endConditional();
            return;
        }
        if (pat instanceof PatternIR.StringInterp sip) {
            // #360 — MatchStringInterp op returns String[] captures or
            // null. Store into a temp local; if non-null, write each
            // capture into its target slot then run guards+body; else
            // fall through to the next case.
            //
            // Layout (mirrors PatternIR.StringInterp): partTypes[i] is
            // 0 for literal / 1 for capture; literals[i] is the string
            // for literals; captureSlots[i] is the target slot (or -1
            // for literal parts).
            java.util.List<Integer> pt = sip.getPartTypes();
            int[] partTypes = new int[pt.size()];
            for (int i = 0; i < pt.size(); i++) partTypes[i] = pt.get(i);
            java.util.List<String> lits = sip.getLiterals();
            String[] literals = lits.toArray(new String[0]);
            BytecodeLocal capturesLocal = b.createLocal();
            b.beginBlock();
            b.beginStoreLocal(capturesLocal);
            b.beginMatchStringInterp(partTypes, literals);
            b.emitLoadLocal(scrutineeLocal);
            b.endMatchStringInterp();
            b.endStoreLocal();
            b.beginConditional();
            b.beginPrimNotNull();
            b.emitLoadLocal(capturesLocal);
            b.endPrimNotNull();
            b.beginBlock();
            // Write each capture to its target slot. partTypes+captureSlots
            // are parallel to parts; capture-type parts have a real slot,
            // literal parts have -1 (skipped).
            int captureIdx = 0;
            java.util.List<Integer> capSlots = sip.getCaptureSlots();
            for (int i = 0; i < partTypes.length; i++) {
                if (partTypes[i] != 1) continue; // literal
                int targetSlot = capSlots.get(i);
                if (targetSlot < 0) { captureIdx++; continue; }
                BytecodeLocal target = localMap[targetSlot - argCount];
                b.beginStoreLocal(target);
                b.beginArrayGetString(captureIdx);
                b.emitLoadLocal(capturesLocal);
                b.endArrayGetString();
                b.endStoreLocal();
                captureIdx++;
            }
            emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
            b.endBlock();
            emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
            b.endConditional();
            b.endBlock();
            return;
        }
        if (pat instanceof PatternIR.Literal lit) {
            // Conditional(PrimEquals(scrutinee, literalExpr), guarded-body-with-iflets, else...)
            b.beginConditional();
            b.beginPrimEquals();
            b.emitLoadLocal(scrutineeLocal);
            emitNode(lit.getValue(), b, argCount, localMap);
            b.endPrimEquals();
            emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
            emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
            b.endConditional();
            return;
        }
        // Count consecutive variant cases starting at idx.
        int variantEnd = idx;
        while (variantEnd < cases.size()
                && cases.get(variantEnd).getPattern() instanceof PatternIR.Variant) {
            variantEnd++;
        }
        int variantCount = variantEnd - idx;

        if (variantCount >= 2) {
            // Optimized path: read the tag ONCE into a local, then dispatch
            // via PrimEquals(tagLocal, tagConstant). This gives Graal PE
            // the canonical pattern for IntegerSwitchNode folding — one
            // value compared against dense int constants. Eliminates N
            // repeated VariantTagEquals tag reads from the scrutinee.
            BytecodeLocal tagLocal = b.createLocal();
            b.beginBlock();
            b.beginStoreLocal(tagLocal);
            b.beginReadVariantTag();
            b.emitLoadLocal(scrutineeLocal);
            b.endReadVariantTag();
            b.endStoreLocal();
            emitVariantSwitchArm(cases, idx, variantEnd, scrutineeLocal,
                    tagLocal, b, argCount, localMap);
            b.endBlock();
            return;
        }

        // Single variant case — use existing VariantTagEquals path.
        PatternIR.Variant v = (PatternIR.Variant) pat;
        b.beginConditional();
        b.beginVariantTagEquals(v.getTag(), v.getTagName());
        b.emitLoadLocal(scrutineeLocal);
        b.endVariantTagEquals();
        emitVariantArmBody(v, c, cases, idx, scrutineeLocal, b, argCount, localMap);
        emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
        b.endConditional();
    }

    /**
     * Emit one arm of the optimized variant switch dispatch. Compares the
     * pre-read tag local against each arm's tag constant via PrimEquals.
     */
    private void emitVariantSwitchArm(java.util.List<CaseIR> cases, int idx,
                                       int variantEnd,
                                       BytecodeLocal scrutineeLocal,
                                       BytecodeLocal tagLocal,
                                       MezzeBytecodeRootNodeGen.Builder b,
                                       int argCount, BytecodeLocal[] localMap) {
        if (idx >= variantEnd) {
            // Past the variant run — hand off to remaining cases (wildcard, etc.)
            emitMatchChain(cases, idx, scrutineeLocal, b, argCount, localMap);
            return;
        }
        CaseIR c = cases.get(idx);
        PatternIR.Variant v = (PatternIR.Variant) c.getPattern();

        b.beginConditional();
        // Condition: PrimEquals(tagLocal, tagConstant)
        b.beginPrimEquals();
        b.emitLoadLocal(tagLocal);
        b.emitLoadConstant((long) v.getTag());
        b.endPrimEquals();
        // Then-branch: bindings + body
        emitVariantArmBody(v, c, cases, idx, scrutineeLocal, b, argCount, localMap);
        // Else-branch: next arm
        emitVariantSwitchArm(cases, idx + 1, variantEnd, scrutineeLocal,
                tagLocal, b, argCount, localMap);
        b.endConditional();
    }

    /**
     * Emit the bindings + guarded body for a single variant match arm.
     * Shared between the optimized switch path and the single-arm path.
     */
    private void emitVariantArmBody(PatternIR.Variant v, CaseIR c,
                                     java.util.List<CaseIR> cases, int idx,
                                     BytecodeLocal scrutineeLocal,
                                     MezzeBytecodeRootNodeGen.Builder b,
                                     int argCount, BytecodeLocal[] localMap) {
        b.beginBlock();
        var names = v.getFieldNames();
        var slots = v.getFieldSlots();
        if (!names.isEmpty()) {
            if (names.size() == 1) {
                // Single-field variant: use ReadVariantField to read
                // directly from the variant, skipping UnwrapSinglePayload
                // + ReadFieldByName.  Handles both raw-value payloads
                // (from creation-side fusion) and MezzeRecord payloads.
                int slot = slots.get(0);
                if (slot >= 0) {
                    BytecodeLocal target = localMap[slot - argCount];
                    b.beginStoreLocal(target);
                    b.beginReadVariantField(names.get(0));
                    b.emitLoadLocal(scrutineeLocal);
                    b.endReadVariantField();
                    b.endStoreLocal();
                }
            } else {
                // Multi-field variant: unwrap the MezzeRecord payload
                // then read each field by name.
                BytecodeLocal payloadLocal = b.createLocal();
                b.beginStoreLocal(payloadLocal);
                b.beginUnwrapSinglePayload();
                b.emitLoadLocal(scrutineeLocal);
                b.endUnwrapSinglePayload();
                b.endStoreLocal();
                for (int i = 0; i < names.size(); i++) {
                    int slot = slots.get(i);
                    if (slot < 0) continue;  // wildcard field (`_`)
                    BytecodeLocal target = localMap[slot - argCount];
                    b.beginStoreLocal(target);
                    b.beginReadFieldByName(names.get(i));
                    b.emitLoadLocal(payloadLocal);
                    b.endReadFieldByName();
                    b.endStoreLocal();
                }
            }
        }
        emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
        b.endBlock();
    }

    /**
     * Emit a case's optional guard + iflet chain + body. Falls through
     * to {@code emitMatchChain(cases, idx+1, ...)} whenever the guard
     * or any variant-iflet doesn't match. Record and wildcard iflets
     * always match — they just bind and continue.
     */
    private void emitCaseGuardsAndBody(CaseIR c, java.util.List<CaseIR> cases, int idx,
                                       BytecodeLocal scrutineeLocal,
                                       MezzeBytecodeRootNodeGen.Builder b,
                                       int argCount, BytecodeLocal[] localMap) {
        // Ordering rule: if-let bindings MUST populate their slots
        // BEFORE the guard runs. Guards frequently reference iflet-bound
        // slots (e.g. `match p is "..." if let Some {val = {name}} = f {..}
        // when name.starts_with{...}`), and the typechecker sometimes
        // desugars the whole `Some {val = {name}}` such that a nested
        // ReadField(ReadLocal(val_slot), "name") appears in the GUARD
        // block as a synthesized let-binding. Running the guard first
        // reads the still-Illegal iflet slot and crashes with "Expected
        // record for field 'name', got: null" on the fall-through arm.
        //
        // Fix: always emit the if-let chain first. The chain's leaf
        // (emitIfLetChain when iletIdx >= size) emits the body; we
        // interpose the guard there when one is present, wrapping the
        // body in Conditional(guard, body, fall-through-to-next-case).
        emitIfLetChain(c, 0, cases, idx, scrutineeLocal, b, argCount, localMap);
    }

    /**
     * Recursively emit if-let bindings then the case body. Variant
     * iflets act like nested Conditionals — mismatch falls through to
     * {@code emitMatchChain(cases, idx+1, ...)}. Record/wildcard
     * iflets bind fields into their target slots and drop through.
     */
    private void emitIfLetChain(CaseIR c, int iletIdx, java.util.List<CaseIR> cases, int caseIdx,
                                BytecodeLocal scrutineeLocal,
                                MezzeBytecodeRootNodeGen.Builder b,
                                int argCount, BytecodeLocal[] localMap) {
        if (iletIdx >= c.getIfLetBindings().size()) {
            // All if-let bindings have populated their slots. If the
            // case has a guard, it may reference any of those slots
            // (via a synthesized nested Let(slot = ReadField(iflet_slot,
            // "..."))). Run guard NOW and fall through to next case on
            // false. If no guard, emit the body directly.
            if (c.getGuard() != null) {
                b.beginConditional();
                emitNode(c.getGuard(), b, argCount, localMap);
                emitNode(c.getBody(), b, argCount, localMap);
                emitMatchChain(cases, caseIdx + 1, scrutineeLocal, b, argCount, localMap);
                b.endConditional();
            } else {
                emitNode(c.getBody(), b, argCount, localMap);
            }
            return;
        }
        var ilb = c.getIfLetBindings().get(iletIdx);
        PatternIR ilpat = ilb.getPattern();
        if (ilpat instanceof PatternIR.Variant iv) {
            // Store the iflet expression into a fresh local so we can
            // both test its tag and later unwrap the payload.
            BytecodeLocal tmp = b.createLocal();
            b.beginBlock();
            b.beginStoreLocal(tmp);
            emitNode(ilb.getExpr(), b, argCount, localMap);
            b.endStoreLocal();
            b.beginConditional();
            b.beginVariantTagEquals(iv.getTag(), iv.getTagName());
            b.emitLoadLocal(tmp);
            b.endVariantTagEquals();
            // Then: bind captured fields and continue.
            b.beginBlock();
            var names = iv.getFieldNames();
            var slots = iv.getFieldSlots();
            if (!names.isEmpty()) {
                if (names.size() == 1) {
                    // Single-field: ReadVariantField from variant directly.
                    int slot = slots.get(0);
                    if (slot >= 0) {
                        BytecodeLocal target = localMap[slot - argCount];
                        b.beginStoreLocal(target);
                        b.beginReadVariantField(names.get(0));
                        b.emitLoadLocal(tmp);
                        b.endReadVariantField();
                        b.endStoreLocal();
                    }
                } else {
                    BytecodeLocal payloadLocal = b.createLocal();
                    b.beginStoreLocal(payloadLocal);
                    b.beginUnwrapSinglePayload();
                    b.emitLoadLocal(tmp);
                    b.endUnwrapSinglePayload();
                    b.endStoreLocal();
                    for (int i = 0; i < names.size(); i++) {
                        int slot = slots.get(i);
                        if (slot < 0) continue;
                        BytecodeLocal target = localMap[slot - argCount];
                        b.beginStoreLocal(target);
                        b.beginReadFieldByName(names.get(i));
                        b.emitLoadLocal(payloadLocal);
                        b.endReadFieldByName();
                        b.endStoreLocal();
                    }
                }
            }
            emitIfLetChain(c, iletIdx + 1, cases, caseIdx, scrutineeLocal, b, argCount, localMap);
            b.endBlock();
            // Else: iflet mismatch — fall through to the next case.
            emitMatchChain(cases, caseIdx + 1, scrutineeLocal, b, argCount, localMap);
            b.endConditional();
            b.endBlock();
            return;
        }
        if (ilpat instanceof PatternIR.Record rp) {
            // Unconditional match — evaluate expr, then extract each
            // captured field into its bound slot, continue.
            b.beginBlock();
            BytecodeLocal tmp = b.createLocal();
            b.beginStoreLocal(tmp);
            emitNode(ilb.getExpr(), b, argCount, localMap);
            b.endStoreLocal();
            for (int i = 0; i < rp.getFieldNames().size(); i++) {
                BytecodeLocal target = localMap[rp.getFieldSlots().get(i) - argCount];
                b.beginStoreLocal(target);
                b.beginReadFieldByName(rp.getFieldNames().get(i));
                b.emitLoadLocal(tmp);
                b.endReadFieldByName();
                b.endStoreLocal();
            }
            emitIfLetChain(c, iletIdx + 1, cases, caseIdx, scrutineeLocal, b, argCount, localMap);
            b.endBlock();
            return;
        }
        // Wildcard iflet: bind expr to fieldSlots[0] if present.
        b.beginBlock();
        int wcSlot = ilb.getFieldSlots().isEmpty() ? -1 : ilb.getFieldSlots().get(0);
        if (wcSlot >= 0) {
            BytecodeLocal target = localMap[wcSlot - argCount];
            b.beginStoreLocal(target);
            emitNode(ilb.getExpr(), b, argCount, localMap);
            b.endStoreLocal();
        } else {
            // No binding — evaluate for side effects, drop.
            emitNode(ilb.getExpr(), b, argCount, localMap);
        }
        emitIfLetChain(c, iletIdx + 1, cases, caseIdx, scrutineeLocal, b, argCount, localMap);
        b.endBlock();
    }

    // ── Fused long tail-loop ────────────────────────────────

    /**
     * Attempts to emit a {@code FusedLongTailLoop} BDSL operation for a
     * 2-parameter self-tail-loop where:
     * <ul>
     *   <li>The condition is a comparison of one param with a constant</li>
     *   <li>Each param update is param OP constant (or param OP otherParam)</li>
     *   <li>The base case returns one of the params</li>
     * </ul>
     * Returns true if emission succeeded; the caller should skip the
     * direct-while fallback in that case.
     */
    private boolean tryEmitFusedLongTailLoop(
            IfIR ifBody, FunctionIR ir, int paramCount, int captureCount,
            MezzeBytecodeRootNodeGen.Builder b, int argCount,
            BytecodeLocal[] localMap) {
        NodeIR cond = ifBody.getCond();
        NodeIR thenBr = ifBody.getThenBr();
        NodeIR elseBr = ifBody.getElseBr();

        // Identify tail-call branch and base-case branch.
        // invertCond: the FusedLongTailLoop loops while condition is FALSE
        // (condition triggers the base case). When the tail call is in
        // the then-branch, we must negate the condition.
        CallDirectIR tailCall = null;
        NodeIR baseExpr = null;
        boolean invertCond = false;

        if (elseBr instanceof CallDirectIR ec && ec.isTailCall()
                && ec.getTargetHash().equals(ir.getHash())
                && ec.getArgs().size() == paramCount) {
            tailCall = ec; baseExpr = thenBr;
        } else if (thenBr instanceof CallDirectIR tc && tc.isTailCall()
                && tc.getTargetHash().equals(ir.getHash())
                && tc.getArgs().size() == paramCount) {
            tailCall = tc; baseExpr = elseBr;
            invertCond = true;
        }
        if (tailCall == null) return false;

        // Helper: resolve IR node to param index (0-based), or -1.
        // Params appear as ReadLocalIR(slot < argCount, slot >= captureCount)
        // or ReadArgIR(index >= captureCount).
        int ac = captureCount + paramCount; // argCount
        java.util.function.ToIntFunction<NodeIR> paramIdx = n -> {
            if (n instanceof ReadLocalIR rl && rl.getSlot() < ac && rl.getSlot() >= captureCount)
                return rl.getSlot() - captureCount;
            if (n instanceof ReadArgIR ra && ra.getIndex() >= captureCount && ra.getIndex() < ac)
                return ra.getIndex() - captureCount;
            return -1;
        };

        // Base case must read one of the params.
        int resultIdx = paramIdx.applyAsInt(baseExpr);
        if (resultIdx < 0) return false;

        // Condition must be a comparison (param CMP constant) or
        // (constant CMP param). Accepted IR shapes: InlineBinaryOpIR,
        // CallNativeIR (scalar-ABI 2-arg), or CallIndirectIR wrapping
        // ReadField(<op>, receiver) + MakeRecord{r = ...} (VarType path).
        InlineOp condOp = fltlExtractOp(cond);
        if (condOp == null) return false;
        int condCmpOp = fltlClassifyCmp(condOp);
        if (condCmpOp < 0) return false;
        NodeIR condLeft = fltlExtractChild(cond, 0);
        NodeIR condRight = fltlExtractChild(cond, 1);

        boolean condOnP0;
        long condRhsVal;
        int leftPI = paramIdx.applyAsInt(condLeft);
        int rightPI = paramIdx.applyAsInt(condRight);
        if (leftPI >= 0 && condRight instanceof LongLitIR clit) {
            condOnP0 = (leftPI == 0);
            condRhsVal = clit.getValue();
        } else if (rightPI >= 0 && condLeft instanceof LongLitIR clit) {
            condOnP0 = (rightPI == 0);
            condCmpOp = fltlSwapCmp(condCmpOp);
            condRhsVal = clit.getValue();
        } else {
            return false;
        }

        // If tail call was in the then-branch, negate the condition.
        if (invertCond) condCmpOp = fltlNegateCmp(condCmpOp);

        // Classify each arg update.
        int[] updateOp = new int[2];
        long[] updateRhs = new long[2];
        boolean[] rhsIsOther = new boolean[2];

        for (int i = 0; i < paramCount; i++) {
            NodeIR arg = tailCall.getArgs().get(i);
            int pi = paramIdx.applyAsInt(arg);
            if (pi == i) { updateOp[i] = -1; continue; } // identity
            if (pi >= 0) return false; // reads OTHER param directly — too complex

            // Extract (op, leftNode, rightNode) from InlineBinaryOpIR,
            // CallNativeIR (scalar-ABI 2-arg), or CallIndirectIR
            // (VarType ability-dispatch: ReadField + MakeRecord).
            InlineOp extractedOp = fltlExtractOp(arg);
            if (extractedOp == null) return false;
            int op = fltlClassifyArithOp(extractedOp);
            if (op < 0) return false;
            NodeIR leftNode = fltlExtractChild(arg, 0);
            NodeIR rightNode = fltlExtractChild(arg, 1);

            // Left must be THIS param
            int lpi = paramIdx.applyAsInt(leftNode);
            if (lpi != i) return false;

            // Right: LongLitIR or OTHER param
            if (rightNode instanceof LongLitIR rlit) {
                updateOp[i] = op;
                updateRhs[i] = rlit.getValue();
                rhsIsOther[i] = false;
            } else {
                int rpi = paramIdx.applyAsInt(rightNode);
                if (rpi < 0 || rpi == i) return false;
                updateOp[i] = op;
                updateRhs[i] = 0;
                rhsIsOther[i] = true;
            }
        }

        // All checks passed — emit FusedLongTailLoop.
        int config = MezzeBytecodeRootNode.fltlPack(
                condCmpOp, condOnP0,
                updateOp[0], updateOp[1],
                resultIdx,
                rhsIsOther[0], rhsIsOther[1]);

        b.beginFusedLongTailLoop(config);
        // p0Init
        b.emitLoadArgument(captureCount);
        // p1Init
        b.emitLoadArgument(captureCount + 1);
        // condRhs
        b.emitLoadLongConstant(condRhsVal);
        // p0UpdateRhs (0 if identity or other-param)
        b.emitLoadLongConstant(updateRhs[0]);
        // p1UpdateRhs
        b.emitLoadLongConstant(updateRhs[1]);
        b.endFusedLongTailLoop();

        return true;
    }

    /**
     * Try to emit a FusedLongTailLoop1P for a 1-param tail-recursive function.
     * Handles patterns like:
     * <pre>
     *   countdown = { n } -> if n == 0 then 0 else countdown { n = n - 1 }
     *   count_up  = { n } -> if n >= 100 then n else count_up { n = n + 1 }
     * </pre>
     * Base case can return the param (resultIsParam=true) or a long literal.
     */
    private boolean tryEmitFusedLongTailLoop1P(
            IfIR ifBody, FunctionIR ir, int captureCount,
            MezzeBytecodeRootNodeGen.Builder b, int argCount,
            BytecodeLocal[] localMap) {
        NodeIR cond = ifBody.getCond();
        NodeIR thenBr = ifBody.getThenBr();
        NodeIR elseBr = ifBody.getElseBr();

        // Identify tail-call branch and base-case branch.
        CallDirectIR tailCall = null;
        NodeIR baseExpr = null;
        boolean invertCond = false;

        if (elseBr instanceof CallDirectIR ec && ec.isTailCall()
                && ec.getTargetHash().equals(ir.getHash())
                && ec.getArgs().size() == 1) {
            tailCall = ec; baseExpr = thenBr;
        } else if (thenBr instanceof CallDirectIR tc && tc.isTailCall()
                && tc.getTargetHash().equals(ir.getHash())
                && tc.getArgs().size() == 1) {
            tailCall = tc; baseExpr = elseBr;
            invertCond = true;
        }
        if (tailCall == null) return false;

        // Param resolver: single param at slot captureCount.
        int paramSlot = captureCount;
        java.util.function.ToIntFunction<NodeIR> paramIdx = n -> {
            if (n instanceof ReadLocalIR rl && rl.getSlot() == paramSlot) return 0;
            if (n instanceof ReadArgIR ra && ra.getIndex() == paramSlot) return 0;
            return -1;
        };

        // Base case: param read OR long literal.
        boolean resultIsParam;
        long baseLitVal = 0;
        if (paramIdx.applyAsInt(baseExpr) == 0) {
            resultIsParam = true;
        } else if (baseExpr instanceof LongLitIR lit) {
            resultIsParam = false;
            baseLitVal = lit.getValue();
        } else {
            return false;
        }

        // Condition: param CMP literal or literal CMP param.
        InlineOp condOp = fltlExtractOp(cond);
        if (condOp == null) return false;
        int condCmpOp = fltlClassifyCmp(condOp);
        if (condCmpOp < 0) return false;
        NodeIR condLeft = fltlExtractChild(cond, 0);
        NodeIR condRight = fltlExtractChild(cond, 1);

        long condRhsVal;
        if (paramIdx.applyAsInt(condLeft) == 0 && condRight instanceof LongLitIR clit) {
            condRhsVal = clit.getValue();
        } else if (paramIdx.applyAsInt(condRight) == 0 && condLeft instanceof LongLitIR clit) {
            condCmpOp = fltlSwapCmp(condCmpOp);
            condRhsVal = clit.getValue();
        } else {
            return false;
        }

        if (invertCond) condCmpOp = fltlNegateCmp(condCmpOp);

        // Classify update expression: param op literal.
        NodeIR updateArg = tailCall.getArgs().get(0);
        int pi = paramIdx.applyAsInt(updateArg);
        int p0Op;
        long p0Rhs;
        if (pi == 0) {
            // Identity update (no change to param — infinite loop guard above
            // via condition, but structurally valid).
            p0Op = -1;
            p0Rhs = 0;
        } else {
            InlineOp extractedOp = fltlExtractOp(updateArg);
            if (extractedOp == null) return false;
            p0Op = fltlClassifyArithOp(extractedOp);
            if (p0Op < 0) return false;
            NodeIR leftNode = fltlExtractChild(updateArg, 0);
            NodeIR rightNode = fltlExtractChild(updateArg, 1);

            // Left must be the param.
            if (paramIdx.applyAsInt(leftNode) != 0) return false;

            // Right must be a literal.
            if (rightNode instanceof LongLitIR rlit) {
                p0Rhs = rlit.getValue();
            } else {
                return false;
            }
        }

        // All checks passed — emit FusedLongTailLoop1P.
        int config = MezzeBytecodeRootNode.fltl1pPack(condCmpOp, p0Op, resultIsParam);

        b.beginFusedLongTailLoop1P(config);
        // p0Init
        b.emitLoadArgument(captureCount);
        // condRhs
        b.emitLoadLongConstant(condRhsVal);
        // p0UpdateRhs
        b.emitLoadLongConstant(p0Rhs);
        // baseResult
        b.emitLoadLongConstant(baseLitVal);
        b.endFusedLongTailLoop1P();

        return true;
    }

    private static int fltlClassifyCmp(InlineOp op) {
        return switch (op) {
            case EQ  -> 5;
            case NEQ -> 6;
            case LT  -> 7;
            case LE  -> 8;
            case GT  -> 9;
            case GE  -> 10;
            default  -> -1;
        };
    }

    private static int fltlSwapCmp(int cmpOp) {
        // Swap sides: a < b ↔ b > a
        return switch (cmpOp) {
            case 7 -> 9;   // LT ↔ GT
            case 8 -> 10;  // LE ↔ GE
            case 9 -> 7;   // GT ↔ LT
            case 10 -> 8;  // GE ↔ LE
            default -> cmpOp; // EQ, NEQ are symmetric
        };
    }

    private static int fltlNegateCmp(int cmpOp) {
        // Negate: !(a == b) → a != b, !(a < b) → a >= b, etc.
        return switch (cmpOp) {
            case 5 -> 6;   // EQ → NEQ
            case 6 -> 5;   // NEQ → EQ
            case 7 -> 10;  // LT → GE
            case 8 -> 9;   // LE → GT
            case 9 -> 8;   // GT → LE
            case 10 -> 7;  // GE → LT
            default -> cmpOp;
        };
    }

    private static int fltlClassifyArithOp(InlineOp op) {
        return switch (op) {
            case ADD -> 0;
            case SUB -> 1;
            case MUL -> 2;
            case DIV -> 3;
            case MOD -> 4;
            default  -> -1;
        };
    }

    /** Classify a CallNativeIR name via its NativeSignature's inlineOp. */
    private static int fltlClassifyNativeArithOp(String name) {
        var sig = NativeSignatures.INSTANCE.signatureOf(name);
        if (sig == null || sig.getInlineOp() == null) return -1;
        return fltlClassifyArithOp(sig.getInlineOp());
    }

    /**
     * Unified extraction of the {@link InlineOp} from any binary-op IR shape:
     * <ol>
     *   <li>{@code InlineBinaryOpIR} — direct inline op</li>
     *   <li>{@code CallNativeIR} (scalar-ABI, 2-arg) — via NativeSignatures</li>
     *   <li>{@code CallIndirectIR(ReadField(receiver, opName), MakeRecord{r=...})}
     *       — VarType ability-dispatch path (the receiver IS the left operand;
     *       "r" field IS the right operand). Uses {@link #inlineOpFor} to map
     *       the field name to an InlineOp.</li>
     * </ol>
     * Returns the InlineOp, or null if the node doesn't match any shape.
     */
    private InlineOp fltlExtractOp(NodeIR node) {
        if (node instanceof InlineBinaryOpIR bin) {
            return bin.getOp();
        }
        if (node instanceof CallNativeIR cnir
                && cnir.isScalarABI() && cnir.getArgs().size() == 2) {
            var sig = NativeSignatures.INSTANCE.signatureOf(cnir.getName());
            return (sig != null) ? sig.getInlineOp() : null;
        }
        if (node instanceof CallIndirectIR ci
                && ci.getFunction() instanceof ReadFieldIR rf
                && ci.getArg() instanceof MakeRecordIR) {
            return inlineOpFor(rf.getFieldName());
        }
        return null;
    }

    /**
     * Retrieve the left (index=0) or right (index=1) child from a
     * binary-op IR node matched by {@link #fltlExtractBinOp}.
     */
    private static NodeIR fltlExtractChild(NodeIR node, int childIdx) {
        if (node instanceof InlineBinaryOpIR bin) {
            return childIdx == 0 ? bin.getLeft() : bin.getRight();
        }
        if (node instanceof CallNativeIR cnir && cnir.isScalarABI()) {
            return cnir.getArgs().get(childIdx);
        }
        if (node instanceof CallIndirectIR ci
                && ci.getFunction() instanceof ReadFieldIR rf) {
            if (childIdx == 0) return rf.getRecord(); // receiver = left operand
            // Right operand is the "r" field of the MakeRecord arg
            if (ci.getArg() instanceof MakeRecordIR mr) {
                for (FieldIR f : mr.getFields()) {
                    if ("r".equals(f.getName())) return f.getValue();
                }
            }
            return null;
        }
        return null;
    }

    // ── Self-tail-loop helpers ────────────────────────────────

    /**
     * Returns true if {@code node} is a call-free expression — safe to
     * use as the base-case result in the direct-while self-tail-loop
     * optimisation without risking a stale {@code selfTailContinueFlag}.
     */
    private static boolean stlIsSimpleExpr(NodeIR node) {
        if (node instanceof ReadLocalIR || node instanceof ReadArgIR
                || node instanceof ReadCaptureIR
                || node instanceof ReadGlobalByHashIR
                || node instanceof ReadGlobalFnByHashIR
                || node instanceof LongLitIR || node instanceof DoubleLitIR
                || node instanceof BoolLitIR || node instanceof StringLitIR
                || node instanceof UnitLitIR || node instanceof CharLitIR
                || node instanceof FracLitIR) {
            return true;
        }
        if (node instanceof InlineBinaryOpIR bin) {
            InlineOp op = bin.getOp();
            if (op == InlineOp.NEG || op == InlineOp.NOT) {
                return stlIsSimpleExpr(bin.getLeft());
            }
            return stlIsSimpleExpr(bin.getLeft())
                    && stlIsSimpleExpr(bin.getRight());
        }
        if (node instanceof ReadFieldIR rf) {
            return stlIsSimpleExpr(rf.getRecord());
        }
        if (node instanceof MakeRecordIR rec) {
            for (FieldIR f : rec.getFields()) {
                if (!stlIsSimpleExpr(f.getValue())) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Emits the logical negation of {@code cond}. For comparison ops
     * the inverse is emitted directly (LE→GT, etc.); otherwise wraps
     * in {@code DirectNot}.
     */
    private void stlEmitInvertedCond(NodeIR cond,
                                     MezzeBytecodeRootNodeGen.Builder b,
                                     int argCount,
                                     BytecodeLocal[] localMap) {
        if (cond instanceof InlineBinaryOpIR bin) {
            InlineOp inv = switch (bin.getOp()) {
                case LE -> InlineOp.GT;
                case LT -> InlineOp.GE;
                case GE -> InlineOp.LT;
                case GT -> InlineOp.LE;
                case EQ -> InlineOp.NEQ;
                case NEQ -> InlineOp.EQ;
                default -> null;
            };
            if (inv != null) {
                emitNode(new InlineBinaryOpIR(inv, bin.getLeft(),
                        bin.getRight(), bin.getSrc()), b, argCount, localMap);
                return;
            }
        }
        // Fallback: wrap in DirectNot.
        b.beginDirectNot();
        emitNode(cond, b, argCount, localMap);
        b.endDirectNot();
    }

    /**
     * Emits param updates for a self-tail-call.  Identity args (an arg
     * that just reads the same param back) are elided entirely.  When
     * multiple params change, evaluation goes through temp locals to
     * preserve order-of-evaluation.
     */
    private void stlEmitParamUpdate(java.util.List<NodeIR> args,
                                    BytecodeLocal[] stlParams,
                                    MezzeBytecodeRootNodeGen.Builder b,
                                    int argCount,
                                    BytecodeLocal[] localMap) {
        int n = args.size();

        // Identify identity args.
        boolean[] identity = new boolean[n];
        int changeCount = 0;
        for (int i = 0; i < n; i++) {
            identity[i] = stlIsIdentityArg(args.get(i), i);
            if (!identity[i]) changeCount++;
        }

        if (changeCount == 0) {
            // All identity — degenerate infinite loop, just emit a
            // dummy so the While body has a value.
            b.emitLoadLongConstant(0L);
            return;
        }

        b.beginBlock();
        if (changeCount == 1) {
            // Single change — no order-of-evaluation risk.
            for (int i = 0; i < n; i++) {
                if (!identity[i]) {
                    b.beginStoreLocal(stlParams[i]);
                    emitNode(args.get(i), b, argCount, localMap);
                    b.endStoreLocal();
                }
            }
        } else {
            // Multiple changes — evaluate into temps first.
            BytecodeLocal[] temps = new BytecodeLocal[n];
            for (int i = 0; i < n; i++) {
                if (!identity[i]) {
                    temps[i] = b.createLocal();
                    b.beginStoreLocal(temps[i]);
                    emitNode(args.get(i), b, argCount, localMap);
                    b.endStoreLocal();
                }
            }
            for (int i = 0; i < n; i++) {
                if (!identity[i]) {
                    b.beginStoreLocal(stlParams[i]);
                    b.emitLoadLocal(temps[i]);
                    b.endStoreLocal();
                }
            }
        }
        b.endBlock();
    }

    /**
     * Returns true if {@code arg} at position {@code paramIndex} is
     * an identity read of the corresponding self-tail-loop parameter.
     */
    private boolean stlIsIdentityArg(NodeIR arg, int paramIndex) {
        int expected = selfTailCaptureCount + paramIndex;
        if (arg instanceof ReadLocalIR r) return r.getSlot() == expected;
        if (arg instanceof ReadArgIR r) return r.getIndex() == expected;
        return false;
    }
}
