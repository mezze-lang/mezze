package com.mezze.interpreter.v2.nodes.array;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * OPTIMIZED Array literal node - creates Object[] (ArrayList representation).
 *
 * In Mezze, array literals `[1, 2, 3]` create mutable ArrayLists,
 * represented as Object[] in V2 for maximum performance.
 *
 * Key optimizations:
 * 1. Pre-computed element count for @ExplodeLoop
 * 2. Direct Object[] construction (no boundary)
 * 3. Specialized paths for 0, 1, 2 elements
 */
@NodeInfo(shortName = "array-literal")
public final class ArrayLiteralNode extends MezzeNode {

    private static final Object[] EMPTY_ARRAY = new Object[0];

    @Children
    private final MezzeNode[] elementNodes;

    @CompilationFinal
    private final int elementCount;

    public ArrayLiteralNode(MezzeNode[] elementNodes) {
        this.elementNodes = elementNodes;
        this.elementCount = elementNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Fast path: empty array
        if (elementCount == 0) {
            return EMPTY_ARRAY;
        }

        // Fast path: single element
        if (elementCount == 1) {
            Object value = elementNodes[0].execute(frame);
            return new Object[] { value };
        }

        // Fast path: two elements
        if (elementCount == 2) {
            Object v0 = elementNodes[0].execute(frame);
            Object v1 = elementNodes[1].execute(frame);
            return new Object[] { v0, v1 };
        }

        // General path: evaluate all elements
        Object[] values = new Object[elementCount];
        for (int i = 0; i < elementCount; i++) {
            values[i] = elementNodes[i].execute(frame);
        }
        return values;
    }

    /**
     * Factory method.
     */
    public static ArrayLiteralNode create(MezzeNode[] elementNodes) {
        return new ArrayLiteralNode(elementNodes);
    }
}
