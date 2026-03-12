package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.CallTarget;

/**
 * JIT-optimized filter operation for MezzeSeq.
 *
 * This node provides specialized implementations based on:
 * 1. Sequence size (small sequences use loop unrolling)
 * 2. Call site monomorphism (uses DirectCallNode when possible)
 * 3. Storage type preservation for filtered results
 *
 * Key optimizations:
 * - Small sequences (size <= 8): @ExplodeLoop for full unrolling
 * - Pre-sized result array with single copy at the end
 * - Monomorphic predicate calls via DirectCallNode
 * - Avoids ArrayList allocation (uses fixed array + trim)
 */
@NodeInfo(shortName = "seq.filter")
public abstract class SeqFilterNode extends Node {

    /**
     * Filter operation with monomorphic predicate.
     * Uses DirectCallNode for optimal JIT compilation.
     */
    public abstract MezzeSeq<?> execute(MezzeSeq<?> seq, CallTarget predicateTarget, Object[] closureValues);

    /**
     * Small sequence fast path - loop unrolling for sequences of size 1-8.
     * Even for filter, small sequences benefit from unrolling the predicate check.
     */
    @Specialization(guards = "seq.size() <= 8")
    @ExplodeLoop
    protected MezzeSeq<?> filterSmall(MezzeSeq<?> seq, CallTarget predicateTarget, Object[] closureValues,
            @Cached("create(predicateTarget)") DirectCallNode callNode) {
        int size = seq.size();
        if (size == 0) {
            return MezzeSeq.empty();
        }

        // Pre-allocate max possible size
        Object[] results = new Object[size];
        int resultCount = 0;

        // JIT unrolls this loop
        for (int i = 0; i < size; i++) {
            Object element = seq.get(i);
            Object result = callWithClosure(callNode, closureValues, element);
            if (result instanceof Boolean && (Boolean) result) {
                results[resultCount++] = element;
            }
        }

        // Trim to actual size
        if (resultCount == 0) {
            return MezzeSeq.empty();
        }
        if (resultCount == size) {
            return seq; // All elements matched - return original
        }
        return trimAndCreate(results, resultCount);
    }

    /**
     * Large sequence path - standard loop with DirectCallNode.
     * Avoids ArrayList by using pre-allocated array and trimming.
     */
    @Specialization(guards = "seq.size() > 8", replaces = "filterSmall")
    protected MezzeSeq<?> filterLarge(MezzeSeq<?> seq, CallTarget predicateTarget, Object[] closureValues,
            @Cached("create(predicateTarget)") DirectCallNode callNode) {
        int size = seq.size();

        // Pre-allocate max possible size
        Object[] results = new Object[size];
        int resultCount = 0;

        for (int i = 0; i < size; i++) {
            Object element = seq.get(i);
            Object result = callWithClosure(callNode, closureValues, element);
            if (result instanceof Boolean && (Boolean) result) {
                results[resultCount++] = element;
            }
        }

        // Trim to actual size
        if (resultCount == 0) {
            return MezzeSeq.empty();
        }
        if (resultCount == size) {
            return seq; // All elements matched - return original
        }
        return trimAndCreate(results, resultCount);
    }

    /**
     * Helper: Call predicate with closure values prepended to argument.
     */
    private Object callWithClosure(DirectCallNode callNode, Object[] closureValues, Object element) {
        int closureLen = closureValues.length;
        if (closureLen == 0) {
            return callNode.call(new Object[] { wrapArgument(element) });
        } else {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            args[closureLen] = wrapArgument(element);
            return callNode.call(args);
        }
    }

    /**
     * Wrap element in record format expected by Mezze functions.
     */
    @TruffleBoundary
    private Object wrapArgument(Object element) {
        return element;
    }

    /**
     * Trim results array and create MezzeSeq.
     */
    @TruffleBoundary
    private MezzeSeq<?> trimAndCreate(Object[] results, int count) {
        Object[] trimmed = new Object[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return MezzeSeq.fromArray(trimmed);
    }

    public static SeqFilterNode create() {
        return SeqFilterNodeGen.create();
    }
}
