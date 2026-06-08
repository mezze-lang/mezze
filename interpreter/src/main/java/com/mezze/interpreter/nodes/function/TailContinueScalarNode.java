package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Self-tail-call as a structured loop continue — no throw, no exception
 * allocation. Used in place of {@link TailCallDirectScalarNode} inside
 * functions whose body has been compiled in "tail-loop mode":
 *
 *   - The callee IS this node's enclosing RootNode (self-call).
 *   - Scalar ABI: args are positional values matching the callee's param slots.
 *
 * Per iteration, this node:
 *   1. Evaluates each argument expression to a temp local (via typed
 *      executeLong/executeDouble/execute).
 *   2. Writes all temps into the param frame slots.
 *   3. Returns {@link TailLoopContinue#INSTANCE}.
 *
 * The two-phase eval-then-write is needed because new arg values can
 * reference old slot values (e.g. {@code acc + i}). Writing in-place
 * mid-eval would corrupt later reads.
 *
 * The body's surrounding tree (IfNode, BlockNode, etc.) passes the sentinel
 * back to {@link MezzeFunctionRootNode.BodyRepeatingNode} which detects it
 * and returns {@code CONTINUE_LOOP_STATUS} to the LoopNode driver. The
 * Truffle JIT sees this as ordinary control flow and compiles the loop
 * body to a register-allocated counted loop. (#100)
 */
@NodeInfo(shortName = "tail-continue-scalar")
public final class TailContinueScalarNode extends MezzeNode {

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    /** Param slot indices in the enclosing RootNode's frame (closureCount + i). */
    @CompilationFinal(dimensions = 1)
    private final int[] paramSlots;

    /** Per-param slot kind: 0=LONG, 1=DOUBLE, 2=BOOLEAN, 3=OBJECT. Mirrors
     *  the kinds used by MezzeFunctionRootNode.bindLongPayload etc. */
    @CompilationFinal(dimensions = 1)
    private final int[] paramKinds;

    public TailContinueScalarNode(MezzeNode[] argumentNodes, int[] paramSlots, int[] paramKinds) {
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
        this.paramSlots = paramSlots;
        this.paramKinds = paramKinds;
        if (argumentNodes.length != paramSlots.length || paramSlots.length != paramKinds.length) {
            throw new IllegalArgumentException(
                "TailContinueScalarNode: arg/slot/kind arity mismatch: " +
                argumentNodes.length + "/" + paramSlots.length + "/" + paramKinds.length);
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Two-phase: evaluate ALL args into temp locals first, then commit
        // to frame slots. Required because args may read old slot values
        // (e.g. `acc + i`).
        long[] longTmp = null;
        double[] doubleTmp = null;
        Object[] objTmp = null;

        // Allocate only the buckets we need. @ExplodeLoop unrolls the
        // per-arg dispatch; the allocations are sized at compile time and
        // Truffle's escape analysis should scalar-replace them.
        int longCount = 0, doubleCount = 0, objCount = 0;
        for (int i = 0; i < argumentCount; i++) {
            int k = paramKinds[i];
            if (k == 0) longCount++;
            else if (k == 1) doubleCount++;
            else objCount++;
        }
        if (longCount > 0) longTmp = new long[longCount];
        if (doubleCount > 0) doubleTmp = new double[doubleCount];
        if (objCount > 0) objTmp = new Object[objCount];

        // Phase 1: evaluate.
        try {
            int li = 0, di = 0, oi = 0;
            for (int i = 0; i < argumentCount; i++) {
                int k = paramKinds[i];
                if (k == 0) {
                    longTmp[li++] = argumentNodes[i].executeLong(frame);
                } else if (k == 1) {
                    doubleTmp[di++] = argumentNodes[i].executeDouble(frame);
                } else {
                    objTmp[oi++] = argumentNodes[i].execute(frame);
                }
            }
        } catch (UnexpectedResultException ure) {
            // A typed arg expression returned the wrong type. Fall back to
            // object evaluation for everything and hope the receiver
            // handles it. Rare; bail to interpreter.
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return continueWithObjects(frame);
        }

        // Phase 2: commit to frame slots.
        int li = 0, di = 0, oi = 0;
        for (int i = 0; i < argumentCount; i++) {
            int slot = paramSlots[i];
            int k = paramKinds[i];
            if (k == 0) {
                frame.setLong(slot, longTmp[li++]);
            } else if (k == 1) {
                frame.setDouble(slot, doubleTmp[di++]);
            } else {
                frame.setObject(slot, objTmp[oi++]);
            }
        }

        return TailLoopContinue.INSTANCE;
    }

    /** Slow path: evaluate every arg as Object and write via setObject. */
    @ExplodeLoop
    private Object continueWithObjects(VirtualFrame frame) {
        Object[] tmp = new Object[argumentCount];
        for (int i = 0; i < argumentCount; i++) {
            tmp[i] = argumentNodes[i].execute(frame);
        }
        for (int i = 0; i < argumentCount; i++) {
            frame.setObject(paramSlots[i], tmp[i]);
        }
        return TailLoopContinue.INSTANCE;
    }
}
