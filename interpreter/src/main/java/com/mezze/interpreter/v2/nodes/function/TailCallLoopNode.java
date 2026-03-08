package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
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

    @Override
    public Object execute(VirtualFrame frame) {
        while (loopProfile.profile(true)) {
            try {
                return bodyNode.execute(frame);
            } catch (TailCallException e) {
                rebindArguments(frame, e.getArguments());
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    /**
     * Rebind parameter slots from the new arguments.
     * First call computes and caches field indices.
     * All subsequent calls use the fast path.
     */
    private void rebindArguments(VirtualFrame frame, Object[] newArgs) {
        if (closureCount >= newArgs.length) return;

        Object recordArg = newArgs[closureCount];
        if (!(recordArg instanceof MezzeRecord record)) return;

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
