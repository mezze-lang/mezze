package com.mezze.interpreter.v2.nodes.seq;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.ArrayList;

/**
 * Seq literal with spread elements: [a, ...xs, b, ...ys]
 *
 * Each element is either regular (adds one value) or a spread
 * (concatenates all elements from another seq). The isSpread
 * array marks which elements should be spread.
 */
@NodeInfo(shortName = "seq-spread-literal")
public final class SeqSpreadLiteralNode extends MezzeNode {

    @Children
    private final MezzeNode[] elementNodes;

    @CompilationFinal(dimensions = 1)
    private final boolean[] isSpread;

    @CompilationFinal
    private final int elementCount;

    public SeqSpreadLiteralNode(MezzeNode[] elementNodes, boolean[] isSpread) {
        this.elementNodes = elementNodes;
        this.isSpread = isSpread;
        this.elementCount = elementNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Evaluate all element nodes
        Object[] values = new Object[elementCount];
        for (int i = 0; i < elementCount; i++) {
            values[i] = elementNodes[i].execute(frame);
        }
        return buildSeq(values);
    }

    @TruffleBoundary
    private Object buildSeq(Object[] values) {
        ArrayList<Object> result = new ArrayList<>();
        for (int i = 0; i < elementCount; i++) {
            if (isSpread[i]) {
                // Spread: flatten the seq/array into the result
                Object spreadValue = values[i];
                if (spreadValue instanceof MezzeSeq<?> seq) {
                    for (int j = 0; j < seq.size(); j++) {
                        result.add(seq.get(j));
                    }
                } else if (spreadValue instanceof Object[] arr) {
                    for (Object elem : arr) {
                        result.add(elem);
                    }
                } else {
                    // Fallback: treat as single element
                    result.add(spreadValue);
                }
            } else {
                result.add(values[i]);
            }
        }
        return MezzeSeq.of(result.toArray());
    }

    public static SeqSpreadLiteralNode create(MezzeNode[] elementNodes, boolean[] isSpread) {
        return new SeqSpreadLiteralNode(elementNodes, isSpread);
    }
}
