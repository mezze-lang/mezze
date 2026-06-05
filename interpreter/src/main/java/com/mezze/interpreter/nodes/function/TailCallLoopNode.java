package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;

/**
 * Tail call loop node — wraps the trampoline body in a {@link LoopNode}
 * so Truffle's compiler treats the iteration count as the "hotness"
 * signal, not the (single) function-call count.
 *
 * <p>Before #256: this node was a plain {@code while (true) { try body }}
 * loop. For tail-recursive driver functions like {@code sum_lengths}
 * (the Collatz outer loop) Truffle's OptimizedCallTarget only triggered
 * compilation on call count — but the function is only called ONCE
 * (and runs forever inside the trampoline), so it never crossed the
 * threshold. JFR confirmed: {@code sum_lengths} was never compiled,
 * its 5M iterations all ran in the interpreter, eating an
 * interpreter→JIT boundary on every call to {@code collatz_length}.
 *
 * <p>After: {@link LoopNode} reports its iteration count to the engine
 * via {@code LoopNode.reportLoopCount}, which feeds into the OSR
 * (on-stack-replacement) compilation trigger. After ~10k iterations
 * Truffle compiles the loop body in-place and the driver enters JIT
 * code without unwinding.
 *
 * <h2>Repeating semantics</h2>
 *
 * Per-iteration outcomes from the body:
 * <ul>
 *   <li><b>Normal return</b>: body computed a value. Use it as the
 *       loop's return value and stop iterating.</li>
 *   <li><b>{@link TailCallException} to self</b>: rebind params,
 *       continue.</li>
 *   <li><b>{@link TailCallException} to another function</b>: inner
 *       cross-function trampoline (preserves the pre-#256 behavior of
 *       this node — see {@code execute} below).</li>
 * </ul>
 *
 * @see RepeatingNode
 */
@NodeInfo(shortName = "tail-loop")
public final class TailCallLoopNode extends MezzeNode {

    @Child
    private LoopNode loopNode;

    @CompilationFinal
    private final TailCallRepeatingNode repeatingNode;

    public TailCallLoopNode(MezzeNode bodyNode, int parameterCount, int closureCount) {
        this(bodyNode, parameterCount, closureCount, null);
    }

    public TailCallLoopNode(MezzeNode bodyNode, int parameterCount, int closureCount, String[] parameterNames) {
        this.repeatingNode = new TailCallRepeatingNode(
            bodyNode, parameterCount, closureCount, parameterNames);
        this.loopNode = com.oracle.truffle.api.Truffle.getRuntime().createLoopNode(repeatingNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Lazy-init the self CallTarget here so we have access to the
        // enclosing RootNode (the body's RootNode == this node's RootNode).
        if (repeatingNode.selfCallTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            com.oracle.truffle.api.nodes.RootNode root = getRootNode();
            if (root != null) {
                repeatingNode.selfCallTarget = root.getCallTarget();
            }
        }
        return loopNode.execute(frame);
    }

    /**
     * The body-once-and-decide node. Truffle's LoopNode calls
     * {@code executeRepeatingWithValue} until we signal stop (via
     * {@code shouldContinue}).
     *
     * <p>Status discipline (Truffle convention):
     * <ul>
     *   <li>{@link RepeatingNode#CONTINUE_LOOP_STATUS} → keep looping
     *       (after a self-tail-call rebind).</li>
     *   <li>Any other Object → stop, return that as the loop's value
     *       (either body's normal return or a cross-fn trampoline result).</li>
     * </ul>
     */
    static final class TailCallRepeatingNode extends com.oracle.truffle.api.nodes.Node
            implements RepeatingNode {

        @Child
        private MezzeNode bodyNode;

        @CompilationFinal
        private final int parameterCount;

        @CompilationFinal
        private final int closureCount;

        @CompilationFinal(dimensions = 1)
        private final String[] parameterNames;

        @CompilationFinal(dimensions = 1)
        private int[] cachedFieldIndices;

        @CompilationFinal
        CallTarget selfCallTarget;

        TailCallRepeatingNode(MezzeNode bodyNode, int parameterCount, int closureCount,
                              String[] parameterNames) {
            this.bodyNode = bodyNode;
            this.parameterCount = parameterCount;
            this.closureCount = closureCount;
            this.parameterNames = parameterNames;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            // Not used — we always go through executeRepeatingWithValue
            // because the body can produce arbitrary values, not just
            // continue/break.
            throw new IllegalStateException(
                "executeRepeating should not be called; use executeRepeatingWithValue");
        }

        @Override
        public Object executeRepeatingWithValue(VirtualFrame frame) {
            try {
                // Body returned normally → final value; loop stops.
                return bodyNode.execute(frame);
            } catch (TailCallException e) {
                CallTarget target = e.getCallTarget();
                if (target == selfCallTarget) {
                    rebindArguments(frame, e.getArguments());
                    return CONTINUE_LOOP_STATUS;
                }
                // Cross-function: dispatch through an inner trampoline so
                // the loop's repeating-node count only includes self-iters.
                // Bounded by the number of distinct fns in the tail cycle.
                Object[] args = e.getArguments();
                while (true) {
                    try {
                        return target.call(args);
                    } catch (TailCallException e2) {
                        if (e2.getCallTarget() == selfCallTarget) {
                            rebindArguments(frame, e2.getArguments());
                            return CONTINUE_LOOP_STATUS;
                        }
                        target = e2.getCallTarget();
                        args = e2.getArguments();
                    }
                }
            }
        }

        @Override
        public Object initialLoopStatus() {
            // Initial state — any value other than the final result means
            // "still looping." We use CONTINUE_LOOP_STATUS as the sentinel
            // to keep the loop running until executeRepeatingWithValue
            // returns something else.
            return CONTINUE_LOOP_STATUS;
        }

        @Override
        public boolean shouldContinue(Object status) {
            return status == CONTINUE_LOOP_STATUS;
        }

        /**
         * Rebind parameter slots from the new arguments.
         * First call computes and caches field indices.
         * All subsequent calls use the fast path.
         *
         * ABI dispatch (mirrors MezzeFunctionRootNode.execute):
         *   parameterCount == 1 + marker present  → scalar (values at closureCount+1..)
         *   parameterCount != 1 + first arg !record → scalar (values at closureCount..)
         *   first arg is a MezzeRecord            → record path
         */
        private void rebindArguments(VirtualFrame frame, Object[] newArgs) {
            if (closureCount >= newArgs.length) return;

            Object first = newArgs[closureCount];
            boolean scalarABI;
            int scalarValuesStart;
            if (parameterCount == 1) {
                scalarABI = first == ScalarABIMarker.INSTANCE;
                scalarValuesStart = closureCount + 1;
            } else {
                scalarABI = newArgs.length == closureCount + parameterCount;
                scalarValuesStart = closureCount;
            }

            if (scalarABI) {
                rebindScalar(frame, newArgs, scalarValuesStart);
                return;
            }
            if (!(first instanceof MezzeRecord record)) return;

            if (cachedFieldIndices != null) {
                rebindFast(frame, record);
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                initializeCacheAndRebind(frame, record);
            }
        }

        @ExplodeLoop
        private void rebindScalar(VirtualFrame frame, Object[] newArgs, int valuesStart) {
            for (int i = 0; i < parameterCount; i++) {
                int srcIndex = valuesStart + i;
                if (srcIndex < newArgs.length) {
                    frame.setObject(closureCount + i, newArgs[srcIndex]);
                }
            }
        }

        @ExplodeLoop
        private void rebindFast(VirtualFrame frame, MezzeRecord record) {
            for (int i = 0; i < parameterCount; i++) {
                int fieldIndex = cachedFieldIndices[i];
                if (fieldIndex >= 0) {
                    Object value = record.getField(fieldIndex);
                    frame.setObject(closureCount + i, value);
                }
            }
        }

        private void initializeCacheAndRebind(VirtualFrame frame, MezzeRecord record) {
            int[] indices = computeFieldIndices(record);
            for (int i = 0; i < parameterCount; i++) {
                if (indices[i] >= 0) {
                    Object value = record.getField(indices[i]);
                    frame.setObject(closureCount + i, value);
                }
            }
            cachedFieldIndices = indices;
        }

        @TruffleBoundary
        private int[] computeFieldIndices(MezzeRecord record) {
            int[] indices = new int[parameterCount];
            if (parameterNames != null) {
                for (int i = 0; i < parameterCount; i++) {
                    indices[i] = record.getFieldIndex(parameterNames[i]);
                }
            } else {
                for (int i = 0; i < parameterCount; i++) {
                    indices[i] = i < record.getFieldCount() ? i : -1;
                }
            }
            return indices;
        }
    }
}
