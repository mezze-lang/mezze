package com.mezze.interpreter.nodes.seq;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.DoubleSeqStorage;
import com.mezze.datastructures.seq.ObjectSeqStorage;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
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
 * 3. Storage type profiling for JIT optimization
 * 4. Proper MezzeRecord argument wrapping
 *
 * Key optimizations:
 * - DirectCallNode for monomorphic call sites (JIT-inlinable)
 * - Storage type profiling allows JIT to specialize based on element type
 * - Pre-allocated result array (avoids ArrayList)
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

    // Storage type profiling for JIT optimization
    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

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

        // Profile storage type for JIT optimization
        SeqStorage storage = seq.getStorage();
        Class<?> storageType = storage.getClass();

        if (!seenMultipleTypes) {
            if (seenStorageType == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenStorageType = storageType;
            } else if (seenStorageType != storageType) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenMultipleTypes = true;
            }
        }

        // Type-directed dispatch: JIT can optimize based on storage type
        if (!seenMultipleTypes) {
            if (seenStorageType == LongSeqStorage.class) {
                return filterLongSeq((LongSeqStorage) storage, size);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return filterDoubleSeq((DoubleSeqStorage) storage, size);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return filterObjectSeq((ObjectSeqStorage) storage, size);
            }
        }

        // Megamorphic fallback
        return filterGeneric(seq, size);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private MezzeSeq<?> filterLongSeq(LongSeqStorage storage, int size) {
        long[] data = storage.getArray();
        long[] results = new long[size];
        int count = 0;

        if (size <= 8) {
            count = filterLongSmall(data, results, size);
        } else {
            count = filterLongLarge(data, results, size);
        }

        if (count == 0) {
            return MezzeSeq.empty();
        }
        if (count == size) {
            return MezzeSeq.fromStorage(storage);
        }
        return trimAndCreateLong(results, count);
    }

    @ExplodeLoop
    private int filterLongSmall(long[] data, long[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long elem = data[i];
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    @TruffleBoundary
    private int filterLongLarge(long[] data, long[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long elem = data[i];
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    @TruffleBoundary
    private MezzeSeq<?> trimAndCreateLong(long[] results, int count) {
        long[] trimmed = new long[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return MezzeSeq.fromStorage(new LongSeqStorage(trimmed));
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private MezzeSeq<?> filterDoubleSeq(DoubleSeqStorage storage, int size) {
        double[] data = storage.getArray();
        double[] results = new double[size];
        int count = 0;

        if (size <= 8) {
            count = filterDoubleSmall(data, results, size);
        } else {
            count = filterDoubleLarge(data, results, size);
        }

        if (count == 0) {
            return MezzeSeq.empty();
        }
        if (count == size) {
            return MezzeSeq.fromStorage(storage);
        }
        return trimAndCreateDouble(results, count);
    }

    @ExplodeLoop
    private int filterDoubleSmall(double[] data, double[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            double elem = data[i];
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    @TruffleBoundary
    private int filterDoubleLarge(double[] data, double[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            double elem = data[i];
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    @TruffleBoundary
    private MezzeSeq<?> trimAndCreateDouble(double[] results, int count) {
        double[] trimmed = new double[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return MezzeSeq.fromStorage(new DoubleSeqStorage(trimmed));
    }

    /**
     * Specialized path for object sequences.
     */
    private MezzeSeq<?> filterObjectSeq(ObjectSeqStorage storage, int size) {
        Object[] data = storage.getArray();
        Object[] results = new Object[size];
        int count = 0;

        if (size <= 8) {
            count = filterObjectSmall(data, results, size);
        } else {
            count = filterObjectLarge(data, results, size);
        }

        if (count == 0) {
            return MezzeSeq.empty();
        }
        if (count == size) {
            return MezzeSeq.fromStorage(storage);
        }
        return trimAndCreate(results, count);
    }

    @ExplodeLoop
    private int filterObjectSmall(Object[] data, Object[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object elem = data[i];
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    @TruffleBoundary
    private int filterObjectLarge(Object[] data, Object[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object elem = data[i];
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private MezzeSeq<?> filterGeneric(MezzeSeq<?> seq, int size) {
        Object[] results = new Object[size];
        int count = 0;

        if (size <= 8) {
            count = filterGenericSmall(seq, results, size);
        } else {
            count = filterGenericLarge(seq, results, size);
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
    private int filterGenericSmall(MezzeSeq<?> seq, Object[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object elem = seq.get(i);
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    @TruffleBoundary
    private int filterGenericLarge(MezzeSeq<?> seq, Object[] results, int size) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object elem = seq.get(i);
            if (testPredicate(elem)) {
                results[count++] = elem;
            }
        }
        return count;
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
