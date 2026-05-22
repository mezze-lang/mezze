package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.LoopConditionProfile;

/**
 * Tail call loop node - MAXIMUM PERFORMANCE.
 *
 * Key optimizations:
 * 1. Field indices cached on first tail call - O(1) field access thereafter
 * 2. @ExplodeLoop for parameter rebinding - fully unrolled by JIT
 * 3. LoopConditionProfile for branch prediction
 * 4. NO @TruffleBoundary in hot path after warmup
 *
 * After JIT compilation, the tail call loop becomes:
 * - A tight loop with no method calls
 * - Direct field access via cached indices
 * - Zero allocation per iteration
 *
 * Slot layout: [closure0, ..., closureN-1, param0, ..., paramM-1]
 * Arguments from TailCallException: [closure0, ..., closureN-1, recordArg]
 */
@NodeInfo(shortName = "tail-loop")
public final class TailCallLoopNode extends MezzeNode {

    @Child
    private MezzeNode bodyNode;

    @CompilationFinal
    private final int parameterCount;

    @CompilationFinal
    private final int closureCount;

    @CompilationFinal(dimensions = 1)
    private final String[] parameterNames;

    // Cached field indices - populated on first tail call, then constant-folded by JIT
    @CompilationFinal(dimensions = 1)
    private int[] cachedFieldIndices;

    private final LoopConditionProfile loopProfile = LoopConditionProfile.create();

    public TailCallLoopNode(MezzeNode bodyNode, int parameterCount, int closureCount) {
        this(bodyNode, parameterCount, closureCount, null);
    }

    public TailCallLoopNode(MezzeNode bodyNode, int parameterCount, int closureCount, String[] parameterNames) {
        this.bodyNode = bodyNode;
        this.parameterCount = parameterCount;
        this.closureCount = closureCount;
        this.parameterNames = parameterNames;
    }

    // The CallTarget for the current function - used to detect self-recursive vs cross-function tail calls
    // Lazily initialized on first tail call
    @CompilationFinal
    private com.oracle.truffle.api.CallTarget selfCallTarget;

    @Override
    public Object execute(VirtualFrame frame) {
        // Lazily initialize self CallTarget from the root node
        if (selfCallTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            com.oracle.truffle.api.nodes.RootNode rootNode = getRootNode();
            if (rootNode != null) {
                selfCallTarget = rootNode.getCallTarget();
            }
        }

        // Trampoline for both self-recursive and cross-function tail
        // calls. The key invariant: this method never grows the Java
        // stack across iterations — when it catches a TailCallException,
        // it either rebinds slots (self) or replaces the target/args
        // and re-issues `target.call(args)` in the inner loop (cross).
        //
        // Cross-function works because the callee's TailCallLoopNode
        // (a fresh Java frame for the duration of one call) catches
        // its OWN body's exceptions: if it self-recurses it loops, if
        // it cross-tail-calls a 3rd function it enters its OWN inner
        // trampoline (one more frame), etc. The depth of Java frames
        // is bounded by the number of distinct functions in the tail
        // cycle, NOT by the recursion depth. For mutual recursion
        // (even/odd ping-pong at depth N), Java stack stays at ~2
        // frames regardless of N.
        while (loopProfile.profile(true)) {
            try {
                return bodyNode.execute(frame);
            } catch (TailCallException e) {
                CallTarget target = e.getCallTarget();
                if (target == selfCallTarget) {
                    rebindArguments(frame, e.getArguments());
                    continue;
                }
                // Cross-function: trampoline. Inner loop avoids re-throwing
                // the exception (which would unwind through bodyNode and
                // re-enter the outer loop redundantly).
                Object[] args = e.getArguments();
                while (true) {
                    try {
                        return target.call(args);
                    } catch (TailCallException e2) {
                        if (e2.getCallTarget() == selfCallTarget) {
                            rebindArguments(frame, e2.getArguments());
                            break;
                        }
                        target = e2.getCallTarget();
                        args = e2.getArguments();
                    }
                }
            }
        }
        throw new IllegalStateException("Unreachable");
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
            // Arity collides — caller writes the marker.
            scalarABI = first == ScalarABIMarker.INSTANCE;
            scalarValuesStart = closureCount + 1;
        } else {
            // parameterCount >= 2: disambiguate by arity (no instanceof).
            // Scalar arity == closureCount + parameterCount; record arity == closureCount + 1.
            scalarABI = newArgs.length == closureCount + parameterCount;
            scalarValuesStart = closureCount;
        }

        if (scalarABI) {
            rebindScalar(frame, newArgs, scalarValuesStart);
            return;
        }
        if (!(first instanceof MezzeRecord record)) return;

        if (cachedFieldIndices != null) {
            // FAST PATH: Use cached indices - fully JIT-compilable
            rebindFast(frame, record);
        } else {
            // SLOW PATH: First call - compute indices and cache
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initializeCacheAndRebind(frame, record);
        }
    }

    /**
     * Scalar ABI rebind — positional, no field-name lookup needed.
     * After JIT: tight loop, no allocation, no record indirection.
     */
    @ExplodeLoop
    private void rebindScalar(VirtualFrame frame, Object[] newArgs, int valuesStart) {
        for (int i = 0; i < parameterCount; i++) {
            int srcIndex = valuesStart + i;
            if (srcIndex < newArgs.length) {
                frame.setObject(closureCount + i, newArgs[srcIndex]);
            }
        }
    }

    /**
     * Fast path: rebind using cached field indices.
     * After JIT: direct array access, no method calls, fully unrolled.
     */
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

    /**
     * Initialize cache and perform first rebind.
     * Called once, then never again for this node instance.
     */
    private void initializeCacheAndRebind(VirtualFrame frame, MezzeRecord record) {
        int[] indices = computeFieldIndices(record);

        // Bind values using the computed indices
        for (int i = 0; i < parameterCount; i++) {
            if (indices[i] >= 0) {
                Object value = record.getField(indices[i]);
                frame.setObject(closureCount + i, value);
            }
        }

        // Cache for future calls - this makes cachedFieldIndices @CompilationFinal effective
        cachedFieldIndices = indices;
    }

    /**
     * Compute field indices by name lookup.
     * Only called once per node instance.
     */
    @TruffleBoundary
    private int[] computeFieldIndices(MezzeRecord record) {
        int[] indices = new int[parameterCount];

        if (parameterNames != null) {
            for (int i = 0; i < parameterCount; i++) {
                indices[i] = record.getFieldIndex(parameterNames[i]);
            }
        } else {
            // Fallback: assume same field order
            for (int i = 0; i < parameterCount; i++) {
                indices[i] = i < record.getFieldCount() ? i : -1;
            }
        }

        return indices;
    }
}
