package com.mezze.interpreter.v2.nodes.array;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Node for array literal with spread elements.
 * Handles the pattern [...xs, a, b, c] where spread arrays come first
 * and regular elements are appended.
 *
 * V2 uses Object[] for ArrayList representation.
 */
@NodeInfo(shortName = "array-spread")
public final class ArraySpreadNode extends MezzeNode {

    @Children
    private final MezzeNode[] spreadArrays;

    @Children
    private final MezzeNode[] appendElements;

    @CompilationFinal
    private final int spreadCount;

    @CompilationFinal
    private final int appendCount;

    public ArraySpreadNode(MezzeNode[] spreadArrays, MezzeNode[] appendElements) {
        this.spreadArrays = spreadArrays;
        this.appendElements = appendElements;
        this.spreadCount = spreadArrays.length;
        this.appendCount = appendElements.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Execute spread arrays
        Object[] spreadValues = new Object[spreadCount];
        for (int i = 0; i < spreadCount; i++) {
            spreadValues[i] = spreadArrays[i].execute(frame);
        }

        // Execute append elements
        Object[] appendValues = new Object[appendCount];
        for (int i = 0; i < appendCount; i++) {
            appendValues[i] = appendElements[i].execute(frame);
        }

        // Build result
        return buildArrayWithSpread(spreadValues, appendValues);
    }

    @TruffleBoundary
    private Object[] buildArrayWithSpread(Object[] spreadValues, Object[] appendValues) {
        // First pass: calculate total size to avoid ArrayList resizing
        int totalSize = appendValues.length;
        for (Object spreadValue : spreadValues) {
            if (spreadValue instanceof Object[] arr) {
                totalSize += arr.length;
            } else {
                throw new RuntimeException("Spread operator requires an array, got: " +
                    (spreadValue == null ? "null" : spreadValue.getClass().getSimpleName()));
            }
        }

        // Single allocation with exact size
        Object[] result = new Object[totalSize];
        int idx = 0;

        // Copy spread arrays
        for (Object spreadValue : spreadValues) {
            Object[] arr = (Object[]) spreadValue;  // Already type-checked above
            System.arraycopy(arr, 0, result, idx, arr.length);
            idx += arr.length;
        }

        // Copy append elements
        System.arraycopy(appendValues, 0, result, idx, appendValues.length);

        return result;
    }

    public static ArraySpreadNode create(MezzeNode[] spreadArrays, MezzeNode[] appendElements) {
        return new ArraySpreadNode(spreadArrays, appendElements);
    }
}
