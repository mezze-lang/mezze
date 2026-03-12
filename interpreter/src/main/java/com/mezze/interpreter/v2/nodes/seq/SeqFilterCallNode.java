package com.mezze.interpreter.v2.nodes.seq;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * JIT-optimized filter operation for MezzeSeq in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Loop unrolling for small sequences
 * 3. Pre-allocated result array (avoids ArrayList)
 * 4. Proper MezzeRecord argument wrapping
 */
@NodeInfo(shortName = "seq.filter.call")
public final class SeqFilterCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

    @Child
    private MezzeNode predicateNode;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedPredicate;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    @CompilationFinal
    private int cachedClosureLength;

    public SeqFilterCallNode(MezzeNode seqNode, MezzeNode predicateNode) {
        this.seqNode = seqNode;
        this.predicateNode = predicateNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
        MezzeFunction predicate = (MezzeFunction) predicateNode.execute(frame);

        // Fast path: same predicate function
        if (callNode != null && predicate == cachedPredicate) {
            return executeFilter(seq);
        }

        // Slow path: resolve predicate
        resolvePredicate(predicate);
        return executeFilter(seq);
    }

    private Object executeFilter(MezzeSeq<?> seq) {
        int size = seq.size();
        if (size == 0) {
            return seq;
        }

        // Use size-based dispatch
        if (size <= 8) {
            return filterSmall(seq, size);
        } else {
            return filterLarge(seq, size);
        }
    }

    @ExplodeLoop
    private MezzeSeq<?> filterSmall(MezzeSeq<?> seq, int size) {
        Object[] results = new Object[size];
        int count = 0;

        for (int i = 0; i < size; i++) {
            Object element = seq.get(i);
            if (testPredicate(element)) {
                results[count++] = element;
            }
        }

        if (count == 0) {
            return MezzeSeq.empty();
        }
        if (count == size) {
            return seq;
        }
        return trimAndCreate(results, count);
    }

    @TruffleBoundary
    private MezzeSeq<?> filterLarge(MezzeSeq<?> seq, int size) {
        Object[] results = new Object[size];
        int count = 0;

        for (int i = 0; i < size; i++) {
            Object element = seq.get(i);
            if (testPredicate(element)) {
                results[count++] = element;
            }
        }

        if (count == 0) {
            return MezzeSeq.empty();
        }
        if (count == size) {
            return seq;
        }
        return trimAndCreate(results, count);
    }

    @ExplodeLoop
    private boolean testPredicate(Object element) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapX(element);

        Object result;
        if (cachedClosureLength == 0) {
            result = callNode.call(new Object[] { arg });
        } else {
            Object[] args = new Object[cachedClosureLength + 1];
            // Unrolled closure copy for small closures (JIT can unroll this)
            for (int i = 0; i < cachedClosureLength; i++) {
                args[i] = cachedClosureValues[i];
            }
            args[cachedClosureLength] = arg;
            result = callNode.call(args);
        }

        return result instanceof Boolean && (Boolean) result;
    }

    @TruffleBoundary
    private MezzeSeq<?> trimAndCreate(Object[] results, int count) {
        Object[] trimmed = new Object[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return MezzeSeq.fromArray(trimmed);
    }

    @TruffleBoundary
    private void resolvePredicate(MezzeFunction predicate) {
        cachedPredicate = predicate;
        cachedClosureValues = predicate.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        callNode = DirectCallNode.create(predicate.getCallTarget());
        insert(callNode);
    }

    public static SeqFilterCallNode create(MezzeNode seqNode, MezzeNode predicateNode) {
        return new SeqFilterCallNode(seqNode, predicateNode);
    }
}
