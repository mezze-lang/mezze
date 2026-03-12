package com.mezze.interpreter.v2.nodes.seq;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Node for seq literal with spread elements.
 * Handles the pattern [...xs, a, b, c] where spread sequences come first
 * and regular elements are appended.
 *
 * V2 uses MezzeSeq for sequence representation.
 */
@NodeInfo(shortName = "seq-spread")
public final class SeqSpreadNode extends MezzeNode {

    @Children
    private final MezzeNode[] spreadSeqs;

    @Children
    private final MezzeNode[] appendElements;

    @CompilationFinal
    private final int spreadCount;

    @CompilationFinal
    private final int appendCount;

    public SeqSpreadNode(MezzeNode[] spreadSeqs, MezzeNode[] appendElements) {
        this.spreadSeqs = spreadSeqs;
        this.appendElements = appendElements;
        this.spreadCount = spreadSeqs.length;
        this.appendCount = appendElements.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Execute spread seqs
        Object[] spreadValues = new Object[spreadCount];
        for (int i = 0; i < spreadCount; i++) {
            spreadValues[i] = spreadSeqs[i].execute(frame);
        }

        // Execute append elements
        Object[] appendValues = new Object[appendCount];
        for (int i = 0; i < appendCount; i++) {
            appendValues[i] = appendElements[i].execute(frame);
        }

        // Build result
        return buildSeqWithSpread(spreadValues, appendValues);
    }

    @TruffleBoundary
    private MezzeSeq buildSeqWithSpread(Object[] spreadValues, Object[] appendValues) {
        // First pass: calculate total size
        int totalSize = appendValues.length;
        for (Object spreadValue : spreadValues) {
            if (spreadValue instanceof MezzeSeq seq) {
                totalSize += seq.size();
            } else if (spreadValue instanceof Object[] arr) {
                totalSize += arr.length;
            } else {
                throw new RuntimeException("Spread operator requires a sequence, got: " +
                    (spreadValue == null ? "null" : spreadValue.getClass().getSimpleName()));
            }
        }

        // Single allocation with exact size
        Object[] result = new Object[totalSize];
        int idx = 0;

        // Copy spread sequences
        for (Object spreadValue : spreadValues) {
            if (spreadValue instanceof MezzeSeq seq) {
                for (int i = 0; i < seq.size(); i++) {
                    result[idx++] = seq.get(i);
                }
            } else if (spreadValue instanceof Object[] arr) {
                System.arraycopy(arr, 0, result, idx, arr.length);
                idx += arr.length;
            }
        }

        // Copy append elements
        System.arraycopy(appendValues, 0, result, idx, appendValues.length);

        return MezzeSeq.fromArray(result);
    }

    public static SeqSpreadNode create(MezzeNode[] spreadSeqs, MezzeNode[] appendElements) {
        return new SeqSpreadNode(spreadSeqs, appendElements);
    }
}
