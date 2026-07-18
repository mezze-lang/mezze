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
import com.mezze.language.truffleir.DictMethodCallIR;
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

    private final MezzeLanguage language;

    /**
     * Cache of already-compiled callee CallTargets, keyed by hash.
     * Populated by {@link #compileWithCallees} before a caller is
     * emitted so its CallDirect2 sites can bake in the callee's target.
     */
    private final Map<Hash, CallTarget> compiledCallTargets = new HashMap<>();

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
        return supportsNode(ir.getBody(), argCount);
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
            }
            return supportsNode(blk.getResult(), argCount);
        }
        if (node instanceof CallDirectIR c) {
            // Any arity accepted (variadic CallDirectN). Tail-call
            // variant throws TailCallException for the trampoline.
            if (!c.isScalarABI()) return false;
            if (resolveCallTarget(c.getTargetHash()) == null) return false;
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
                }
            }
            return true;
        }
        if (node instanceof MakeLambdaIR ml) {
            // Callee must already be compiled — same constraint as
            // CallDirectN. Captures are just slot reads in the caller's
            // frame; supports() lets them through unconditionally
            // (they resolve to LoadArgument or LoadLocal at emit time).
            if (resolveCallTarget(ml.getFunctionHash()) == null) return false;
            return true;
        }
        if (node instanceof MakeSeqIR ms) {
            for (SeqElementIR el : ms.getElements()) {
                if (el instanceof SeqElementIR.Spread) return false;
                SeqElementIR.Regular reg = (SeqElementIR.Regular) el;
                if (!supportsNode(reg.getNode(), argCount)) return false;
            }
            return true;
        }
        if (node instanceof CallIndirectIR ci) {
            return supportsNode(ci.getFunction(), argCount)
                && supportsNode(ci.getArg(), argCount);
        }
        if (node instanceof DictMethodCallIR dm) {
            // Dict method call = read impl fn from a slot, invoke with arg.
            // Synthesized at emit time as (LoadArgument/LoadLocal, arg,
            // CallIndirect).
            return supportsNode(dm.getArg(), argCount);
        }
        if (node instanceof CallLocalIR cl) {
            return supportsNode(cl.getArg(), argCount);
        }
        if (node instanceof CallNativeIR cn) {
            // Scalar-ABI natives — both regular and needsNode variants
            // (Async, HOF-callback consumers) are supported. The emit
            // side dispatches to CallNativeScalar or
            // CallNativeScalarWithNode based on needsNode.
            if (!cn.isScalarABI()) return false;
            if (NativeHandlers.INSTANCE.handlerOf(cn.getName()) == null) return false;
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
            if (cases.isEmpty()) return false;
            {
                PatternIR lastPat = cases.get(cases.size() - 1).getPattern();
                if (!(lastPat instanceof PatternIR.Wildcard)
                    && !(lastPat instanceof PatternIR.Record)) {
                    return false;
                }
            }
            for (int i = 0; i < cases.size(); i++) {
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
                        return false;
                    }
                }
                PatternIR pat = c.getPattern();
                boolean isLast = i == cases.size() - 1;
                if (pat instanceof PatternIR.Variant v) {
                    // Supported: any number of fieldNames — each becomes
                    // a ReadFieldByName on the unwrapped payload record.
                    // supports() defers to the runtime for record-shape
                    // presence; missing fields ClassCast or throw.
                } else if (pat instanceof PatternIR.Literal lit) {
                    if (!supportsNode(lit.getValue(), argCount)) return false;
                } else if (pat instanceof PatternIR.Record) {
                    // Record pattern is unconditional — must be the last
                    // case (anything after it is dead). Its fields are
                    // extracted directly from the scrutinee via
                    // ReadFieldByName. isOpen doesn't change semantics
                    // (typechecker guarantees shape compatibility).
                    if (!isLast) return false;
                } else if (pat instanceof PatternIR.Seq) {
                    // Fine — length check + element extract, optional rest.
                } else if (pat instanceof PatternIR.Wildcard) {
                    if (!isLast) return false;
                } else {
                    return false;
                }
                if (!supportsNode(c.getBody(), argCount)) return false;
            }
            return true;
        }
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

        var nodes = MezzeBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();

            // Allocate a BDSL local for each IR local slot. The IR uses
            // a flat frame layout [captures..., params..., locals...]
            // with slot numbers indexing into it. Under BDSL params
            // come from LoadArgument(i) and locals from BytecodeLocal
            // objects — we translate at emit time.
            BytecodeLocal[] localMap = new BytecodeLocal[localCount];
            for (int i = 0; i < localCount; i++) {
                localMap[i] = b.createLocal();
            }

            b.beginReturn();
            emitNode(ir.getBody(), b, argCount, localMap);
            b.endReturn();

            b.endRoot();
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
            for (StmtIR s : blk.getStmts()) {
                if (s instanceof StmtIR.Let l) {
                    // A `let` in Mezze IR is a WriteLocal to the let's
                    // slot; the AST compiler models it that way too.
                    // Emit the write, discarding the pushed value
                    // isn't needed because StoreLocal already pops.
                    b.beginStoreLocal(localMap[l.getSlot() - argCount]);
                    emitNode(l.getValue(), b, argCount, localMap);
                    b.endStoreLocal();
                } else if (s instanceof StmtIR.Expr e) {
                    // Bare expression statement — evaluate for side
                    // effects, drop the result. BDSL doesn't expose an
                    // explicit "drop" op inside a block. However, a
                    // top-level operation in the middle of a block IS
                    // treated as a discarded expression by the DSL —
                    // its return value is popped implicitly at the
                    // next instruction.
                    emitNode(e.getNode(), b, argCount, localMap);
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
            if (target instanceof com.oracle.truffle.api.RootCallTarget rct
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
            boolean needsScalarMarker = c.getArgs().size() == 1
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
            b.beginMakeSeq();
            for (SeqElementIR el : ms.getElements()) {
                SeqElementIR.Regular reg = (SeqElementIR.Regular) el;
                emitNode(reg.getNode(), b, argCount, localMap);
            }
            b.endMakeSeq();
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
                    emitNode(e.getNode(), b, argCount, localMap);
                }
            }
            b.endInterpolateString();
            return;
        }
        if (node instanceof CallIndirectIR ci) {
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
        emitMatchChain(m.getCases(), 0, scrutineeLocal, b, argCount, localMap);
        b.endBlock();
    }

    private void emitMatchChain(java.util.List<CaseIR> cases, int idx,
                                BytecodeLocal scrutineeLocal,
                                MezzeBytecodeRootNodeGen.Builder b,
                                int argCount, BytecodeLocal[] localMap) {
        // supports() rejects matches without a trailing wildcard, so we
        // are guaranteed to hit the wildcard case before overflowing.
        CaseIR c = cases.get(idx);
        PatternIR pat = c.getPattern();
        if (pat instanceof PatternIR.Wildcard w) {
            // Wildcard body IS the else. Bind the scrutinee to the
            // wildcard slot if requested, then emit the body directly.
            b.beginBlock();
            if (w.getSlot() >= 0) {
                BytecodeLocal target = localMap[w.getSlot() - argCount];
                b.beginStoreLocal(target);
                b.emitLoadLocal(scrutineeLocal);
                b.endStoreLocal();
            }
            emitNode(c.getBody(), b, argCount, localMap);
            b.endBlock();
            return;
        }
        if (pat instanceof PatternIR.Record rp) {
            // Record patterns are unconditional given the typechecker
            // guarantees shape compatibility — read each captured field
            // from the scrutinee and bind, then emit the body.
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
            emitNode(c.getBody(), b, argCount, localMap);
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
                BytecodeLocal target = localMap[sp.getElementSlots().get(i) - argCount];
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
        if (c.getGuard() != null) {
            b.beginConditional();
            emitNode(c.getGuard(), b, argCount, localMap);
            emitIfLetChain(c, 0, cases, idx, scrutineeLocal, b, argCount, localMap);
            emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
            b.endConditional();
        } else {
            emitIfLetChain(c, 0, cases, idx, scrutineeLocal, b, argCount, localMap);
        }
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
            emitNode(c.getBody(), b, argCount, localMap);
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
