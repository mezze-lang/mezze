package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.truffleir.InlineBinaryOpIR;
import com.mezze.language.truffleir.InlineOp;
import com.mezze.language.truffleir.LongLitIR;
import com.mezze.language.truffleir.NodeIR;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * IR → Bytecode DSL lowering pass — Phase 1 walking skeleton.
 *
 * <p>Sibling of {@code TruffleCompiler} (Kotlin, lowers to AST) but
 * emits Truffle 25 Bytecode DSL programs instead. Written in Java on
 * purpose: the annotation-processor-generated
 * {@link MezzeBytecodeRootNodeGen} is emitted during the same
 * {@code compileJava} invocation that compiles this class, so the
 * reference resolves without any Gradle wiring gymnastics. (Doing
 * this from Kotlin would require breaking the standard Kotlin-first
 * compile order, which produced a circular-dependency error.)
 *
 * <p>Only lowers the subset of IR that Phase 1 supports (see
 * {@link #supports}): {@link LongLitIR}, long-typed
 * {@link InlineBinaryOpIR} with {@code op = ADD}. Everything else
 * throws {@link UnsupportedOperationException}.
 *
 * <p>Under the BDSL-only migration plan
 * (docs/perf/bytecode-dsl-migration-2026-07.md, task #278), callers
 * consult {@link #supports} before {@link #compile} — programs outside
 * the current BDSL scope still route through the AST path for now.
 * As each phase grows support, more of {@link #supports} returns true,
 * and the AST branches for those node types get deleted.
 *
 * <p>Not thread-safe. One instance per compilation.
 */
public final class BytecodeCompiler {

    private final MezzeLanguage language;

    public BytecodeCompiler(MezzeLanguage language) {
        this.language = language;
    }

    /**
     * True iff {@code ir} uses only IR node types this compiler can
     * lower. Bail early instead of building a partial BDSL program
     * that would later throw at an unsupported node.
     */
    public boolean supports(FunctionIR ir) {
        if (!ir.getParamSlots().isEmpty() ||
            !ir.getLocalSlots().isEmpty() ||
            !ir.getCaptureSlots().isEmpty()) {
            // Phase 1 doesn't handle locals/params/captures yet.
            return false;
        }
        return supportsNode(ir.getBody());
    }

    private boolean supportsNode(NodeIR node) {
        if (node instanceof LongLitIR) {
            return true;
        }
        if (node instanceof InlineBinaryOpIR bin) {
            return bin.getOp() == InlineOp.ADD
                && supportsNode(bin.getLeft())
                && supportsNode(bin.getRight());
        }
        return false;
    }

    /**
     * Lower {@code ir} to a BDSL root node. Precondition:
     * {@link #supports} returned true for the same {@code ir}.
     * Violating that throws {@link UnsupportedOperationException} at
     * the unsupported sub-node — no silent fallback (BDSL-only plan).
     */
    public RootNode compile(FunctionIR ir) {
        var nodes = MezzeBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            b.beginReturn();
            emitNode(ir.getBody(), b);
            b.endReturn();
            b.endRoot();
        });
        return nodes.getNode(0);
    }

    private static void emitNode(NodeIR node, MezzeBytecodeRootNodeGen.Builder b) {
        if (node instanceof LongLitIR lit) {
            b.emitLoadLongConstant(lit.getValue());
            return;
        }
        if (node instanceof InlineBinaryOpIR bin) {
            if (bin.getOp() != InlineOp.ADD) {
                throw new IllegalArgumentException(
                    "Phase 1 supports only InlineOp.ADD; got " + bin.getOp());
            }
            b.beginAddLong();
            emitNode(bin.getLeft(), b);
            emitNode(bin.getRight(), b);
            b.endAddLong();
            return;
        }
        throw new UnsupportedOperationException(
            "BytecodeCompiler Phase 1 doesn't support IR node "
            + node.getClass().getSimpleName()
            + ". Callers should check `supports(ir)` first.");
    }
}
