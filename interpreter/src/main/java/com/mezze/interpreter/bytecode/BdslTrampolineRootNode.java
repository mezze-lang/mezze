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
public final class BdslTrampolineRootNode extends RootNode {

    private RootCallTarget innerTarget;
    private final String name;

    @Child
    private LoopNode loop;

    private final int argsSlot;
    private final int targetSlot;
    private final int resultSlot;

    /** Single-phase constructor: used when the inner is already compiled. */
    public BdslTrampolineRootNode(MezzeLanguage language, RootNode inner, String name) {
        this(language, name);
        attachInner(inner);
    }

    /**
     * Two-phase constructor: create the trampoline first, register its
     * CallTarget under the function's hash, then compile the inner BDSL
     * root (which sees this trampoline as its own callee for
     * self-tail-calls), then call {@link #attachInner}.
     */
    public BdslTrampolineRootNode(MezzeLanguage language, String name) {
        super(language, makeDescriptor());
        this.name = name;
        this.argsSlot = 0;
        this.targetSlot = 1;
        this.resultSlot = 2;
    }

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
        if (this.innerTarget != null) {
            throw new IllegalStateException("BdslTrampolineRootNode.attachInner already called");
        }
        this.innerTarget = inner.getCallTarget();
        this.loop = insert(Truffle.getRuntime().createLoopNode(
                new TrampolineRepeatingNode(getCallTarget(), innerTarget,
                        argsSlot, targetSlot, resultSlot)));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (innerTarget == null) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException(
                    "BdslTrampolineRootNode[" + name + "] executed before attachInner");
        }
        frame.setObject(argsSlot, frame.getArguments());
        frame.setObject(targetSlot, innerTarget);
        loop.execute(frame);
        return frame.getObject(resultSlot);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "BdslTrampolineRootNode[" + name + "]";
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

        private final CallTarget outerTarget;
        private final RootCallTarget innerTarget;
        private final int argsSlot;
        private final int targetSlot;
        private final int resultSlot;

        @Child
        private DirectCallNode selfCall;

        @Child
        private IndirectCallNode crossCall;

        TrampolineRepeatingNode(CallTarget outerTarget, RootCallTarget innerTarget,
                                int argsSlot, int targetSlot, int resultSlot) {
            this.outerTarget = outerTarget;
            this.innerTarget = innerTarget;
            this.argsSlot = argsSlot;
            this.targetSlot = targetSlot;
            this.resultSlot = resultSlot;
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
                } else {
                    result = crossCall.call(target, args);
                }
            } catch (TailCallException tce) {
                frame.setObject(targetSlot, tce.getCallTarget());
                frame.setObject(argsSlot, tce.getArguments());
                return true;
            }
            frame.setObject(resultSlot, result);
            return false;
        }
    }
}
