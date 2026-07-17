package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.nodes.builtin.dsl.NativeHandler;
import com.mezze.interpreter.nodes.builtin.dsl.NativeHandlers;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.truffleir.BlockIR;
import com.mezze.language.truffleir.BoolLitIR;
import com.mezze.language.truffleir.CallDirectIR;
import com.mezze.language.truffleir.CallIndirectIR;
import com.mezze.language.truffleir.CallNativeIR;
import com.mezze.language.truffleir.CharLitIR;
import com.mezze.language.truffleir.DoubleLitIR;
import com.mezze.language.truffleir.FieldIR;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.truffleir.IfIR;
import com.mezze.language.truffleir.InlineBinaryOpIR;
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
import com.mezze.language.truffleir.ReadFieldIR;
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

    public BytecodeCompiler(MezzeLanguage language) {
        this.language = language;
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
        if (node instanceof WriteLocalIR w) {
            return supportsNode(w.getValue(), argCount);
        }
        if (node instanceof InlineBinaryOpIR bin) {
            return bin.getOp() == InlineOp.ADD
                && supportsNode(bin.getLeft(), argCount)
                && supportsNode(bin.getRight(), argCount);
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
            // Any arity accepted (variadic CallDirectN). Still requires
            // scalar-ABI + non-tail-call, and the callee must already
            // be compiled.
            if (c.isTailCall()) return false;
            if (!c.isScalarABI()) return false;
            if (!compiledCallTargets.containsKey(c.getTargetHash())) return false;
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
                if ("__spread__".equals(f.getName())) return false;
                if (!supportsNode(f.getValue(), argCount)) return false;
            }
            return true;
        }
        if (node instanceof MakeVariantIR mv) {
            NodeIR payload = mv.getPayload();
            return payload == null || supportsNode(payload, argCount);
        }
        if (node instanceof MakeLambdaIR ml) {
            // Callee must already be compiled — same constraint as
            // CallDirectN. Captures are just slot reads in the caller's
            // frame; supports() lets them through unconditionally
            // (they resolve to LoadArgument or LoadLocal at emit time).
            if (!compiledCallTargets.containsKey(ml.getFunctionHash())) return false;
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
            if (ci.isTailCall()) return false;
            return supportsNode(ci.getFunction(), argCount)
                && supportsNode(ci.getArg(), argCount);
        }
        if (node instanceof CallNativeIR cn) {
            // Phase 2: scalar-ABI natives only, and only handlers that
            // do NOT require a Node reference (needsNode=false). Ops
            // needing a Node — Async, IndirectCallNode-consuming ops —
            // are Phase 3.
            if (!cn.isScalarABI()) return false;
            if (NativeHandlers.INSTANCE.needsNode(cn.getName())) return false;
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
                if (!c.getIfLetBindings().isEmpty()) return false;
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
        return nodes.getNode(0);
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
            if (bin.getOp() != InlineOp.ADD) {
                throw new IllegalArgumentException(
                    "Phase 1 supports only InlineOp.ADD; got " + bin.getOp());
            }
            b.beginAddLong();
            emitNode(bin.getLeft(), b, argCount, localMap);
            emitNode(bin.getRight(), b, argCount, localMap);
            b.endAddLong();
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
            CallTarget target = compiledCallTargets.get(c.getTargetHash());
            if (target == null) {
                throw new IllegalStateException(
                    "CallDirectIR references unknown target hash "
                    + c.getTargetHash().getHex() + " — callee must be compiled first");
            }
            b.beginCallDirectN(target);
            for (NodeIR arg : c.getArgs()) {
                emitNode(arg, b, argCount, localMap);
            }
            b.endCallDirectN();
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
        if (node instanceof CallIndirectIR ci) {
            b.beginCallIndirect();
            emitNode(ci.getFunction(), b, argCount, localMap);
            emitNode(ci.getArg(), b, argCount, localMap);
            b.endCallIndirect();
            return;
        }
        if (node instanceof MakeLambdaIR ml) {
            CallTarget target = compiledCallTargets.get(ml.getFunctionHash());
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
            NativeHandler handler = NativeHandlers.INSTANCE.handlerOf(cn.getName());
            if (handler == null) {
                throw new IllegalStateException(
                    "CallNativeIR references unregistered native '" + cn.getName()
                    + "' — supports() should have rejected this");
            }
            b.beginCallNativeScalar(handler);
            for (NodeIR arg : cn.getArgs()) {
                emitNode(arg, b, argCount, localMap);
            }
            b.endCallNativeScalar();
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
        if (pat instanceof PatternIR.Literal lit) {
            // Conditional(PrimEquals(scrutinee, literalExpr), body?, else...)
            // With guard: nest a second Conditional(guard, body, restChain)
            // in the then-branch — same shape as the variant path.
            b.beginConditional();
            b.beginPrimEquals();
            b.emitLoadLocal(scrutineeLocal);
            emitNode(lit.getValue(), b, argCount, localMap);
            b.endPrimEquals();
            if (c.getGuard() != null) {
                b.beginConditional();
                emitNode(c.getGuard(), b, argCount, localMap);
                emitNode(c.getBody(), b, argCount, localMap);
                emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
                b.endConditional();
            } else {
                emitNode(c.getBody(), b, argCount, localMap);
            }
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
        if (c.getGuard() != null) {
            // Guard: Conditional(guard, body, restChain). If the guard
            // is false we fall through by emitting the tail chain
            // again — semantically correct but duplicates the tail
            // once per guarded case. A shared-continuation lowering
            // would be a Phase 3+ perf optimization.
            b.beginConditional();
            emitNode(c.getGuard(), b, argCount, localMap);
            emitNode(c.getBody(), b, argCount, localMap);
            emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
            b.endConditional();
        } else {
            emitNode(c.getBody(), b, argCount, localMap);
        }
        b.endBlock();
        // Else-branch (tag mismatch) — recurse.
        emitMatchChain(cases, idx + 1, scrutineeLocal, b, argCount, localMap);
        b.endConditional();
    }
}
