package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.truffleir.BlockIR;
import com.mezze.language.truffleir.CallDirectIR;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.truffleir.IfIR;
import com.mezze.language.truffleir.InlineBinaryOpIR;
import com.mezze.language.truffleir.InlineOp;
import com.mezze.language.truffleir.LongLitIR;
import com.mezze.language.truffleir.NodeIR;
import com.mezze.language.truffleir.ReadLocalIR;
import com.mezze.language.truffleir.StmtIR;
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
 *   <li>{@link LongLitIR} — long constant push</li>
 *   <li>{@link InlineBinaryOpIR} with {@code op = ADD} — long add</li>
 *   <li>{@link ReadLocalIR} — parameter or local read (uses IR slot
 *       layout {@code [captures..., params..., locals...]}; captures
 *       empty in Phase 1)</li>
 *   <li>{@link WriteLocalIR} — local write</li>
 *   <li>{@link IfIR} — branching (via BDSL built-in {@code IfThenElse})</li>
 *   <li>{@link BlockIR} with {@link StmtIR.Let} + {@link StmtIR.Expr}</li>
 *   <li>{@link CallDirectIR} with EXACTLY 2 args, scalar-ABI, non-tail-call
 *       — via the {@code CallDirect2} custom operation</li>
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
 * constant operand to {@code CallDirect2} in the caller. This means
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
        if (!ir.getCaptureSlots().isEmpty()) {
            // Phase 1 doesn't handle closures.
            return false;
        }
        return supportsNode(ir.getBody(), ir.getParamSlots().size());
    }

    private boolean supportsNode(NodeIR node, int paramCount) {
        if (node instanceof LongLitIR) return true;
        if (node instanceof ReadLocalIR) return true;
        if (node instanceof WriteLocalIR w) {
            return supportsNode(w.getValue(), paramCount);
        }
        if (node instanceof InlineBinaryOpIR bin) {
            return bin.getOp() == InlineOp.ADD
                && supportsNode(bin.getLeft(), paramCount)
                && supportsNode(bin.getRight(), paramCount);
        }
        if (node instanceof IfIR i) {
            return supportsNode(i.getCond(), paramCount)
                && supportsNode(i.getThenBr(), paramCount)
                && supportsNode(i.getElseBr(), paramCount);
        }
        if (node instanceof BlockIR blk) {
            for (StmtIR s : blk.getStmts()) {
                NodeIR child = (s instanceof StmtIR.Let l) ? l.getValue()
                             : ((StmtIR.Expr) s).getNode();
                if (!supportsNode(child, paramCount)) return false;
            }
            return supportsNode(blk.getResult(), paramCount);
        }
        if (node instanceof CallDirectIR c) {
            // Phase 1: only 2-arg scalar-ABI, non-tail-call, and the
            // callee's CallTarget must already have been compiled.
            if (c.getArgs().size() != 2) return false;
            if (c.isTailCall()) return false;
            if (!c.isScalarABI()) return false;
            if (!compiledCallTargets.containsKey(c.getTargetHash())) return false;
            for (NodeIR arg : c.getArgs()) {
                if (!supportsNode(arg, paramCount)) return false;
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
        final int paramCount = ir.getParamSlots().size();
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
            emitNode(ir.getBody(), b, paramCount, localMap);
            b.endReturn();

            b.endRoot();
        });
        return nodes.getNode(0);
    }

    private void emitNode(NodeIR node, MezzeBytecodeRootNodeGen.Builder b,
                          int paramCount, BytecodeLocal[] localMap) {
        if (node instanceof LongLitIR lit) {
            b.emitLoadLongConstant(lit.getValue());
            return;
        }
        if (node instanceof ReadLocalIR r) {
            // IR slot layout: [captures..., params..., locals...].
            // Captures always empty in Phase 1, so slot < paramCount
            // means a parameter (LoadArgument), slot >= paramCount
            // means a local (LoadLocal).
            int slot = r.getSlot();
            if (slot < paramCount) {
                b.emitLoadArgument(slot);
            } else {
                b.emitLoadLocal(localMap[slot - paramCount]);
            }
            return;
        }
        if (node instanceof WriteLocalIR w) {
            int slot = w.getSlot();
            if (slot < paramCount) {
                throw new UnsupportedOperationException(
                    "Phase 1 doesn't support writing to a parameter slot "
                    + "(WriteLocalIR to slot " + slot + " where paramCount=" + paramCount + ")");
            }
            b.beginStoreLocal(localMap[slot - paramCount]);
            emitNode(w.getValue(), b, paramCount, localMap);
            b.endStoreLocal();
            return;
        }
        if (node instanceof InlineBinaryOpIR bin) {
            if (bin.getOp() != InlineOp.ADD) {
                throw new IllegalArgumentException(
                    "Phase 1 supports only InlineOp.ADD; got " + bin.getOp());
            }
            b.beginAddLong();
            emitNode(bin.getLeft(), b, paramCount, localMap);
            emitNode(bin.getRight(), b, paramCount, localMap);
            b.endAddLong();
            return;
        }
        if (node instanceof IfIR i) {
            b.beginIfThenElse();
            emitNode(i.getCond(), b, paramCount, localMap);
            emitNode(i.getThenBr(), b, paramCount, localMap);
            emitNode(i.getElseBr(), b, paramCount, localMap);
            b.endIfThenElse();
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
                    b.beginStoreLocal(localMap[l.getSlot() - paramCount]);
                    emitNode(l.getValue(), b, paramCount, localMap);
                    b.endStoreLocal();
                } else if (s instanceof StmtIR.Expr e) {
                    // Bare expression statement — evaluate for side
                    // effects, drop the result. BDSL doesn't expose an
                    // explicit "drop" op inside a block. However, a
                    // top-level operation in the middle of a block IS
                    // treated as a discarded expression by the DSL —
                    // its return value is popped implicitly at the
                    // next instruction.
                    emitNode(e.getNode(), b, paramCount, localMap);
                }
            }
            emitNode(blk.getResult(), b, paramCount, localMap);
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
            if (c.getArgs().size() != 2) {
                throw new UnsupportedOperationException(
                    "Phase 1 CallDirect2 requires exactly 2 args, got " + c.getArgs().size());
            }
            b.beginCallDirect2(target);
            emitNode(c.getArgs().get(0), b, paramCount, localMap);
            emitNode(c.getArgs().get(1), b, paramCount, localMap);
            b.endCallDirect2();
            return;
        }
        throw new UnsupportedOperationException(
            "BytecodeCompiler Phase 1 doesn't support IR node "
            + node.getClass().getSimpleName()
            + ". Callers should check `supports(ir)` first.");
    }
}
