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
    StandardTags.WriteVariableTag.class
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

    // BDSL routing (#284) — opt-in via MEZZE_BDSL env var. When on, every
    // per-function compile tries BytecodeCompiler first; failure ⇒ falls back
    // to TruffleCompiler. Off by default so existing paths are unaffected.
    private static final boolean BDSL_ENABLED =
            System.getenv("MEZZE_BDSL") != null
                && !"0".equals(System.getenv("MEZZE_BDSL"))
                && !"false".equalsIgnoreCase(System.getenv("MEZZE_BDSL"));

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

    public static void enableBdslDebugInstrumentation() {
        BDSL_DEBUG_INSTRUMENTATION = true;
    }

    public static boolean isBdslDebugInstrumentation() {
        return BDSL_DEBUG_INSTRUMENTATION;
    }

    // CPS routing (Path C, docs/perf/bdsl-effects-path-c-2026-07.md) — opt-in
    // via MEZZE_CPS env var. When on, CpsLowering runs on every FunctionIR
    // before Truffle/BDSL compilation and effect-related fibers pick the CPS
    // backend. Off by default until Phase 3 when we flip the default and
    // delete the Loom Continuation path.
    public static final boolean CPS_ENABLED =
            System.getenv("MEZZE_CPS") != null
                && !"0".equals(System.getenv("MEZZE_CPS"))
                && !"false".equalsIgnoreCase(System.getenv("MEZZE_CPS"));

    // Path D routing (docs/perf/bdsl-effects-path-d-2026-07.md) — opt-in
    // via MEZZE_BDSL_YIELD env var. When on, BytecodeCompiler emits a BDSL
    // Yield op at every PerformOpIR site (bytecode-native suspend), and the
    // driver catches ContinuationResult on return. Requires MEZZE_BDSL=1
    // to have any effect. Off by default; will flip in Phase D5.
    public static final boolean BDSL_YIELD_ENABLED =
            System.getenv("MEZZE_BDSL_YIELD") != null
                && !"0".equals(System.getenv("MEZZE_BDSL_YIELD"))
                && !"false".equalsIgnoreCase(System.getenv("MEZZE_BDSL_YIELD"));

    // Path E — lazy-until-fully-discharged effect runtime (task #336).
    // When on, HandleNode's lazy path builds a MezzeSuspended carrying the
    // whole body continuation (bodyNode + frame + chain snapshot) instead of
    // eagerly running the body and losing the continuation at first
    // unhandled op. Force is atomic — merges chains, runs body inline on
    // caller thread, no fiber. Independent of BDSL flags: the Path E runtime
    // is language-level, not codegen-level.
    //
    // Phase 4 (2026-07-23) flipped default ON. Opt-out via MEZZE_LAZY_EFFECTS=0
    // or MEZZE_LEGACY_SUSPENDED=1 for one release cycle while any surprising
    // interactions are debugged. Full test matrix at flip time:
    //   - baseline / no gates:                5302/5302 pass
    //   - MEZZE_LAZY_EFFECTS=1 alone:         5302/5302 pass (Path E on AST path)
    //   - MEZZE_BDSL + MEZZE_BDSL_YIELD:      5299/5302 (D8 known-3 unchanged; BDSL codegen bypasses HandleNode)
    //   - all three gates:                    5299/5302 (Path E orthogonal to BDSL D8)
    public static final boolean LAZY_EFFECTS_ENABLED = computeLazyEffectsDefault();

    private static boolean computeLazyEffectsDefault() {
        // Explicit off wins: MEZZE_LAZY_EFFECTS=0/false OR MEZZE_LEGACY_SUSPENDED=1.
        String off = System.getenv("MEZZE_LAZY_EFFECTS");
        if ("0".equals(off) || "false".equalsIgnoreCase(off)) return false;
        String legacy = System.getenv("MEZZE_LEGACY_SUSPENDED");
        if (legacy != null && !"0".equals(legacy) && !"false".equalsIgnoreCase(legacy)) return false;
        // Default: on.
        return true;
    }
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
                }
            }, "bdsl-stats"));
        }
    }
    private volatile com.mezze.interpreter.bytecode.BytecodeCompiler bdslCompiler;
    private final java.util.concurrent.atomic.AtomicLong bdslHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong bdslMisses = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Compile one FunctionIR. When {@link #BDSL_ENABLED} is on, tries BDSL
     * first ({@link com.mezze.interpreter.bytecode.BytecodeCompiler#supports})
     * and falls back to the AST TruffleCompiler when unsupported. Off by
     * default — the four legacy call sites in this file still invoke
     * {@code TruffleCompiler.compile} directly through this helper.
     *
     * The BDSL compiler shares an external CallTarget resolver that looks
     * into {@link #stdlibCallTargetCache} — so cross-fn calls to already-
     * compiled targets resolve without duplicating them in BDSL's own map.
     */
    private com.oracle.truffle.api.nodes.RootNode compileOne(
            com.mezze.interpreter.compiler.TruffleCompiler astCompiler,
            com.mezze.language.truffleir.FunctionIR ir) {
        if (BDSL_ENABLED) {
            if (!isBdslSafeCallee(ir)) {
                bdslMisses.incrementAndGet();
                if (BDSL_STATS) GLOBAL_BDSL_MISS_UNSAFE.incrementAndGet();
                return astCompiler.compile(ir);
            }
            com.mezze.interpreter.bytecode.BytecodeCompiler bdsl = getOrCreateBdslCompiler();
            if (!bdsl.supports(ir)) {
                bdslMisses.incrementAndGet();
                if (BDSL_STATS) GLOBAL_BDSL_MISS_UNSUPPORTED.incrementAndGet();
                return astCompiler.compile(ir);
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
                if (BDSL_STATS) GLOBAL_BDSL_MISS_CRASH.incrementAndGet();
            }
        }
        return astCompiler.compile(ir);
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
     * {@link com.mezze.interpreter.bytecode.BdslTrampolineRootNode#execute}
     * normalises record ABI into the flat scalar layout BDSL's
     * {@code LoadArgument(i)} expects (see
     * {@link com.mezze.interpreter.bytecode.BdslTrampolineRootNode#normalizeArgs}).
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
            com.mezze.interpreter.compiler.TruffleCompiler astCompiler,
            com.mezze.language.truffleir.FunctionIR ir) {
        return compileOne(astCompiler, ir);
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
                var compiler = new com.mezze.interpreter.compiler.TruffleCompiler(this);
                compiler.registerFunctions(new java.util.ArrayList<>(ctx.getAllUserFunctionIRs()));
                var rootNode = compileOne(compiler, ir);
                ct = rootNode.getCallTarget();
                ctx.cacheUserCallTarget(hash, ct);
                return ct;
            }
        }

        // 3. Compile from stdlib FunctionIR on demand (in-memory map)
        var stdlibIR = com.mezze.language.stdlib.StdlibRuntimeData.INSTANCE.getPreloweredFunctions().get(hash);
        if (stdlibIR != null) {
            var compiler = new com.mezze.interpreter.compiler.TruffleCompiler(this);
            var rootNode = compileOne(compiler, stdlibIR);
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
                    var compiler = new com.mezze.interpreter.compiler.TruffleCompiler(this);
                    var rootNode = compileOne(compiler, fnIR);
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
            com.mezze.interpreter.compiler.TruffleCompiler compiler,
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
                    var rootNode = compileOne(compiler, ir);
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
    private static final ThreadLocal<String> directExecutionEntryName = new ThreadLocal<>();

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

    /** Read locals from the frame + evaluate a small watch/condition expr. */
    private static final class FrameEvalNode extends ExecutableNode {
        private final FrameSlotResolver.Expr expr;
        FrameEvalNode(MezzeLanguage lang, FrameSlotResolver.Expr expr) {
            super(lang);
            this.expr = expr;
        }
        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            return FrameSlotResolver.evaluate(expr, frame);
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

        // Check for direct TruffleIR execution (from tests or CLI)
        List<FunctionIR> directFunctions = directExecutionFunctions.get();
        if (directFunctions != null) {
            directExecutionFunctions.remove();
            List<String[]> performers = directExecutionPerformers.get();
            directExecutionPerformers.remove();
            Path projectRoot = directExecutionRoot.get();
            directExecutionRoot.remove();
            String entryName = directExecutionEntryName.get();
            directExecutionEntryName.remove();
            if (projectRoot == null) {
                projectRoot = Paths.get(System.getProperty("mezze.project.root", "."));
            }

            // All modules are already included in the FunctionIR list — no import extraction needed
            return com.mezze.interpreter.compiler.PipelineRunner.compileFromIR(
                this, directFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList(),
                entryName
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
                null, sourceOverrides
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
            if (result instanceof com.mezze.interpreter.values.MezzeSuspended suspended) {
                result = forceSuspended(suspended);
            }
            lastResultStore.set(result);
            captureGlobalContext();
            return MezzeUnit.INSTANCE;
        }

        @CompilerDirectives.TruffleBoundary
        private Object forceSuspended(com.mezze.interpreter.values.MezzeSuspended suspended) {
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
                if (!(forced instanceof com.mezze.interpreter.values.MezzeSuspended)) {
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
        directExecutionEntryName.set(null);
    }

    /**
     * Set pre-lowered TruffleIR functions with explicit entry function name.
     * Used by mez dev — the entry function (e.g., "main") is called automatically.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, List<String[]> performers, Path projectRoot, String entryName) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(performers);
        directExecutionRoot.set(projectRoot);
        directExecutionEntryName.set(entryName);
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
