package com.mezze.interpreter.runtime;

import com.mezze.interpreter.instrumentation.FrameSlotResolver;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mezze language registration for Truffle - V2 OPTIMIZED.
 *
 * Fully functional language implementation with:
 * - Source code parsing via MezzeParser
 * - Type checking via TypeChecker
 * - TruffleIR compilation via PipelineRunner/TruffleCompiler
 *
 * Uses "mezze" as the language ID - V2 is the default interpreter.
 */
@TruffleLanguage.Registration(
    id = MezzeLanguage.ID,
    name = "Mezze",
    version = "2.0",
    defaultMimeType = MezzeLanguage.MIME_TYPE,
    characterMimeTypes = MezzeLanguage.MIME_TYPE,
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED
)
@ProvidedTags({
    // Tags advertised to Truffle instruments (debugger, sampler, coverage,
    // tracer, Insight, JFR). Per-node `hasTag(...)` is implemented by
    // InstrumentableNode subclasses (separate task). Without these tags,
    // every Truffle tool silently no-ops.
    StandardTags.RootTag.class,
    StandardTags.RootBodyTag.class,
    StandardTags.StatementTag.class,
    StandardTags.ExpressionTag.class,
    StandardTags.CallTag.class,
    StandardTags.ReadVariableTag.class,
    StandardTags.WriteVariableTag.class,
    // #356: DebuggerSession.suspendAll() / suspendNextExecution() install
    // an AlwaysHalt probe on the running roots. Without this tag declared,
    // the guest execution throws IllegalArgumentException the moment a
    // pause request lands on any root — and pause silently no-ops
    // (evalThread dies with "Invalid tag specified" before it can suspend).
    com.oracle.truffle.api.debug.DebuggerTags.AlwaysHalt.class
})
public final class MezzeLanguage extends TruffleLanguage<MezzeContext> {

    public static final String ID = "mezze";
    public static final String MIME_TYPE = "application/x-mezze";

    // Populate the NativeOp DSL registry on first class load. Tests and
    // embedded callers that bypass the CLI entrypoint still trigger this
    // because they touch MezzeLanguage to register the Truffle language.
    // Idempotent — NativeBootstrap guards itself with an AtomicBoolean.
    // See docs/perf/scalar-native-abi-2026-06.md.
    static {
        com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap.INSTANCE.ensureLoaded();
    }

    // Store last execution result for test access
    private static final AtomicReference<Object> lastResultStore = new AtomicReference<>();

    // Store last execution context (variable bindings by name) for test access.
    // Populated from MezzeContext.testInspectionVars — explicitly name-keyed for
    // tests that look up top-level lets by source identifier.
    private static final AtomicReference<Map<String, Object>> lastContextStore = new AtomicReference<>();

    // Parser and typechecker live on MezzeContext (per-context isolation).
    // NOT static — GraalVM native-image cannot serialize TypeChecker into the image heap
    // because it triggers BuiltInAbilities/stdlib loading at class init time.

    // CallTarget cache — shared across all tests for the same Language instance.
    // Content-hash keys ensure identical functions reuse the same CallTarget,
    // preventing accumulation on the Engine across 5000+ test runs.
    private final java.util.concurrent.ConcurrentHashMap<Hash, CallTarget> stdlibCallTargetCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // #391 — Scaffold for eager whole-program BDSL compilation. Sits
    // alongside the three existing caches (globals / userStubCache /
    // stdlibCallTargetCache) that later migration steps will collapse
    // into it. No callers yet.
    private final SlotTable slotTable = new SlotTable();

    public SlotTable getSlotTable() {
        return slotTable;
    }

    // BDSL routing (#284) — Phase 4 flipped to DEFAULT-ON (2026-08-02).
    // Every per-function compile tries BytecodeCompiler first; failure ⇒
    // falls back to TruffleCompiler AST path. BDSL coverage on the test
    // suite is empirically 100% (per BDSL_STATS runs), so the fallback
    // fires only in edge cases the AST path also handles safely.
    //
    // Opt out via MEZZE_BDSL=0 or MEZZE_BDSL=false — kept as an escape
    // hatch while the AST interpreter still exists. Once Phase 5 deletes
    // the AST path entirely, this flag goes away.
    private static final boolean BDSL_ENABLED = computeBdslDefault();

    private static boolean computeBdslDefault() {
        String v = System.getenv("MEZZE_BDSL");
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) return false;
        return true;  // default ON
    }

    // #356 — When a debugger is attached (DAP, Chrome Inspector), BDSL
    // compilation switches to BytecodeConfig.COMPLETE + source/tag/name
    // annotations so line breakpoints, stepping, scopes, and setVariable
    // work on BDSL-compiled functions. Off by default so the hot path
    // stays lean (parseSources allocates per-op source-section bytes).
    //
    // Flipped by GraalDapBridge ctor + EmbeddedExec/RuntimeServer wire-in.
    // BDSL still routes (unlike the previous disable-flip); the compile
    // shape is what changes.
    private static volatile boolean BDSL_DEBUG_INSTRUMENTATION = false;

    /**
     * #359 — flag-flip epoch. Bumped when
     * {@link #enableBdslDebugInstrumentation()} transitions the flag,
     * so callers can invalidate CallTargets that were compiled under
     * the previous debug shape. Each MezzeLanguage instance tracks its
     * own last-observed epoch and drops its stdlib/imported caches +
     * BDSL compiler state when it moves.
     */
    private static final java.util.concurrent.atomic.AtomicInteger DEBUG_EPOCH =
        new java.util.concurrent.atomic.AtomicInteger(0);

    public static void enableBdslDebugInstrumentation() {
        boolean was = BDSL_DEBUG_INSTRUMENTATION;
        BDSL_DEBUG_INSTRUMENTATION = true;
        if (!was) DEBUG_EPOCH.incrementAndGet();
    }

    /** Test-only: turn debug back off AND bump the epoch, so a test
     *  that enabled the flag doesn't leave debug-shape CallTargets in
     *  the cache for subsequent tests. */
    public static void disableBdslDebugInstrumentationForTest() {
        boolean was = BDSL_DEBUG_INSTRUMENTATION;
        BDSL_DEBUG_INSTRUMENTATION = false;
        if (was) DEBUG_EPOCH.incrementAndGet();
    }

    public static boolean isBdslDebugInstrumentation() {
        return BDSL_DEBUG_INSTRUMENTATION;
    }

    static int currentDebugEpoch() { return DEBUG_EPOCH.get(); }

    // Per-instance last-observed epoch. When it lags DEBUG_EPOCH we
    // invalidate our caches before the next compile.
    private volatile int cachedDebugEpoch = 0;

    // Effect dispatch (Path D, docs/perf/bdsl-effects-path-d-2026-07.md):
    // every perform lowers to a BDSL PerformD op that dispatches the
    // effect directly against the thread's handler chain — no Loom fiber,
    // no reflection. Loom-fiber routing was deleted in #333.

    // Lazy-until-fully-discharged effect runtime (task #336). The BDSL
    // Handle @Operation builds a MezzeSuspend carrying the whole body
    // continuation (bodyNode + frame + chain snapshot) instead of eagerly
    // running the body and losing the continuation at first unhandled op.
    // Force is atomic — merges chains, runs body inline on caller thread,
    // no fiber.
    private static final boolean BDSL_STATS =
            System.getenv("MEZZE_BDSL_STATS") != null
                && !"0".equals(System.getenv("MEZZE_BDSL_STATS"))
                && !"false".equalsIgnoreCase(System.getenv("MEZZE_BDSL_STATS"));
    private static final java.util.concurrent.atomic.AtomicLong GLOBAL_BDSL_HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong GLOBAL_BDSL_MISS_UNSAFE = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong GLOBAL_BDSL_MISS_UNSUPPORTED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong GLOBAL_BDSL_MISS_CRASH = new java.util.concurrent.atomic.AtomicLong();
    static {
        if (BDSL_STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                long h = GLOBAL_BDSL_HITS.get();
                long unsafe = GLOBAL_BDSL_MISS_UNSAFE.get();
                long unsup = GLOBAL_BDSL_MISS_UNSUPPORTED.get();
                long crash = GLOBAL_BDSL_MISS_CRASH.get();
                long m = unsafe + unsup + crash, t = h + m;
                if (t > 0) {
                    System.err.println("[BDSL] compile counts: " + h + " hits, " + m + " misses ("
                            + String.format("%.1f", 100.0 * h / t) + "% BDSL) — "
                            + "unsafe-callee=" + unsafe + " unsupported=" + unsup + " emit-crash=" + crash);
                    if (unsup > 0) {
                        var top = com.mezze.interpreter.bytecode.BytecodeCompiler.getRejectionCounts();
                        int i = 0;
                        System.err.print("[BDSL] top unsupported reasons: ");
                        for (var e : top.entrySet()) {
                            if (i++ >= 10) break;
                            if (i > 1) System.err.print(", ");
                            System.err.print(e.getKey() + "=" + e.getValue());
                        }
                        System.err.println();
                    }
                    if (crash > 0) {
                        var top = com.mezze.interpreter.bytecode.BytecodeCompiler.getCrashCounts();
                        int i = 0;
                        System.err.print("[BDSL] top emit-crash reasons: ");
                        for (var e : top.entrySet()) {
                            if (i++ >= 10) break;
                            if (i > 1) System.err.print(", ");
                            System.err.print(e.getKey() + "=" + e.getValue());
                        }
                        System.err.println();
                    }
                }
            }, "bdsl-stats"));
        }
    }
    private volatile com.mezze.interpreter.bytecode.BytecodeCompiler bdslCompiler;
    private final java.util.concurrent.atomic.AtomicLong bdslHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bdslMisses = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Compile one FunctionIR through BDSL. Under gate-ON default, BDSL is
     * the sole compilation path — no AST fallback. Audit `docs/effects/
     * bdsl-eager-steps-7-13-audit.md` proved 0 fallback firings across
     * the full test suite under gate-ON. #391 Step 7 c1.
     *
     * <p>The BDSL compiler shares an external CallTarget resolver that
     * looks into {@link #stdlibCallTargetCache} — so cross-fn calls to
     * already-compiled targets resolve without duplicating them in BDSL's
     * own map.
     */
    private com.oracle.truffle.api.nodes.RootNode compileOne(
            com.mezze.language.truffleir.FunctionIR ir) {
        // #359 — flag-flip epoch check. When BDSL_DEBUG_INSTRUMENTATION
        // was toggled after we last cached anything, drop caches so we
        // recompile in the new shape (debug vs non-debug). Otherwise a
        // test that enables debug for its own Context poisons subsequent
        // tests with debug-shape CallTargets (ProbeNode.onReturnValue
        // then rejects internal Mezze types on tag-instrumented ops).
        int nowEpoch = DEBUG_EPOCH.get();
        if (nowEpoch != cachedDebugEpoch) {
            invalidateDebugShapeCaches(nowEpoch);
        }
        if (!isBdslSafeCallee(ir)) {
            bdslMisses.incrementAndGet();
            if (BDSL_STATS) GLOBAL_BDSL_MISS_UNSAFE.incrementAndGet();
            throw bdslCompileError(ir, "unsafe-callee", null);
        }
        com.mezze.interpreter.bytecode.BytecodeCompiler bdsl = getOrCreateBdslCompiler();
        if (!bdsl.supports(ir)) {
            bdslMisses.incrementAndGet();
            if (BDSL_STATS) GLOBAL_BDSL_MISS_UNSUPPORTED.incrementAndGet();
            throw bdslCompileError(ir, "unsupported", null);
        }
        try {
            var root = bdsl.compile(ir);
            bdslHits.incrementAndGet();
            if (BDSL_STATS) GLOBAL_BDSL_HITS.incrementAndGet();
            return root;
        } catch (Throwable t) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[BDSL] emit failed for " + ir.getName() + ": " + t);
            }
            bdslMisses.incrementAndGet();
            if (BDSL_STATS) {
                GLOBAL_BDSL_MISS_CRASH.incrementAndGet();
                com.mezze.interpreter.bytecode.BytecodeCompiler.noteCrash(t);
            }
            throw bdslCompileError(ir, "emit-crash", t);
        }
    }

    /** BDSL compile failure — surfaced instead of falling back to AST
     *  (the AST tree is deleted; there is nothing to fall back to). */
    private static RuntimeException bdslCompileError(
            com.mezze.language.truffleir.FunctionIR ir, String reason, Throwable cause) {
        String msg = "[BDSL] compile failed for " + ir.getName()
                + " (hash=" + ir.getHash().getHex().substring(0, 8) + ") reason=" + reason;
        return cause == null ? new RuntimeException(msg) : new RuntimeException(msg, cause);
    }

    private com.mezze.interpreter.bytecode.BytecodeCompiler getOrCreateBdslCompiler() {
        com.mezze.interpreter.bytecode.BytecodeCompiler c = bdslCompiler;
        if (c == null) {
            synchronized (this) {
                c = bdslCompiler;
                if (c == null) {
                    // External resolver: on a supports()/emit callee-hash
                    // check, BDSL asks the shared cache. Returns null if not
                    // yet compiled — supports() then rejects, driving fallback.
                    c = new com.mezze.interpreter.bytecode.BytecodeCompiler(
                            this,
                            (java.util.function.Function<Hash, CallTarget>) stdlibCallTargetCache::get);
                    bdslCompiler = c;
                }
            }
        }
        return c;
    }

    /**
     * #359 — drop everything cached under the previous debug-shape flag
     * so the next compile builds under the current shape. Called from
     * compileOne when it detects DEBUG_EPOCH moved.
     *
     * <p>Also drops the BDSL compiler instance itself — its internal
     * compiledCallTargets map has references to same-epoch CallTargets
     * baked into its bytecode-node emit constants; the next
     * getOrCreateBdslCompiler rebuilds it fresh.
     *
     * <p>Stdlib PrecompiledStdlib CallTargets are Language-static
     * (initialized once) and remain — that's fine because those are
     * compiled at build time in non-debug shape, and the debug shape
     * only matters for user code the debugger stops in.
     */
    private synchronized void invalidateDebugShapeCaches(int newEpoch) {
        if (cachedDebugEpoch == newEpoch) return;
        stdlibCallTargetCache.clear();
        importedCallTargetCache.clear();
        userStubCache.clear();
        bdslCompiler = null;
        cachedDebugEpoch = newEpoch;
    }

    /**
     * A function is "BDSL-safe as callee" iff its calling convention won't
     * be record-ABI at runtime. BDSL's LoadArgument(i) treats args[i]
     * verbatim as slot[i] — it can't unpack a MezzeRecord by field name.
     *
     * <p>Conservative: bail on any function that has params. Rationale: at
     * this wiring stage we can't tell whether callers of this function
     * will invoke it under scalar ABI or record ABI (the AST caller decides
     * based on the call site's argument shape). Parameter-less functions
     * (mostly top-level lets and closures capturing state) are always
     * scalar-safe — args[0..closureCount-1] are closure values,
     * closureCount == args.length.
     *
     * <p>Later steps: (1) BDSL root wraps execute() with record-ABI unpack,
     * matching MezzeFunctionRootNode's discrimination logic; (2) tighten
     * this predicate to accept scalar-only callees the type-checker can
     * mark unambiguously; (3) allow record-ABI when the record field names
     * match the paramNames in order (the same "unpackRecordFast" shape).
     *
     * <p>Zero-param zero-capture "value thunks" (constants like
     * {@code math::e}, top-level {@code let x = ...}) are safe because
     * {@code ReadGlobalByHashNode.autoEvaluateThunk} recognises BDSL
     * roots as thunk-eligible.
     *
     * <p>Non-zero-param callees are safe because
     * {@link com.mezze.interpreter.bytecode.TrampolineRootNode#execute}
     * normalises record ABI into the flat scalar layout BDSL's
     * {@code LoadArgument(i)} expects (see
     * {@link com.mezze.interpreter.bytecode.TrampolineRootNode#normalizeArgs}).
     *
     * <p>Excluded: bodies that contain a {@code ReadFieldIR} on any
     * subtree. BDSL's ReadFieldByName op currently mishandles records
     * carried through closure captures inside {@code do}-block
     * destructuring (see DestructuringRenameIntegrationTest
     * .testMultipleRenamesInDoBlock — the op receives {@code String, Long}
     * instead of {@code MezzeRecord, String}). Skipping such bodies
     * keeps them on the AST path until the BDSL emit for ReadField is
     * fixed. Value-constants (numbers, strings, seq literals) are
     * unaffected since they have no ReadFieldIR.
     */
    private static boolean isBdslSafeCallee(com.mezze.language.truffleir.FunctionIR ir) {
        // hasReadField gate dropped:
        //   - Group A/C (nested pattern + guard-after-iflet order): fixed
        //     by cfd364caf, 7ea2bdc82, ae7a0698a.
        //   - Group B (scalar .to_dbg_str{} / .to_str{}): fixed by
        //     BytecodeCompiler.tryFoldInspectMethod peephole +
        //     InlineScalarInspect @Operation.
        // Known holdout (accepted): user newtype Eq impl bodies pattern-
        // match `UserId {val = ...}` where the payload record was
        // constructed with a differently-named field (typechecker
        // renames "val"->"value" only in the AST-only path). The IR
        // doesn't distinguish this shape from Option's `Some {val = ..}`
        // (which is correct), so a narrow gate would over-reject 740
        // legit fns. Left as a single-test regression
        // (newtypeEqWithNeqViaNot) pending a typechecker-level fix.
        return true;
    }

    /**
     * Kept in sync with BytecodeCompiler.inlineOpFor MINUS eq/neq —
     * those are excluded because user newtype impls of Eq chain
     * `.eq {r=r}.not {}` where the receiver is a Variant and the
     * dispatch must go through dict-passing, not a peephole.
     */
    private static boolean isBdslFoldableOp(String name) {
        switch (name) {
            case "add": case "sub": case "mul": case "div": case "mod":
            case "lt": case "le": case "gt": case "ge":
            case "and": case "or":
            case "neg": case "not":
                return true;
            default:
                return false;
        }
    }

    private static boolean hasReadField(com.mezze.language.truffleir.NodeIR node) {
        if (node == null) return false;
        if (node instanceof com.mezze.language.truffleir.ReadFieldIR) return true;
        // Walk the common composite shapes. This is intentionally
        // conservative: any node with children needs a recursive check.
        // Kept in sync with the shapes BDSL actually compiles today.
        if (node instanceof com.mezze.language.truffleir.BlockIR blk) {
            for (var s : blk.getStmts()) {
                var child = (s instanceof com.mezze.language.truffleir.StmtIR.Let l)
                        ? l.getValue()
                        : ((com.mezze.language.truffleir.StmtIR.Expr) s).getNode();
                if (hasReadField(child)) return true;
            }
            return hasReadField(blk.getResult());
        }
        if (node instanceof com.mezze.language.truffleir.IfIR i) {
            return hasReadField(i.getCond()) || hasReadField(i.getThenBr()) || hasReadField(i.getElseBr());
        }
        if (node instanceof com.mezze.language.truffleir.WriteLocalIR w) {
            return hasReadField(w.getValue());
        }
        if (node instanceof com.mezze.language.truffleir.InlineBinaryOpIR bin) {
            return hasReadField(bin.getLeft()) || hasReadField(bin.getRight());
        }
        if (node instanceof com.mezze.language.truffleir.CallDirectIR c) {
            for (var arg : c.getArgs()) if (hasReadField(arg)) return true;
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.CallNativeIR cn) {
            for (var arg : cn.getArgs()) if (hasReadField(arg)) return true;
            return hasReadField(cn.getArg());
        }
        if (node instanceof com.mezze.language.truffleir.CallIndirectIR ci) {
            // Ability-method peephole: BDSL folds
            //   CallIndirect(ReadField(x, foldable_op), MakeRecord({...}))
            // into an InlineBinaryOp so the ReadField never runs at
            // runtime. Treat the ReadField in the .function slot as
            // safe for those foldable-op names, mirroring the emit
            // logic in BytecodeCompiler.tryFoldInlineOp.
            var fn = ci.getFunction();
            if (fn instanceof com.mezze.language.truffleir.ReadFieldIR rf
                    && ci.getArg() instanceof com.mezze.language.truffleir.MakeRecordIR
                    && isBdslFoldableOp(rf.getFieldName())) {
                return hasReadField(rf.getRecord()) || hasReadField(ci.getArg());
            }
            return hasReadField(fn) || hasReadField(ci.getArg());
        }
        if (node instanceof com.mezze.language.truffleir.CallLocalIR cl) {
            return hasReadField(cl.getArg());
        }
        if (node instanceof com.mezze.language.truffleir.DictMethodCallIR dm) {
            return hasReadField(dm.getArg());
        }
        if (node instanceof com.mezze.language.truffleir.CallIndirectScalarIR ci) {
            if (hasReadField(ci.getFunction())) return true;
            for (var a : ci.getArgs()) if (hasReadField(a)) return true;
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.PartialApplyDirectIR pa) {
            for (var f : pa.getPartialFields()) if (hasReadField(f.getValue())) return true;
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.PerformOpIR p) {
            return hasReadField(p.getArg());
        }
        if (node instanceof com.mezze.language.truffleir.EffectBindingIR) {
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.MakeRecordIR m) {
            for (var f : m.getFields()) if (hasReadField(f.getValue())) return true;
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.RecordUpdateIR ru) {
            if (hasReadField(ru.getRecord())) return true;
            for (var f : ru.getUpdates()) if (hasReadField(f.getValue())) return true;
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.MakeVariantIR mv) {
            return mv.getPayload() != null && hasReadField(mv.getPayload());
        }
        if (node instanceof com.mezze.language.truffleir.MakeSeqIR seq) {
            for (var el : seq.getElements()) {
                if (el instanceof com.mezze.language.truffleir.SeqElementIR.Regular reg
                        && hasReadField(reg.getNode())) return true;
                if (el instanceof com.mezze.language.truffleir.SeqElementIR.Spread sp
                        && hasReadField(sp.getNode())) return true;
            }
            return false;
        }
        if (node instanceof com.mezze.language.truffleir.MatchIR m) {
            if (hasReadField(m.getScrutinee())) return true;
            for (var c : m.getCases()) {
                if (c.getGuard() != null && hasReadField(c.getGuard())) return true;
                if (hasReadField(c.getBody())) return true;
            }
            return false;
        }
        // Literals, ReadLocal, MakeLambda (captures are slot refs, not NodeIR),
        // Unit — no ReadField reachable.
        return false;
    }

    /** Test/telemetry: how many functions hit BDSL vs fell back to AST. */
    public long getBdslHits() { return bdslHits.get(); }
    public long getBdslMisses() { return bdslMisses.get(); }
    public boolean isBdslEnabled() { return BDSL_ENABLED; }

    /**
     * Public wrapper so Kotlin callers (PipelineRunner) can route their
     * eager per-function compilations through the BDSL fast path.
     */
    public com.oracle.truffle.api.nodes.RootNode compileOneJava(
            com.mezze.language.truffleir.FunctionIR ir) {
        return compileOne(ir);
    }

    /**
     * #374 — compile {@code __entry__} through the BDSL entry path so its
     * top-level {@code let x = ...} statements publish {@code x} to
     * {@link MezzeContext#defineGlobal} + {@link MezzeContext#recordTestInspectionVar}
     * via the {@code RegisterGlobal} op (mirrors the AST
     * {@code WriteLocalWithContextNode} semantics).
     *
     * <p>Returns the entry's {@link com.oracle.truffle.api.CallTarget}, which
     * takes an empty record argument and returns the module body's result.
     * Caller (PipelineRunner) wraps a small adapter around this call to slot
     * back into {@code HashModuleLoaderNode} / {@code createResultCapturingNode}.
     *
     * <p>Falls back to the AST body path when BDSL is disabled or doesn't
     * support the entry IR — returns {@code null} then, and the caller keeps
     * using {@code TruffleCompiler.compileNode(entry.body)}.
     */
    public com.oracle.truffle.api.CallTarget compileEntryFn(
            com.mezze.language.truffleir.FunctionIR entryIR) {
        if (!BDSL_ENABLED) return null;
        com.mezze.interpreter.bytecode.BytecodeCompiler bdsl = getOrCreateBdslCompiler();
        if (!bdsl.supports(entryIR)) return null;
        try {
            com.oracle.truffle.api.nodes.RootNode root = bdsl.compileEntryFn(entryIR);
            return root.getCallTarget();
        } catch (Throwable t) {
            if (System.getenv("MEZZE_DEBUG") != null) {
                System.err.println("[BDSL entry] compile failed for " + entryIR.getName() + ": " + t);
            }
            return null;
        }
    }

    /**
     * #391 Step 3 — eager whole-program BDSL compilation, gated by
     * {@code MEZZE_EAGER=1}. Phase 1: walk reachability from the given
     * roots (via {@link ReachabilityWalker}) and pre-allocate a slot per
     * hash. Phase 2: emit each reachable {@code FunctionIR} through BDSL
     * and publish the resulting {@code RootCallTarget} into its slot.
     *
     * <p>No caller-visible behavior change this step: today's lazy stub /
     * autoInflate paths still run. The slot table is populated in parallel
     * so Step 4 can flip callers to read from it.
     *
     * <p>Emit failures (unsupported IR, transient BDSL crash) are caught
     * and logged when {@code MEZZE_DEBUG} is set — the slot stays
     * unpublished, and today's lazy path fills the gap. Step 4 makes the
     * slot table authoritative and turns these into hard errors.
     */
    public void eagerCompileReachable(
            java.util.Set<Hash> roots,
            java.util.List<com.mezze.language.truffleir.FunctionIR> userFunctions) {
        if ("0".equals(System.getenv("MEZZE_EAGER"))) return;
        if (roots.isEmpty()) return;
        // Build a lookup that prefers the fresh user-function list, then
        // falls back to the language's consolidated lookupFunctionIR (per-
        // context user IR → stdlib pre-lowered → runtime store).
        java.util.Map<Hash, com.mezze.language.truffleir.FunctionIR> byHash =
                new java.util.HashMap<>();
        for (var fn : userFunctions) byHash.put(fn.getHash(), fn);
        kotlin.jvm.functions.Function1<Hash, com.mezze.language.truffleir.FunctionIR> lookup =
                h -> {
                    var ir = byHash.get(h);
                    if (ir != null) return ir;
                    return lookupFunctionIR(h);
                };
        java.util.Set<Hash> missing = new java.util.LinkedHashSet<>();
        com.mezze.interpreter.runtime.ReachabilityWalker.MissingHashLogger missLogger =
                missing::add;
        java.util.Set<Hash> reachable =
                com.mezze.interpreter.runtime.ReachabilityWalker.INSTANCE
                        .walk(roots, lookup, missLogger);
        // Phase 1 — allocate every slot up front so Phase 2 emit can
        // resolve cross-refs via slot lookup.
        for (Hash h : reachable) slotTable.allocate(h);
        // Phase 2 — emit each function through BDSL, publish its CT.
        com.mezze.interpreter.bytecode.BytecodeCompiler bdsl = getOrCreateBdslCompiler();
        int emitted = 0, skippedUnsupported = 0, skippedMissing = 0, failed = 0;
        for (Hash h : reachable) {
            SlotEntry slot = slotTable.get(h);
            if (slot == null || slot.isPublished()) continue;
            var ir = lookup.invoke(h);
            if (ir == null) { skippedMissing++; continue; }
            if (!bdsl.supports(ir)) { skippedUnsupported++; continue; }
            try {
                com.oracle.truffle.api.nodes.RootNode root = bdsl.compile(ir);
                slot.publish((com.oracle.truffle.api.RootCallTarget) root.getCallTarget());
                emitted++;
            } catch (Throwable t) {
                failed++;
                if (System.getenv("MEZZE_DEBUG") != null) {
                    System.err.println("[eager #391] emit failed for "
                            + ir.getName() + " " + h.getHex() + ": " + t);
                }
            }
        }
        if (System.getenv("MEZZE_DEBUG") != null || "1".equals(System.getenv("MEZZE_EAGER_STATS"))) {
            long mlTotal = com.mezze.interpreter.bytecode.BytecodeCompiler.getMakeLambdaTotal();
            long mlCless = com.mezze.interpreter.bytecode.BytecodeCompiler.getMakeLambdaClosureless();
            long mlPct = mlTotal == 0 ? 0 : (mlCless * 100 / mlTotal);
            long cbFallbacks = com.mezze.interpreter.bytecode.BytecodeCompiler.getEagerClosureBearingFallbacks();
            System.err.println("[eager #391] reachable=" + reachable.size()
                    + " emitted=" + emitted
                    + " skippedUnsupported=" + skippedUnsupported
                    + " skippedMissing=" + (skippedMissing + missing.size())
                    + " failed=" + failed
                    + " slotTable=" + slotTable.size()
                    + " makeLambda closureless=" + mlCless + "/" + mlTotal
                    + " (" + mlPct + "%)"
                    + " closureBearingFallbacks=" + cbFallbacks);
        }
    }

    /**
     * Optional content-addressed store for resolving stdlib (and eventually
     * remote) hashes at runtime. When set, `getCachedStdlibCallTarget`
     * checks the store before falling back to PrecompiledStdlib's
     * in-memory map. The seam for hash-based remote fetching (#169):
     * point this at a `ChainedMezzeStore(local, remote)` and missing
     * hashes get fetched transparently.
     */
    private static volatile com.mezze.language.store.MezzeStore runtimeStore = null;

    public static void setRuntimeStore(com.mezze.language.store.MezzeStore store) {
        runtimeStore = store;
    }

    public static com.mezze.language.store.MezzeStore getRuntimeStore() {
        return runtimeStore;
    }

    /** Expose cache for FunctionDefinitionNode to store lambda CallTargets by content hash. */
    public java.util.concurrent.ConcurrentHashMap<Hash, CallTarget> getCachedStdlibCallTargetCache() {
        return stdlibCallTargetCache;
    }
    private volatile java.util.Map<Hash, String> stdlibHashToName = null;
    private volatile java.util.List<String[]> stdlibPerformerRecords = null;

    /** Register a user FunctionIR for lazy compilation (per-context). */
    public void registerFunctionIR(Hash hash, com.mezze.language.truffleir.FunctionIR ir) {
        getCurrentContext().registerUserFunctionIR(hash, ir);
    }

    /**
     * Look up a FunctionIR by content hash. Searches per-context user
     * registry, then stdlib pre-lowered map, then runtime store.
     * Returns null if not found. Used by BDSL emit to inspect callee
     * metadata (e.g. paramNames for {@code MakeLambda}).
     */
    public com.mezze.language.truffleir.FunctionIR lookupFunctionIR(Hash hash) {
        MezzeContext ctx;
        try { ctx = getCurrentContext(); } catch (Exception e) { ctx = null; }
        if (ctx != null) {
            var ir = ctx.getUserFunctionIR(hash);
            if (ir != null) return ir;
        }
        var stdlibIr = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE
                .getPreloweredFunctions().get(hash);
        if (stdlibIr != null) return stdlibIr;
        var store = runtimeStore;
        if (store != null) {
            byte[] bytes = store.getIR(hash);
            if (bytes != null) {
                try {
                    return com.mezze.language.store.StoreSerialization.INSTANCE
                            .deserializeFunctionIR(bytes);
                } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    // #304 — per-hash lazy stubs. Minted on demand by BDSL emit when a
    // callee CallTarget isn't yet cached; the stub itself is stable
    // (baked as ConstantOperand into BDSL bytecode) and lazily inflates
    // on first call via LazyStubRootNode.inflate.
    private final java.util.concurrent.ConcurrentHashMap<Hash, CallTarget> userStubCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Return the shared lazy-inflating stub CallTarget for {@code hash}
     * (mint on first request). The stub inflates on first execute via
     * {@link #compileUserIRForStub}; callers get stable identity for
     * emit-time constant baking. Returns null if the hash is not
     * registered in any known IR source (per-context user registry,
     * PrecompiledStdlib, or runtime MezzeStore).
     */
    public CallTarget getOrMintUserStub(Hash hash, String name) {
        CallTarget existing = userStubCache.get(hash);
        if (existing != null) return existing;
        // Confirm the hash is at least resolvable before minting a stub —
        // returning a dud stub would defer the "not found" error to a
        // confusing runtime IllegalStateException.
        if (!canResolveIR(hash)) return null;
        return userStubCache.computeIfAbsent(hash, h ->
            new com.mezze.interpreter.bytecode.LazyStubRootNode(this, h, name).getCallTarget());
    }

    private boolean canResolveIR(Hash hash) {
        MezzeContext ctx;
        try { ctx = getCurrentContext(); } catch (Exception e) { ctx = null; }
        if (ctx != null && ctx.getUserFunctionIR(hash) != null) return true;
        if (com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE
                .getPreloweredFunctions().containsKey(hash)) return true;
        var store = runtimeStore;
        if (store != null && store.getIR(hash) != null) return true;
        return false;
    }

    /**
     * BDSL compile of a user/stdlib IR for lazy-stub inflation. Uses a
     * FRESH {@link com.mezze.interpreter.bytecode.BytecodeCompiler}
     * instance (not the shared singleton), so it doesn't re-enter a
     * mid-emit compiler. Cross-fn refs resolve via SlotTable-first
     * external resolver, falling back to the existing user-CT cache /
     * stdlib CT cache — mirrors the pattern used by callers of the
     * singleton in {@link #compileOne}.
     *
     * Falls back to AST compilation via {@link com.mezze.interpreter.compiler.TruffleCompiler}
     * if BDSL {@link com.mezze.interpreter.bytecode.BytecodeCompiler#supports}
     * rejects the IR — needed until every IR shape is BDSL-supported.
     */
    public com.oracle.truffle.api.nodes.RootNode compileUserIRForStub(Hash hash) {
        MezzeContext ctx;
        try { ctx = getCurrentContext(); } catch (Exception e) { ctx = null; }
        com.mezze.language.truffleir.FunctionIR ir = null;
        if (ctx != null) ir = ctx.getUserFunctionIR(hash);
        if (ir == null) {
            ir = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE
                .getPreloweredFunctions().get(hash);
        }
        if (ir == null) {
            var store = runtimeStore;
            if (store != null) {
                byte[] bytes = store.getIR(hash);
                if (bytes != null) {
                    try {
                        ir = com.mezze.language.store.StoreSerialization.INSTANCE
                                .deserializeFunctionIR(bytes);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        if (ir == null) return null;
        // Try BDSL first with a fresh instance (avoids singleton re-entry).
        // Resolver: prefer SlotTable-published CTs, then user CT cache,
        // then stdlib CT cache — matches the compileOne resolver order.
        final MezzeContext ctxFinal = ctx;
        java.util.function.Function<Hash, com.oracle.truffle.api.CallTarget> resolver = h -> {
            SlotEntry slot = slotTable.get(h);
            if (slot != null && slot.isPublished()) return slot.getCallTarget();
            if (ctxFinal != null) {
                var userCT = ctxFinal.getUserCallTarget(h);
                if (userCT != null) return userCT;
            }
            return getCachedStdlibCallTarget(h);
        };
        var bdsl = new com.mezze.interpreter.bytecode.BytecodeCompiler(this, resolver);
        // Force telemetry so LAST_REJECTION captures the reason even without MEZZE_BDSL_STATS.
        System.setProperty("mezze.bdsl.rejection.probe", "1");
        boolean supported = bdsl.supports(ir);
        if (supported) {
            try {
                return bdsl.compile(ir);
            } catch (Throwable t) {
                throw new IllegalStateException(
                    "BDSL rejected IR for hash " + hash + " name=" + ir.getName()
                    + " — emit crash: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }
        var counts = com.mezze.interpreter.bytecode.BytecodeCompiler.getRejectionCounts();
        String lastRej = counts.isEmpty() ? "<no telemetry — set MEZZE_BDSL_STATS=1>"
            : counts.keySet().iterator().next();
        throw new IllegalStateException(
            "BDSL rejected IR for hash " + hash + " name=" + ir.getName()
            + " — rejection shape: " + lastRej);
    }

    /**
     * Get a cached CallTarget by hash — checks stdlib cache first, then compiles user IR on demand.
     *
     * Resolution order:
     *   1. CallTarget cache (already compiled stdlib hash)
     *   2. Per-context user CallTarget cache
     *   3. Per-context user FunctionIR (compile on demand)
     *   4. PrecompiledStdlib in-memory map (the JVM warm path)
     *   5. Runtime MezzeStore (#169: the seam for hash-based remote fetching —
     *      a ChainedStore(local, remote) makes step 5 transparently network)
     */
    public CallTarget getCachedStdlibCallTarget(Hash hash) {
        // 1. Check shared stdlib cache
        CallTarget ct = stdlibCallTargetCache.get(hash);
        if (ct != null) return ct;

        // 2. Check per-context user functions (compile on demand)
        MezzeContext ctx;
        try { ctx = getCurrentContext(); } catch (Exception e) { ctx = null; }
        if (ctx != null) {
            ct = ctx.getUserCallTarget(hash);
            if (ct != null) return ct;
            var ir = ctx.getUserFunctionIR(hash);
            if (ir != null) {
                var rootNode = compileOne(ir);
                ct = rootNode.getCallTarget();
                ctx.cacheUserCallTarget(hash, ct);
                return ct;
            }
        }

        // 3. Compile from stdlib FunctionIR on demand (in-memory map)
        var stdlibIR = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE.getPreloweredFunctions().get(hash);
        if (stdlibIR != null) {
            var rootNode = compileOne(stdlibIR);
            ct = rootNode.getCallTarget();
            stdlibCallTargetCache.put(hash, ct);
            return ct;
        }

        // 4. Last resort: ask the runtime MezzeStore. Used by standalone JAR
        //    when blobs.sqlite holds hashes that PrecompiledStdlib's snapshot
        //    doesn't (impl-op IRs not registered in the snapshot, or — in
        //    the future — hashes fetched from a remote registry by a
        //    ChainedStore wrapper).
        var store = runtimeStore;
        if (store != null) {
            byte[] irBytes = store.getIR(hash);
            if (irBytes != null) {
                try {
                    var fnIR = com.mezze.language.store.StoreSerialization.INSTANCE.deserializeFunctionIR(irBytes);
                    var rootNode = compileOne(fnIR);
                    ct = rootNode.getCallTarget();
                    stdlibCallTargetCache.put(hash, ct);
                    return ct;
                } catch (Exception e) {
                    // Fall through — caller will throw the standard
                    // "Function not found" error with full context.
                }
            }
        }
        return null;
    }

    /**
     * Compile referenced stdlib functions into the shared cache.
     * Only compiles functions not already cached. Idempotent.
     */
    public void compileReferencedStdlib(
            java.util.Set<Hash> referencedHashes,
            java.util.Set<Hash> userHashes) {
        var runtimeData = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE;
        var allFunctions = runtimeData.getPreloweredFunctions();

        // Build name map + performer records on first call.
        if (stdlibHashToName == null) {
            var nameMap = new java.util.HashMap<Hash, String>();
            for (var entry : allFunctions.entrySet()) {
                var fn = entry.getValue();
                if (!fn.getName().equals("lambda") && !fn.getName().startsWith("__")) {
                    nameMap.put(entry.getKey(), fn.getName());
                }
            }
            stdlibHashToName = nameMap;
            stdlibPerformerRecords = runtimeData.getPreloweredPerformerRecords();
        }

        // Compile only referenced stdlib functions not yet cached
        for (Hash hash : referencedHashes) {
            if (!userHashes.contains(hash) && !stdlibCallTargetCache.containsKey(hash)) {
                var ir = allFunctions.get(hash);
                if (ir != null) {
                    var rootNode = compileOne(ir);
                    stdlibCallTargetCache.put(hash, rootNode.getCallTarget());
                }
            }
        }
    }

    /** Get stdlib hash→name map (available after ensureStdlibCompiled). */
    public java.util.Map<Hash, String> getStdlibHashToName() {
        return stdlibHashToName != null ? stdlibHashToName : java.util.Collections.emptyMap();
    }

    /** Get stdlib performer records (available after ensureStdlibCompiled). */
    public java.util.List<String[]> getStdlibPerformerRecords() {
        return stdlibPerformerRecords != null ? stdlibPerformerRecords : java.util.Collections.emptyList();
    }

    // Cache for imported module CallTargets (non-entry, non-stdlib functions)
    private final java.util.concurrent.ConcurrentHashMap<Hash, CallTarget> importedCallTargetCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public CallTarget getCachedCallTarget(Hash hash) {
        return importedCallTargetCache.get(hash);
    }

    public void cacheCallTarget(Hash hash, CallTarget ct) {
        importedCallTargetCache.put(hash, ct);
    }

    /**
     * Evict CallTargets for the given hashes from all Language-level caches.
     * Stdlib hashes never change in a JVM lifetime (content-addressed), so
     * they're typically absent — caller decides.
     */
    public void invalidateHashes(java.util.Collection<Hash> hashes) {
        for (Hash h : hashes) {
            importedCallTargetCache.remove(h);
            stdlibCallTargetCache.remove(h);
        }
    }

    /**
     * DevRunner sets this before each watch reload — parse() picks it up and
     * calls invalidateHashes() before compiling the fresh user FunctionIRs.
     * Thread-local because parse() runs on whatever thread Polyglot picks.
     */
    private static final ThreadLocal<java.util.Collection<Hash>> hashesToInvalidate = new ThreadLocal<>();

    public static void setHashesToInvalidate(java.util.Collection<Hash> hashes) {
        hashesToInvalidate.set(hashes);
    }

    // ThreadLocal for direct TruffleIR execution (bypasses parsing)
    private static final ThreadLocal<List<FunctionIR>> directExecutionFunctions = new ThreadLocal<>();
    private static final ThreadLocal<List<String[]>> directExecutionPerformers = new ThreadLocal<>();
    private static final ThreadLocal<Path> directExecutionRoot = new ThreadLocal<>();
    // Entry-point identity: the entry function's content-hash hex. Selection
    // at runtime keys on this hash, NEVER on a name. (D1/D2.)
    private static final ThreadLocal<String> directExecutionEntryHash = new ThreadLocal<>();

    @Override
    protected MezzeContext createContext(Env env) {
        return new MezzeContext(this, env);
    }

    @Override
    protected void initializeMultipleContexts() {
        // Called by Truffle when SHARED policy is active.
        // Our caches are already thread-safe ConcurrentHashMaps — nothing extra needed.
    }

    /**
     * Truffle calls this when a thread leaves this context (Context.close
     * or the thread's implicit leave). STM state is per-thread but stored
     * in static ThreadLocals — without cleanup, ThreadLocal residue from
     * one Context leaks into the next Context that reuses the same JVM
     * thread. #380: an interrupted STM txn leaves nesting-depth &gt; 0, and
     * the next Context's stmBegin then no-ops itself as "nested" while
     * writes silently drop. Reset on leave, always.
     */
    @Override
    protected void disposeThread(MezzeContext context, Thread thread) {
        com.mezze.interpreter.nodes.builtin.concurrency.ConcurrencyNatives
                .resetPerThreadStmState();
    }

    /**
     * Belt-and-braces: also clear on context close for the calling
     * thread. Truffle guarantees disposeThread is called for every
     * thread that entered, but this is a cheap idempotent op and covers
     * embedders that call close() without unwinding an aborted txn.
     */
    @Override
    protected void disposeContext(MezzeContext context) {
        com.mezze.interpreter.nodes.builtin.concurrency.ConcurrencyNatives
                .resetPerThreadStmState();
        // #392 — SlotTable is per-Engine but its entries are populated by
        // per-Context eagerCompileReachable, so stale entries from a
        // disposed Context can leak into the next Context sharing this
        // Language (e.g. the test suite's sharedEngine). Clear on
        // disposeContext to keep eager slots Context-scoped in effect,
        // while still amortizing the SlotTable Map allocation.
        if (slotTable != null) slotTable.clear();
    }

    /**
     * Inline eval in a suspended frame — the debugger's watch / conditional
     * breakpoint / logpoint path (DebugStackFrame.eval → parse(InlineParsingRequest)).
     * The regular parse() compiles a whole standalone module and can't see the
     * frame's locals, so `a` there is "Undefined variable". Here we resolve the
     * expression against the FRAME'S named slots.
     *
     * We handle the expressions watch/conditions actually use: a bare identifier
     * (`a`), and a binary form `<lhs> <op> <rhs>` where each side is an
     * identifier or an integer literal, for the comparison + arithmetic ops.
     * Returning null (unrecognized shape) falls through to the module parse
     * (which still handles literals + globals), so nothing regresses.
     */
    @Override
    protected ExecutableNode parse(InlineParsingRequest request) throws Exception {
        String expr = request.getSource().getCharacters().toString().trim();
        FrameSlotResolver.Expr parsed = FrameSlotResolver.parse(expr);
        if (parsed == null) return null;
        return new FrameEvalNode(this, parsed);
    }

    /** Read locals from the frame + evaluate a small watch/condition expr.
     *  #359 — under BDSL, local + param shadow names live in the
     *  BytecodeNode's local table, not the FrameDescriptor. Resolve the
     *  BytecodeNode at eval time (via BytecodeNode.get walking the parent
     *  chain from this ExecutableNode's insertion point) and pass it to
     *  the resolver so it can search both sources. */
    private static final class FrameEvalNode extends ExecutableNode {
        private final FrameSlotResolver.Expr expr;
        FrameEvalNode(MezzeLanguage lang, FrameSlotResolver.Expr expr) {
            super(lang);
            this.expr = expr;
        }
        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            // #359 — the debugger's inline eval passes us the SUSPENDED
            // frame (materialized). We need the BytecodeNode of the
            // suspended-in fn, plus the bci at the suspension point.
            //
            // Truffle's iterateFrames walks the actual runtime stack
            // (bypassing our own ExecutableNode wrapper). The topmost
            // Mezze BDSL frame is where the debugger stopped — that's
            // where we look up locals + params.
            // The debugger adopts our ExecutableNode under the suspended
            // RootNode. When that RootNode is a MezzeBytecodeRootNode
            // (BDSL), we get its BytecodeNode via BytecodeRootNode's
            // getBytecodeNode() — BytecodeNode.get(Node) doesn't walk
            // to the root, so we have to reach through the interface
            // explicitly.
            // The debugger's DebugStackFrame.eval hands us the paused
            // frame. Our ExecutableNode is adopted under the debug
            // frame's RootNode — for BDSL that's a MezzeBytecodeRootNode
            // subclass. Find its BytecodeNode via the BytecodeRootNode
            // interface, then look up the bci from the frame's stored
            // slot (storeBytecodeIndexInFrame is enabled).
            //
            // getRootNode() on THIS gives the ExecutableNode's parent
            // root. If that's a bytecode root, its getBytecodeNode()
            // returns the executable bytecode instance. Note: earlier
            // iterateFrames didn't work because the debug inline-eval
            // doesn't put a real Mezze frame on the runtime stack —
            // the paused frame is passed as our arg.
            // #359 — resolve the BytecodeNode of the enclosing BDSL
            // root. getBytecodeIndex(frame) reads the bci from a
            // frame slot (storeBytecodeIndexInFrame=true stores it
            // at safe points), but the debugger-materialized frame
            // may not populate that slot — pass bci=-1 to
            // FrameSlotResolver, which then reads locals via the
            // static getLocals() list and each local's own liveness
            // start bci (invariant within a scope).
            com.oracle.truffle.api.bytecode.BytecodeNode bn = null;
            int bci = -1;
            com.oracle.truffle.api.nodes.RootNode rn = getRootNode();
            if (rn instanceof com.oracle.truffle.api.bytecode.BytecodeRootNode brn) {
                bn = brn.getBytecodeNode();
                if (bn != null) {
                    try {
                        bci = bn.getBytecodeIndex(frame);
                    } catch (Throwable t) {
                        bci = -1; // no valid bci; FrameSlotResolver falls
                                  // back to the local's own startIndex
                    }
                }
                // Sub-root fallback: our ExecutableNode may be adopted
                // under a wrapper root that doesn't hold the executing
                // bytecode. Walk the RootNodes collection and try each.
                if (bn == null) {
                    try {
                        var roots = brn.getRootNodes();
                        if (roots != null) {
                            for (int i = 0; i < roots.count(); i++) {
                                var r2 = roots.getNode(i);
                                if (r2 instanceof com.oracle.truffle.api.bytecode.BytecodeRootNode brn2) {
                                    var candidate = brn2.getBytecodeNode();
                                    if (candidate == null) continue;
                                    int localBci;
                                    try {
                                        localBci = candidate.getBytecodeIndex(frame);
                                    } catch (Throwable t) { localBci = 0; }
                                    if (localBci >= 0) {
                                        bn = candidate;
                                        bci = localBci;
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            final com.oracle.truffle.api.frame.Frame useFrame = frame;
            return FrameSlotResolver.evaluate(expr, useFrame, bn, bci);
        }
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        Source source = request.getSource();
        String sourceCode = source.getCharacters().toString();

        // Watch-mode targeted invalidation: DevRunner has computed the set of
        // hashes whose content changed since last reload. Evict only those.
        java.util.Collection<Hash> changed = hashesToInvalidate.get();
        if (changed != null) {
            hashesToInvalidate.remove();
            invalidateHashes(changed);
        }

        // Clear per-context user function state — each test gets fresh user functions.
        // Stdlib cache (Language-level) is NOT cleared — it persists across all Contexts.
        var ctx = getCurrentContext();
        if (ctx == null) {
            throw new RuntimeException("MezzeLanguage.parse() called without a context");
        }
        ctx.clearUserState();
        // #304 — user-hash stubs may hold CallTargets from a prior test's
        // context. Clear them alongside per-context user state so the next
        // test starts fresh.
        userStubCache.clear();
        // The BDSL compiler's per-instance compiledCallTargets cache also
        // holds the stub CTs from prior emits; evict them too so a stale
        // stub can't be resolved on the next test's supports() check.
        var bc = bdslCompiler;
        if (bc != null) bc.invalidateStubs();

        // Check for direct TruffleIR execution (from tests or CLI)
        List<FunctionIR> directFunctions = directExecutionFunctions.get();
        if (directFunctions != null) {
            directExecutionFunctions.remove();
            List<String[]> performers = directExecutionPerformers.get();
            directExecutionPerformers.remove();
            Path projectRoot = directExecutionRoot.get();
            directExecutionRoot.remove();
            String entryHashHex = directExecutionEntryHash.get();
            directExecutionEntryHash.remove();
            if (projectRoot == null) {
                projectRoot = Paths.get(System.getProperty("mezze.project.root", "."));
            }

            com.mezze.language.typedast.Hash entryHash =
                entryHashHex != null ? new com.mezze.language.typedast.Hash(entryHashHex) : null;

            // All modules are already included in the FunctionIR list — no import extraction needed
            return com.mezze.interpreter.compiler.PipelineRunner.compileFromIR(
                this, directFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList(),
                entryHash
            );
        }

        // Determine project root
        Path projectRoot;
        String projectRootProp = System.getProperty("mezze.project.root");
        if (projectRootProp != null) {
            projectRoot = Paths.get(projectRootProp);
        } else if (source.getPath() != null) {
            Path sourcePath = Paths.get(source.getPath());
            projectRoot = sourcePath.getParent() != null ? sourcePath.getParent() : sourcePath;
        } else {
            projectRoot = Paths.get(".");
        }

        // Parse and typecheck via context-owned instances (proper isolation,
        // native-image safe). Reflective so the slim runtime jar (without
        // :parser/:typechecker) class-loads fine; this path only fires when
        // a host calls context.eval() on source (tests, embedders) — the
        // wire-protocol path took the `directExecutionFunctions` branch above.
        var parsedModule = invoke(ctx.getParser(), "parseParsedModule",
            new Class<?>[]{String.class, String.class},
            sourceCode, source.getPath() != null ? source.getPath() : "<unknown>");
        var typeCheckResult = invoke(ctx.getTypeChecker(), "typeCheck",
            new Class<?>[]{Class.forName("com.mezze.language.parsedast.ParsedModule"), Path.class, boolean.class, String.class},
            parsedModule, projectRoot, true, null);

        // TypeCheckResult is a Kotlin class — use reflection to fetch the
        // getter outputs without compile-time refs to :typechecker types.
        @SuppressWarnings("unchecked")
        java.util.List<FunctionIR> loweredFunctions =
            (java.util.List<FunctionIR>) typeCheckResult.getClass().getMethod("getLoweredFunctions").invoke(typeCheckResult);
        if (loweredFunctions != null && !loweredFunctions.isEmpty()) {
            @SuppressWarnings("unchecked")
            java.util.List<String[]> performers =
                (java.util.List<String[]>) typeCheckResult.getClass().getMethod("getPerformerRecords").invoke(typeCheckResult);
            // Provide the eval Source's real text keyed by its file path so the
            // compiler's Truffle Sources (and their sections) match what a
            // debugger client set breakpoints against — the FunctionIRs were
            // parsed with this same path as their sourceFile.
            String srcFile = source.getPath() != null ? source.getPath() : "<unknown>";
            java.util.Map<String, String> sourceOverrides =
                java.util.Collections.singletonMap(srcFile, sourceCode);
            return com.mezze.interpreter.compiler.PipelineRunner.compileFromIR(
                this, loweredFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList(),
                (com.mezze.language.typedast.Hash) null, sourceOverrides
            );
        }

        Object typedModule = typeCheckResult.getClass().getMethod("getTypedModule").invoke(typeCheckResult);
        throw new RuntimeException("TruffleIR not produced by typechecker. loweredFunctions is " +
            (loweredFunctions == null ? "null" : "empty (" + loweredFunctions.size() + ")") +
            ". typedModule is " + (typedModule == null ? "null" : "present"));
    }

    /** Reflectively call a method, unwrapping InvocationTargetException. */
    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object... args) {
        try {
            return target.getClass().getMethod(name, paramTypes).invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Surface the underlying exception verbatim — parser/typechecker
            // throws are user-meaningful (ParseException, TypeCheckError).
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke " + name + " on " + target.getClass().getName(), e);
        }
    }

    /**
     * Create a ResultCapturingNode that stores the execution result.
     * Used by PipelineRunner.
     */
    public static MezzeNode createResultCapturingNode(MezzeNode bodyNode) {
        return new ResultCapturingNode(bodyNode);
    }

    /**
     * Node that captures the result for test access.
     */
    private static final class ResultCapturingNode extends MezzeNode {
        @Child private MezzeNode bodyNode;

        ResultCapturingNode(MezzeNode bodyNode) {
            this.bodyNode = bodyNode;
        }

        @Child private com.oracle.truffle.api.nodes.IndirectCallNode forceCallNode;

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object result = bodyNode.execute(frame);
            // Force suspended computations — effects should have been handled
            // by the time we reach the top-level result
            if (result instanceof com.mezze.interpreter.values.MezzeSuspend suspended) {
                result = forceSuspended(suspended);
            }
            lastResultStore.set(result);
            captureGlobalContext();
            return MezzeUnit.INSTANCE;
        }

        @CompilerDirectives.TruffleBoundary
        private Object forceSuspended(com.mezze.interpreter.values.MezzeSuspend suspended) {
            try {
                if (forceCallNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    forceCallNode = insert(com.oracle.truffle.api.nodes.IndirectCallNode.create());
                }
                // Under the new dispatch design, forcing routes through the
                // current fiber rather than a context-wide handler map. At the
                // top level no fiber is current, so force() will typically
                // return the suspension unchanged — which is fine, the user
                // sees the suspension explicitly.
                Object forced = suspended.force(forceCallNode);
                if (!(forced instanceof com.mezze.interpreter.values.MezzeSuspend)) {
                    return forced;
                }
            } catch (Exception e) {
                // Force failed — return suspended as-is
            }
            return suspended;
        }

        @CompilerDirectives.TruffleBoundary
        private void captureGlobalContext() {
            // Capture all global variables registered by WriteLocalWithContextNode.
            // This enables tests using runAndGetContext() to read variable values.
            try {
                MezzeContext context = MezzeContext.get(this);
                Map<String, Object> testVars = context.getTestInspectionVars();
                if (testVars != null && !testVars.isEmpty()) {
                    lastContextStore.set(testVars);
                }
            } catch (Exception e) {
                // No context available
            }
        }
    }

    /**
     * Get the language instance from a node.
     */
    public static MezzeLanguage get(Node node) {
        return REFERENCE.get(node);
    }

    /**
     * Get the current context for the current thread.
     */
    public MezzeContext getCurrentContext() {
        return CONTEXT_REFERENCE.get(null);
    }

    /**
     * Set pre-lowered TruffleIR functions for direct execution.
     * Used by CLI and test helpers to bypass parsing — all modules are
     * already included in the FunctionIR list via monomorphization.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, Path projectRoot) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(null);
        directExecutionRoot.set(projectRoot);
    }

    /**
     * Set pre-lowered TruffleIR functions AND performer records for direct execution.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, List<String[]> performers, Path projectRoot) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(performers);
        directExecutionRoot.set(projectRoot);
        directExecutionEntryHash.set(null);
    }

    /**
     * Set pre-lowered TruffleIR functions with explicit entry-point identity.
     * Used by mez dev / exec / embedded apps — the entry function is selected
     * by its content hash (NOT its name) and called automatically.
     *
     * @param entryHash the entry function's content-hash hex (== entry
     *                  FunctionIR.hash), or null to fall back to __entry__.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, List<String[]> performers, Path projectRoot, String entryHash) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(performers);
        directExecutionRoot.set(projectRoot);
        directExecutionEntryHash.set(entryHash);
    }

    /**
     * Get the last execution result.
     * Used by test helpers.
     */
    public static Object getLastExecutionResult() {
        return lastResultStore.get();
    }

    /**
     * Clear the last execution result and any accumulated state.
     */
    public static void clearLastResult() {
        lastResultStore.set(null);
    }

    /**
     * Get the last execution context (variable bindings).
     * Used by test helpers to inspect variable values after execution.
     */
    public static Map<String, Object> getLastExecutionContext() {
        Map<String, Object> context = lastContextStore.get();
        return context != null ? context : Collections.emptyMap();
    }

    /**
     * Clear the last execution context.
     */
    public static void clearLastContext() {
        lastContextStore.set(null);
    }

    /**
     * Enable multi-threaded access for concurrency support.
     * Required for Loom virtual threads and parallel execution.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // Allow all threads to access this language
        return true;
    }

    private static final LanguageReference<MezzeLanguage> REFERENCE =
        LanguageReference.create(MezzeLanguage.class);

    private static final ContextReference<MezzeContext> CONTEXT_REFERENCE =
        ContextReference.create(MezzeLanguage.class);
}
