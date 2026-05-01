package com.mezze.interpreter.nodes.seq;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * OPTIMIZED Seq literal node - creates MezzeSeq (optimized Seq type).
 *
 * In Mezze, seq literals `[1, 2, 3]` create immutable Seq types,
 * represented as MezzeSeq backed by the optimized datastructures module.
 *
 * Key optimizations:
 * 1. Pre-computed element count for @ExplodeLoop
 * 2. Direct MezzeSeq construction using factory methods
 * 3. Specialized paths for 0, 1, 2, 3 elements
 */
@NodeInfo(shortName = "seq-literal")
public final class SeqLiteralNode extends MezzeNode {

    @Children
    private final MezzeNode[] elementNodes;

    @CompilationFinal
    private final int elementCount;

    public SeqLiteralNode(MezzeNode[] elementNodes) {
        this.elementNodes = elementNodes;
        this.elementCount = elementNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Fast path: empty sequence — MezzeSeq.empty() returns a shared
        // singleton, so we don't report it as an allocation.
        if (elementCount == 0) {
            return MezzeSeq.empty();
        }

        // For non-empty seqs, evaluate elements first (their own nodes
        // may allocate and report), then bracket the seq construction
        // call with onEnter/onReturnValue. Reporter is null in unit-test
        // contexts that exercise nodes outside an Engine; null-checks are
        // JIT-folded when no instrument is attached.
        AllocationReporter reporter = MezzeContext.tryGetAllocationReporter(this);

        if (elementCount == 1) {
            Object value = elementNodes[0].execute(frame);
            if (reporter != null) reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
            MezzeSeq result = MezzeSeq.of(value);
            if (reporter != null) reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
            return result;
        }

        if (elementCount == 2) {
            Object v0 = elementNodes[0].execute(frame);
            Object v1 = elementNodes[1].execute(frame);
            if (reporter != null) reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
            MezzeSeq result = MezzeSeq.of(v0, v1);
            if (reporter != null) reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
            return result;
        }

        if (elementCount == 3) {
            Object v0 = elementNodes[0].execute(frame);
            Object v1 = elementNodes[1].execute(frame);
            Object v2 = elementNodes[2].execute(frame);
            if (reporter != null) reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
            MezzeSeq result = MezzeSeq.of(v0, v1, v2);
            if (reporter != null) reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
            return result;
        }

        // General path: evaluate all elements
        Object[] values = new Object[elementCount];
        for (int i = 0; i < elementCount; i++) {
            values[i] = elementNodes[i].execute(frame);
        }
        if (reporter != null) reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
        MezzeSeq result = MezzeSeq.of(values);
        if (reporter != null) reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
        return result;
    }

    /**
     * Factory method.
     */
    public static SeqLiteralNode create(MezzeNode[] elementNodes) {
        return new SeqLiteralNode(elementNodes);
    }
}
