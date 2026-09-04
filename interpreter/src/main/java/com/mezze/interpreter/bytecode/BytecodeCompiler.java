package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.builtin.dsl.NativeHandler;
import com.mezze.interpreter.nodes.builtin.dsl.NativeHandlers;
import com.mezze.interpreter.nodes.builtin.dsl.TypedNativeHandlers;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.truffleir.BlockIR;
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
import com.mezze.language.truffleir.FusedChainIR;
import com.mezze.language.truffleir.SourceIR;
import com.mezze.language.truffleir.StageIR;
import com.mezze.language.truffleir.SinkIR;
import com.mezze.language.truffleir.LongLitIR;
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
                        System.err.println("[eager #391] closure-bearing eager CT hash="
                                + hash.getHex() + " captures=" + ir.getCaptureSlots().size());
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
        if (node instanceof FusedChainIR) {
            // Canon-emit contract (#446): canonicalizer must only produce
            // shapes emit can handle. Accept unconditionally here; if emit
            // refuses at runtime, it throws and MezzeLanguage buckets it
            // as emit-crash. Without this branch (removed by oversight
            // when Phase 4b landed), supports() rejects at line 864 and
            // emit is never given a chance — which crashed all
            // fusion-producing user programs like bench/cases/sum_fold.
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
            // MakeSeqWithSpread packs isSpread as a 63-bit long; larger
            // literals fall back to AST until a boolean[]-operand variant
            // is added.
            if (ms.getElements().size() > 63) { markReject(node, "over-63"); return false; }
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
            emitNode(ir.getBody(), b, argCount, localMap);
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
            trampoline.attachInner(inner, hasTail);
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
                b.emitLoadArgument(slot);
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
            b.emitLoadArgument(r.getIndex());
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
            // Fail loud if the canonicalizer produced a shape the emitter
            // can't handle. The two must stay in lockstep: any shape the
            // canonicalizer emits must be emittable here. Silent fall-
            // through would miscompile (there's no CallDirect equivalent
            // to reconstruct).
            throw new IllegalStateException(
                "FusedChainIR reached BDSL emit but emitter refused it. " +
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
            if (c.isScalarABI()
                    && target instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof TrampolineRootNode tramp
                    && !tramp.hasTailCalls()
                    && !c.isTailCall()
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
            boolean needsScalarMarker = c.isScalarABI()
                    && c.getArgs().size() == 1
                    && !isBdslCallee(target);
            if (c.isTailCall()) {
                b.beginTailCallDirectN(target);
                if (needsScalarMarker) {
                    b.emitLoadObjectConstant(
                            com.mezze.interpreter.nodes.function.ScalarABIMarker.INSTANCE);
                }
                for (NodeIR arg : c.getArgs()) {
                    emitNode(arg, b, argCount, localMap);
                }
                b.endTailCallDirectN();
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
            b.beginCallIndirect();
            int slot = dm.getDictSlot();
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
            // Lower as CallIndirect(function, MakeRecord(argFieldNames, args)).
            // This mirrors InvokeScalarNNode's fallback path when no
            // typed-payload fast path applies — we get the record-ABI
            // dispatch for free at the cost of one MezzeRecord allocation.
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
                    b.emitLoadArgument(slot);
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
                // #350: resolve the handler at emit time (no string dispatch
                // at runtime). supports() has already verified one of the
                // two record-ABI handler maps has this name.
                String recName = cn.getName();
                var withNode = com.mezze.interpreter.nodes.builtin.NativeDispatch
                        .recordHandlerWithNodeOf(recName);
                if (withNode != null) {
                    b.beginCallNativeRecordWithNode(withNode);
                    emitNode(cn.getArg(), b, argCount, localMap);
                    b.endCallNativeRecordWithNode();
                    return;
                }
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
            // Typed fast paths — bypass Object[] alloc + PositionalArgs
            // + NativeHandler indirection when the signature matches
            // one we registered typed via TypedNativeHandlers. Ordered
            // by argument count so we short-circuit early.
            String name = cn.getName();
            int arity = cn.getArgs().size();
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
            m.put("eq@stdlib-std-int.mz",  InlineOp.EQ);
            m.put("neq@stdlib-std-int.mz", InlineOp.NEQ);
            m.put("lt@stdlib-std-int.mz",  InlineOp.LT);
            m.put("le@stdlib-std-int.mz",  InlineOp.LE);
            m.put("gt@stdlib-std-int.mz",  InlineOp.GT);
            m.put("ge@stdlib-std-int.mz",  InlineOp.GE);
            KEY_TO_OP = java.util.Map.copyOf(m);
        }

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
                if (op != null) m.put(e.getKey(), op);
            }
            return m;
        }

        private static String basename(String path) {
            if (path == null) return "";
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }
    }

    private static final class FolderShape {
        final InlineOp op;
        final int leftSlot;
        final int rightSlot;
        FolderShape(InlineOp op, int l, int r) { this.op = op; this.leftSlot = l; this.rightSlot = r; }
    }

    private static FolderShape classifyFolderBody(NodeIR body) {
        if (body instanceof InlineBinaryOpIR bin) {
            if (bin.getLeft() instanceof ReadLocalIR l && bin.getRight() instanceof ReadLocalIR r) {
                return new FolderShape(bin.getOp(), l.getSlot(), r.getSlot());
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
            if (!(rExpr instanceof ReadLocalIR rSlot)) return null;
            return new FolderShape(inlineOp, recvRead.getSlot(), rSlot.getSlot());
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
            }
        }
        java.util.Collections.reverse(classifiedStages);
        boolean hasStages = !classifiedStages.isEmpty();

        // ── Phase 4c: classify source ──
        NodeIR rangeStart = null, rangeEnd = null;
        NodeIR rawXsExpr = null;
        boolean isSumSink = sinkMode instanceof FusedSinkMode.Accumulate a
                && !a.ignoreElement() && a.op() == InlineOp.ADD;
        if (chain.getSource() instanceof SourceIR.RangeSeq rangeSrc) {
            rangeStart = rangeSrc.getStart();
            rangeEnd = rangeSrc.getEnd();
        } else if (chain.getSource() instanceof SourceIR.RawSeq rawSrc) {
            rawXsExpr = rawSrc.getXs();
        } else if (chain.getSource() instanceof SourceIR.Opaque opaqueSrc && isSumSink
                && !hasStages) {
            b.beginBuiltinSumLongSeq();
              emitNode(opaqueSrc.getExpr(), b, argCount, localMap);
            b.endBuiltinSumLongSeq();
            if (dbg) System.err.println("[chain-canon-emit] emitted BuiltinSumLongSeq (Opaque+Sum)");
            return true;
        } else {
            if (dbg) System.err.println("[chain-canon-emit] reject: unhandled source/sink combo");
            return false;
        }

        // ── Fast paths — skip the generic loop for simple builtins ──
        if (sinkMode instanceof FusedSinkMode.Accumulate a && !a.ignoreElement()) {
            // #427 fast path: ADD folder over range with no stages → BuiltinSumRangeLong.
            if (rangeStart != null && a.op() == InlineOp.ADD && !hasStages) {
                b.beginBuiltinSumRangeLong();
                  emitNode(rangeStart, b, argCount, localMap);
                  emitNode(rangeEnd,   b, argCount, localMap);
                  emitNode(a.initExpr(), b, argCount, localMap);
                b.endBuiltinSumRangeLong();
                if (dbg) System.err.println("[chain-canon-emit] emitted BuiltinSumRangeLong");
                return true;
            }
            // Sum over RawSeq with no stages → BuiltinSumLongSeq.
            if (rawXsExpr != null && isSumSink && !hasStages) {
                b.beginBuiltinSumLongSeq();
                  emitNode(rawXsExpr, b, argCount, localMap);
                b.endBuiltinSumLongSeq();
                if (dbg) System.err.println("[chain-canon-emit] emitted BuiltinSumLongSeq (RawSeq+Sum)");
                return true;
            }
        }

        // ── Phase 4d: emit the generic fused loop ──
        emitFusedLoop(classifiedStages, rangeStart, rangeEnd, rawXsExpr,
                sinkMode, b, argCount, localMap);
        if (dbg) System.err.println("[chain-canon-emit] emitted fused loop ("
                + sinkMode.getClass().getSimpleName() + ", " + classifiedStages.size() + " stages)");
        return true;
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
              b.beginMakeRecord(new String[] { fieldName });
                b.emitLoadLocal(xSlot);
              b.endMakeRecord();
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
            // the stack, becoming the IfThen condition. Stage the arg
            // through a fresh local to isolate it from the callee's frame
            // activity (see emitMapperStep for the full rationale).
            var target = resolveCallTarget(filter.callHash);
            if (target == null && language != null) {
                target = language.getOrMintUserStub(filter.callHash, "fused_pred_check");
            }
            if (target != null) {
                var argCopy = b.createLocal();
                b.beginStoreLocal(argCopy);
                  b.emitLoadLocal(xSlot);
                b.endStoreLocal();
                emitCallOneArg(b, target, argCopy,
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
    }

    // ── Sink mode: the per-element action and post-loop result ──────────

    /** Describes what the fused loop does at each element and what it returns. */
    private sealed interface FusedSinkMode {
        /** acc = init; per element: acc = acc <op> x. Covers Fold/Sum. */
        record Accumulate(InlineOp op, NodeIR initExpr, boolean ignoreElement) implements FusedSinkMode {}
        /** Call f(x) per element, discard result. */
        record CallEach(com.oracle.truffle.api.CallTarget target, boolean scalarABI,
                        String fieldName) implements FusedSinkMode {}
        /** Short-circuit predicate: Any/All/Find. */
        record ShortCircuit(FilterPeel predPeel, ShortCircuitKind kind) implements FusedSinkMode {}
        /** Fold with first element as init; result wrapped in Option. */
        record ReduceOpt(InlineOp op) implements FusedSinkMode {}
        /** Collect elements into a Seq or List. */
        record Materialize(MaterializeTarget target) implements FusedSinkMode {}

        enum ShortCircuitKind { ANY, ALL, FIND }
        enum MaterializeTarget { SEQ, LIST }
    }

    /**
     * Classify the chain's sink into a {@link FusedSinkMode}, doing
     * lambda/folder classification up front. Returns null on rejection.
     */
    private FusedSinkMode classifySinkMode(FusedChainIR chain, boolean dbg) {
        if (chain.getSink() instanceof SinkIR.Fold foldSink) {
            var folderOp = classifyBinaryFolder(foldSink.getF(), dbg, "folder");
            if (folderOp == null) return null;
            return new FusedSinkMode.Accumulate(folderOp, foldSink.getInit(), false);
        }
        if (chain.getSink() instanceof SinkIR.Sum) {
            return new FusedSinkMode.Accumulate(InlineOp.ADD,
                    new LongLitIR(0L, chain.getSrc()), false);
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
        if (chain.getSink() instanceof SinkIR.Reduce reduceSink) {
            var reducerOp = classifyBinaryFolder(reduceSink.getF(), dbg, "reducer");
            if (reducerOp == null) return null;
            return new FusedSinkMode.ReduceOpt(reducerOp);
        }
        if (chain.getSink() instanceof SinkIR.ToSeq) {
            return new FusedSinkMode.Materialize(FusedSinkMode.MaterializeTarget.SEQ);
        }
        if (chain.getSink() instanceof SinkIR.ToList) {
            return new FusedSinkMode.Materialize(FusedSinkMode.MaterializeTarget.LIST);
        }
        if (dbg) System.err.println("[chain-canon-emit] reject: unsupported sink: "
                + chain.getSink().getClass().getSimpleName());
        return null;
    }

    /** Shared classification for Fold and Reduce combining functions. */
    private InlineOp classifyBinaryFolder(MakeLambdaIR lambda, boolean dbg, String label) {
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
        return op;
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
            NodeIR rangeStart, NodeIR rangeEnd, NodeIR rawXsExpr,
            FusedSinkMode mode,
            MezzeBytecodeRootNodeGen.Builder b,
            int argCount,
            com.oracle.truffle.api.bytecode.BytecodeLocal[] localMap) {

        boolean isRange = rangeStart != null;
        boolean useCompoundCond = mode instanceof FusedSinkMode.ShortCircuit;

        b.beginBlock();

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
        }

        // ── Source init ──
        com.oracle.truffle.api.bytecode.BytecodeLocal endSlot  = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal seqSlot  = null;
        com.oracle.truffle.api.bytecode.BytecodeLocal sizeSlot = null;

        if (isRange) {
            endSlot = b.createLocal();
            b.beginStoreLocal(iSlot);
              emitNode(rangeStart, b, argCount, localMap);
            b.endStoreLocal();
            b.beginStoreLocal(endSlot);
              emitNode(rangeEnd, b, argCount, localMap);
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

        var boundSlot = isRange ? endSlot : sizeSlot;

        // ── Init builder for materializing sinks ──
        // RangeSeq sources produce longs — use a long[] builder to avoid
        // boxing. RawSeq sources use the generic ArrayList<Object> builder.
        boolean useLongBuilder = isRange && mode instanceof FusedSinkMode.Materialize;
        if (mode instanceof FusedSinkMode.Materialize) {
            // Size hint: range end - start (if range); else seq size.
            // With filters the actual count may be less, but over-alloc
            // is cheaper than realloc.
            b.beginStoreLocal(builderSlot);
            if (useLongBuilder) {
                b.beginNewLongSeqBuilder();
                  b.beginDirectSubtract();
                    b.emitLoadLocal(endSlot); b.emitLoadLocal(iSlot);
                  b.endDirectSubtract();
                b.endNewLongSeqBuilder();
            } else {
                b.beginNewSeqBuilder();
                  b.emitLoadLocal(sizeSlot);
                b.endNewSeqBuilder();
            }
            b.endStoreLocal();
        }

        // ── Pre-allocate Take/Drop counter locals ──
        var takeDropCounters = new java.util.ArrayList<com.oracle.truffle.api.bytecode.BytecodeLocal>();
        for (var stage : stages) {
            if (stage instanceof ClassifiedStage.TakeStep
                    || stage instanceof ClassifiedStage.DropStep) {
                var ctr = b.createLocal();
                b.beginStoreLocal(ctr);
                  b.emitLoadLongConstant(0L);
                b.endStoreLocal();
                takeDropCounters.add(ctr);
            }
        }

        // ── While loop ──
        b.beginWhile();

        // Condition: i < end [&& !found]
        if (useCompoundCond) {
            b.beginDirectAnd();
              b.beginDirectLessThan();
                b.emitLoadLocal(iSlot); b.emitLoadLocal(boundSlot);
              b.endDirectLessThan();
              b.beginDirectNot();
                b.emitLoadLocal(foundSlot);
              b.endDirectNot();
            b.endDirectAnd();
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
                // Any:  if (p(x))  → found = true
                // All:  if (!p(x)) → found = true
                // Find: if (p(x))  → found = true; result = x
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
                  b.endBlock();
                b.endIfThen();
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
                    // long[] builder = LongSeqBuilderAdd(builder, x)
                    // Must store back — array may grow (new allocation).
                    b.beginStoreLocal(builderSlot);
                      b.beginLongSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(xSlot);
                      b.endLongSeqBuilderAdd();
                    b.endStoreLocal();
                } else {
                    // builder.add(x) — discard the returned builder ref
                    var discardSlot2 = b.createLocal();
                    b.beginStoreLocal(discardSlot2);
                      b.beginSeqBuilderAdd();
                        b.emitLoadLocal(builderSlot);
                        b.emitLoadLocal(xSlot);
                      b.endSeqBuilderAdd();
                    b.endStoreLocal();
                }
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
        b.beginStoreLocal(iSlot);
          b.beginDirectAdd();
            b.emitLoadLocal(iSlot); b.emitLoadLongConstant(1L);
          b.endDirectAdd();
        b.endStoreLocal();

        b.endBlock();
        b.endWhile();

        // ── Post-loop result (mode-specific) ──
        switch (mode) {
            case FusedSinkMode.Accumulate ignored ->
                b.emitLoadLocal(accSlot);
            case FusedSinkMode.CallEach ignored ->
                b.emitLoadObjectConstant(com.mezze.interpreter.values.MezzeRecord.empty());
            case FusedSinkMode.ShortCircuit sc -> {
                switch (sc.kind()) {
                    case ANY -> b.emitLoadLocal(foundSlot);
                    case ALL -> {
                        b.beginDirectNot();
                          b.emitLoadLocal(foundSlot);
                        b.endDirectNot();
                    }
                    case FIND -> {
                        b.beginConditional();
                          b.emitLoadLocal(foundSlot);
                          b.beginMakeVariantSinglePayload(SOME_TAG,
                              com.mezze.language.ast.BuiltinTypes.SOME);
                            b.beginMakeRecord(VAL_FIELD);
                              b.emitLoadLocal(resultSlot);
                            b.endMakeRecord();
                          b.endMakeVariantSinglePayload();
                          b.emitMakeVariantNoPayload(NONE_TAG,
                              com.mezze.language.ast.BuiltinTypes.NONE);
                        b.endConditional();
                    }
                }
            }
            case FusedSinkMode.ReduceOpt ignored -> {
                b.beginConditional();
                  b.emitLoadLocal(hasAccSlot);
                  b.beginMakeVariantSinglePayload(SOME_TAG,
                      com.mezze.language.ast.BuiltinTypes.SOME);
                    b.beginMakeRecord(VAL_FIELD);
                      b.emitLoadLocal(accSlot);
                    b.endMakeRecord();
                  b.endMakeVariantSinglePayload();
                  b.emitMakeVariantNoPayload(NONE_TAG,
                      com.mezze.language.ast.BuiltinTypes.NONE);
                b.endConditional();
            }
            case FusedSinkMode.Materialize m -> {
                if (useLongBuilder) {
                    // long[] → MezzeSeq via LongSeqStorage (unboxed)
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
            NodeIR lifted = liftCaptureRhs(right, caps);
            if (lifted != null) return new UnaryLitShape(op, lifted);
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
        b.beginBlock();
        BytecodeLocal resultSlot = b.createLocal();
        b.beginTryCatchOtherwise(() -> {
            // otherwise (normal exit): pop the chain (runs finalize_scope)
            b.emitPopHandlerChain();
        });
        // === try branch ===
        b.beginBlock();
        // Push handler on chain
        b.beginPushHandlerChain(hb.getEffectHash());
        if (hb.getParams() != null) {
            b.beginLookupParameterizedHandlerByHash(hb.getHandlerHash());
            emitNode(hb.getParams(), b, argCount, localMap);
            b.endLookupParameterizedHandlerByHash();
        } else {
            b.emitLookupHandlerByHash(hb.getHandlerHash());
        }
        b.endPushHandlerChain();
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
        b.emitPopHandlerChain();
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
    private void emitMatch(MatchIR m, MezzeBytecodeRootNodeGen.Builder b,
                           int argCount, BytecodeLocal[] localMap) {
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
        PatternIR.Variant v = (PatternIR.Variant) pat;
        b.beginConditional();
        // Condition.
        b.beginVariantTagEquals(v.getTag(), v.getTagName());
        b.emitLoadLocal(scrutineeLocal);
        b.endVariantTagEquals();
        // Then-branch. Bind each captured field of the payload record:
        // for a pattern like Some { value = v }, fieldNames=["value"],
        // fieldSlots=[slot(v)]. Read v = payload.value. When the pattern
        // is a bare payload variable Some v — no {} — fieldNames = [v]
        // and the AST also does a record-field extraction; that same
        // shape wouldn't typecheck without an actual record payload, so
        // treat both identically.
        b.beginBlock();
        var names = v.getFieldNames();
        var slots = v.getFieldSlots();
        if (!names.isEmpty()) {
            BytecodeLocal payloadLocal = b.createLocal();
            b.beginStoreLocal(payloadLocal);
            b.beginUnwrapSinglePayload();
            b.emitLoadLocal(scrutineeLocal);
            b.endUnwrapSinglePayload();
            b.endStoreLocal();
            for (int i = 0; i < names.size(); i++) {
                int slot = slots.get(i);
                if (slot < 0) continue;  // wildcard field (`_`) — no binding
                BytecodeLocal target = localMap[slot - argCount];
                b.beginStoreLocal(target);
                b.beginReadFieldByName(names.get(i));
                b.emitLoadLocal(payloadLocal);
                b.endReadFieldByName();
                b.endStoreLocal();
            }
        }
        emitCaseGuardsAndBody(c, cases, idx, scrutineeLocal, b, argCount, localMap);
        b.endBlock();
        // Else-branch (tag mismatch) — recurse.
        emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
        b.endConditional();
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
            // Then: bind captured fields (via payload record) and continue.
            b.beginBlock();
            var names = iv.getFieldNames();
            var slots = iv.getFieldSlots();
            if (!names.isEmpty()) {
                BytecodeLocal payloadLocal = b.createLocal();
                b.beginStoreLocal(payloadLocal);
                b.beginUnwrapSinglePayload();
                b.emitLoadLocal(tmp);
                b.endUnwrapSinglePayload();
                b.endStoreLocal();
                for (int i = 0; i < names.size(); i++) {
                    int slot = slots.get(i);
                    if (slot < 0) continue;  // wildcard field (`_`) — no binding
                    BytecodeLocal target = localMap[slot - argCount];
                    b.beginStoreLocal(target);
                    b.beginReadFieldByName(names.get(i));
                    b.emitLoadLocal(payloadLocal);
                    b.endReadFieldByName();
                    b.endStoreLocal();
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
}
