package com.mezze.interpreter.v2.nodes.seq;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
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
        // Fast path: empty sequence
        if (elementCount == 0) {
            return MezzeSeq.empty();
        }

        // Fast path: single element
        if (elementCount == 1) {
            Object value = elementNodes[0].execute(frame);
            return MezzeSeq.of(value);
        }

        // Fast path: two elements
        if (elementCount == 2) {
            Object v0 = elementNodes[0].execute(frame);
            Object v1 = elementNodes[1].execute(frame);
            return MezzeSeq.of(v0, v1);
        }

        // Fast path: three elements
        if (elementCount == 3) {
            Object v0 = elementNodes[0].execute(frame);
            Object v1 = elementNodes[1].execute(frame);
            Object v2 = elementNodes[2].execute(frame);
            return MezzeSeq.of(v0, v1, v2);
        }

        // General path: evaluate all elements
        Object[] values = new Object[elementCount];
        for (int i = 0; i < elementCount; i++) {
            values[i] = elementNodes[i].execute(frame);
        }
        return MezzeSeq.of(values);
    }

    /**
     * Factory method.
     */
    public static SeqLiteralNode create(MezzeNode[] elementNodes) {
        return new SeqLiteralNode(elementNodes);
    }
}
