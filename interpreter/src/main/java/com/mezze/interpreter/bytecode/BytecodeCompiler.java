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
import com.mezze.language.truffleir.LongLitIR;
import com.mezze.language.truffleir.CaseIR;
import com.mezze.language.truffleir.MakeLambdaIR;
import com.mezze.language.truffleir.MakeRecordIR;
import com.mezze.language.truffleir.MakeSeqIR;
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
     * #356 — set by {@link #compile} at the top of each fn compile.
     * Non-null iff BDSL debug instrumentation is on AND the fn's source
     * file could be resolved. Read by {@link #emitNode} to decide
     * whether to emit StatementTag / SourceSection wraps.
     */
    private com.oracle.truffle.api.source.Source currentSource;

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

    /** Look up a callee CallTarget: local map first, then external resolver. */
    private CallTarget resolveCallTarget(Hash hash) {
        CallTarget ct = compiledCallTargets.get(hash);
        if (ct != null) return ct;
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

    /**
     * True iff {@code target} is a BDSL-compiled callee. AST callees use
     * {@code MezzeFunctionRootNode}; BDSL callees are either a
     * {@link BdslTrampolineRootNode} wrapper (paramCount &gt; 0 or has
     * tail-calls) or a bare {@link MezzeBytecodeRootNode}-generated
     * root (paramCount == 0 non-tail).
     *
     * <p>Used at emit time to skip caller-side scalar-marker prepend
     * when the callee is BDSL — the marker would be dead weight because
     * {@link BdslTrampolineRootNode#normalizeArgs} strips it anyway.
     * Skipping saves ~5-10 ns per hot arity-1 call.
     */
    private static boolean isBdslCallee(CallTarget target) {
        if (!(target instanceof com.oracle.truffle.api.RootCallTarget rct)) return false;
        var root = rct.getRootNode();
        return root instanceof BdslTrampolineRootNode
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
        boolean ok = supportsNode(ir.getBody(), argCount);
        if (!ok && STATS_ENABLED) {
            String key = LAST_REJECTION.get();
            if (key == null) key = "<unknown>";
            REJECTION_COUNTS.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
        }
        LAST_REJECTION.remove();
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

    private static void markReject(NodeIR node, String extra) {
        if (!STATS_ENABLED) return;
        // Only capture the FIRST (deepest) rejection so we know the actual
        // blocking node type — outer wrappers set it too but we don't
        // overwrite.
        if (LAST_REJECTION.get() == null) {
            String label = (node == null) ? "<null>" : node.getClass().getSimpleName();
            if (extra != null) label += ":" + extra;
            LAST_REJECTION.set(label);
        }
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
        if (node instanceof PerformOpIR p) {
            // Path A: arg is evaluated then handed to a cached
            // OpCallNode.executeWith. The Mut fast path lowers the raw
            // `act` lambda directly (no wrapper record on the stack)
            // to preserve the marker fast path.
            return supportsNode(p.getArg(), argCount);
        }
        if (node instanceof HandleIR h) {
            // Path D8 (endgame): every HandleIR compiles to inline BDSL
            // ops (PushHandlerChain / body / PopHandlerChain wrapped in
            // TryCatchOtherwise with UnwrapAbortValue in the catch branch).
            // See emit for shape. Path D single-shot semantics apply — the
            // handler impls that use multi-shot resume (Generator.AsStream)
            // won't behave correctly, but those live in stdlib functions
            // that already carry their own effect-safety story; if a user
            // writes a multi-shot handler in a BDSL-compiled fn they get
            // fall-through-only semantics (same as PerformD).
            //
            // Path E (#336) interop: when LAZY_EFFECTS is on AND this Handle
            // is partial-discharging, reject BDSL compilation so the whole
            // function falls back to AST. HandleNode.execute's lazy branch
            // then builds a MezzeSuspended carrying the full body
            // continuation — force-able under a wider chain. BDSL's inline
            // pattern doesn't support that (would need a MakeSuspended op
            // capturing bodyNode as a sub-CallTarget). Fallback is small
            // perf loss, correct semantics.
            if (com.mezze.interpreter.runtime.MezzeLanguage.LAZY_EFFECTS_ENABLED
                    && !h.getStaticallyFullyDischarges()) {
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
                // #361 — non-tail Expr stmts that could yield MezzeSuspended
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
            if (resolveCallTarget(c.getTargetHash()) == null) {
                markReject(node, "target-not-compiled"); return false;
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
                    // path below), so a MezzeSuspended flowing through
                    // an interp part escapes as SuspendedInBlockException
                    // caught at the fn root's outer TryCatch (#361).
                    // Group B scalar-receiver dispatch (Long.to_dbg_str{}
                    // etc.) is now handled by tryFoldInspectMethod +
                    // InlineScalarInspect (#346), so the previous blocker
                    // is gone.
                }
            }
            return true;
        }
        if (node instanceof MakeLambdaIR ml) {
            // Captures are just slot reads in the caller's frame; they
            // resolve to LoadArgument or LoadLocal at emit time.
            //
            // Emit-time-resolved target: fast path uses MakeLambda with
            // CallTarget baked as constant operand.
            //
            // Target-not-compiled fallback (MakeLambdaByHash) tried but
            // reverted — two blockers surfaced under full test:
            //   1. Recursive-let `let f = {..} -> ... f {..}` captures
            //      slot(f) BEFORE the WriteLocal populates it; emit
            //      loads Illegal.
            //   2. Under Async.spawn on a Loom thread,
            //      getCurrentContext() returns null → MakeLambdaByHash
            //      runtime resolve throws (same root cause as #304).
            // Reject; AST fallback keeps these working.
            if (resolveCallTarget(ml.getFunctionHash()) == null) {
                markReject(node, "target-not-compiled");
                return false;
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
        if (node instanceof CallIndirectIR ci) {
            return supportsNode(ci.getFunction(), argCount)
                && supportsNode(ci.getArg(), argCount);
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
                        h -> new BdslTrampolineRootNode(
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
        //       expects — see BdslTrampolineRootNode.normalizeArgs).
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
        BdslTrampolineRootNode trampoline = null;
        if (needsTrampoline) {
            CallTarget registered = compiledCallTargets.get(ir.getHash());
            BdslTrampolineRootNode existing = null;
            if (registered instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof BdslTrampolineRootNode t
                    && t.getInnerTarget() == null) {
                existing = t;
            }
            String[] paramNames = ir.getParamNames().toArray(new String[0]);
            if (existing != null) {
                trampoline = existing;
            } else {
                trampoline = new BdslTrampolineRootNode(
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

            // #361 — wrap body in TryCatch(SuspendedInBlockException).
            // On catch, extract the MezzeSuspended via UnwrapSuspendedInBlock
            // and return it as the fn's result. The exception is thrown by
            // CheckSuspendedThrow emitted after non-tail block Expr stmts
            // whose result could yield suspended (see BlockIR emit above).
            // Non-suspended exceptions are rethrown by UnwrapSuspendedInBlock.
            b.beginTryCatch();
            //   try: normal return of the body
            b.beginReturn();
            emitNode(ir.getBody(), b, argCount, localMap);
            b.endReturn();
            //   catch: extract MezzeSuspended and return it
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
            b.emitLoadObjectConstant(com.mezze.interpreter.values.MezzeUnit.INSTANCE);
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
                    int[] range = debug ? toCharRange(currentSource, l.getSrc()) : null;
                    if (range != null) b.beginSourceSection(range[0], range[1]);
                    if (debug) b.beginTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class);
                    b.beginStoreLocal(localMap[l.getSlot() - argCount]);
                    emitNode(l.getValue(), b, argCount, localMap);
                    b.endStoreLocal();
                    if (debug) b.endTag(com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class);
                    if (range != null) b.endSourceSection();
                } else if (s instanceof StmtIR.Expr e) {
                    // Bare expression statement — evaluate for side
                    // effects, drop the result. BDSL doesn't expose an
                    // explicit "drop" op inside a block. However, a
                    // top-level operation in the middle of a block IS
                    // treated as a discarded expression by the DSL —
                    // its return value is popped implicitly at the
                    // next instruction.
                    //
                    // #361 — when the stmt could yield MezzeSuspended,
                    // wrap the child in CheckSuspendedThrow so the
                    // value is intercepted before it's dropped. If it's
                    // a MezzeSuspended, CheckSuspendedThrow throws
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
            return;
        }
        if (node instanceof CallDirectIR c) {
            CallTarget target = resolveCallTarget(c.getTargetHash());
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
            if (c.isScalarABI()
                    && target instanceof com.oracle.truffle.api.RootCallTarget rct
                    && rct.getRootNode() instanceof BdslTrampolineRootNode tramp
                    && !tramp.hasTailCalls()
                    && !c.isTailCall()) {
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
            // the target's RootNode class — BdslTrampolineRootNode
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
                    // Wrap suspend-capable parts so a MezzeSuspended flows
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
                // #346 Group B: fold `x.to_dbg_str{}` / `x.to_str{}` on
                // scalar (or any) receiver into an inline stringify.
                // Same shape as tryFoldInlineOp: the desugar is
                // CallIndirect(ReadField(x, "to_dbg_str"), MakeRecord{it=x}).
                // Without this, the ReadField hits ReadFieldByName's
                // doNotRecord fallback on a Long/Double/Bool/Str/Char
                // receiver and throws. Mirrors DirectInspectNode's
                // AST-side auto-derive.
                NodeIR receiver = tryFoldInspectMethod(ci);
                if (receiver != null) {
                    ReadFieldIR rf = (ReadFieldIR) ci.getFunction();
                    boolean dbgStyle = "to_dbg_str".equals(rf.getFieldName());
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
            // op — reads resume/abort/scope from EffectFiber.dispatching()
            // or MezzeContext. Zero stack input.
            b.emitEffectBinding(eb.getName());
            return;
        }
        if (node instanceof PerformOpIR p) {
            // Path D (docs/perf/bdsl-effects-path-d-2026-07.md): when the
            // MEZZE_BDSL_YIELD gate is on, lower every perform to a BDSL
            // PerformD op — a direct chain-dispatch native call, no
            // Loom fiber, no reflection. resume is fall-through (single-
            // return); abort throws AbortPerformException as usual. See
            // BdslYieldDispatch for semantics.
            if (com.mezze.interpreter.runtime.MezzeLanguage.BDSL_YIELD_ENABLED) {
                b.beginPerformD(p.getEffectHash(), p.getOperationName());
                emitNode(p.getArg(), b, argCount, localMap);
                b.endPerformD();
                return;
            }

            // Path A (default): fiber-yield via OpCallNode (same node used
            // by AST). See docs/perf/bdsl-effects-path-a-2026-07.md.
            // Mut fast path: emit the raw `act` value directly and use
            // PerformMutMutateAct so the OpCallNode wraps it into the
            // one-field {act = ...} record locally — preserves the
            // marker fast path.
            if (isMutMutateWithActLambda(p)) {
                com.mezze.language.truffleir.MakeRecordIR arg =
                        (com.mezze.language.truffleir.MakeRecordIR) p.getArg();
                b.beginPerformMutMutateAct(p.getEffectHash());
                emitNode(arg.getFields().get(0).getValue(), b, argCount, localMap);
                b.endPerformMutMutateAct();
                return;
            }
            b.beginPerform(p.getEffectHash(), p.getOperationName());
            emitNode(p.getArg(), b, argCount, localMap);
            b.endPerform();
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
            CallTarget target = resolveCallTarget(ml.getFunctionHash());
            if (target == null) {
                // supports() should have rejected — defensive.
                throw new IllegalStateException(
                    "MakeLambdaIR references unknown target hash "
                    + ml.getFunctionHash().getHex() + " — callee must be compiled first");
            }
            b.beginMakeLambda(target, "lambda");
            for (var cap : ml.getCaptures()) {
                int slot = cap.getSlot();
                if (slot < argCount) {
                    b.emitLoadArgument(slot);
                } else {
                    b.emitLoadLocal(localMap[slot - argCount]);
                }
            }
            b.endMakeLambda();
            return;
        }
        if (node instanceof CallNativeIR cn) {
            // Record-ABI: single record arg on the stack; dispatch via
            // NativeDispatch.HANDLERS by name. Slower than the scalar
            // path (still allocs a MezzeRecord in the caller) but routes
            // the surrounding function through BDSL.
            if (!cn.isScalarABI()) {
                b.beginCallNativeRecord(cn.getName());
                emitNode(cn.getArg(), b, argCount, localMap);
                b.endCallNativeRecord();
                return;
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
     * #346 Group B: detect the desugared shape of `x.to_dbg_str{}` /
     * `x.to_str{}` — {@code CallIndirect(ReadField(x, "to_dbg_str"),
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
        if (!"to_dbg_str".equals(name) && !"to_str".equals(name)) return null;
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
     * {@link com.mezze.interpreter.values.MezzeSuspended} — the AST's
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
        // MezzeSuspended anyway, so a call-site whose value is dropped
        // is what matters. Be conservative: treat calls as
        // suspend-capable.
        if (node instanceof CallDirectIR) return true;
        if (node instanceof CallIndirectIR) return true;
        if (node instanceof CallLocalIR) return true;
        if (node instanceof DictMethodCallIR) return true;
        if (node instanceof CallNativeIR) return true;
        // Everything else — literals, ReadArg/Local/Global, MakeRecord etc.
        // — cannot produce MezzeSuspended.
        return false;
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
            // otherwise (normal exit): pop the chain
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
        // so a lazy MezzeSuspended flowing out of body — e.g. from a
        // top-level `let x = effectful{}` — is forced under this Handle's
        // installed chain before the Pop. supports() rejects partial-
        // discharging Handles under LAZY, so every BDSL Handle here IS
        // fully-discharging and safe to force unconditionally.
        b.beginStoreLocal(resultSlot);
        if (isOutermost && com.mezze.interpreter.runtime.MezzeLanguage.LAZY_EFFECTS_ENABLED) {
            b.beginAutoForceIfSuspended();
            emitHandleNested(handlers, idx + 1, body, b, argCount, localMap);
            b.endAutoForceIfSuspended();
        } else {
            emitHandleNested(handlers, idx + 1, body, b, argCount, localMap);
        }
        b.endStoreLocal();
        b.endBlock();
        // === catch branch ===
        b.beginBlock();
        b.beginStoreLocal(resultSlot);
        b.beginUnwrapAbortValue();
        b.emitLoadException();
        b.endUnwrapAbortValue();
        b.endStoreLocal();
        b.emitPopHandlerChain();
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
            boolean hasRest = sp.getRestSlot() >= 0;
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
            if (hasRest) {
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
        b.beginVariantTagEquals(v.getTag());
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
                BytecodeLocal target = localMap[slots.get(i) - argCount];
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
            b.beginVariantTagEquals(iv.getTag());
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
                    BytecodeLocal target = localMap[slots.get(i) - argCount];
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
