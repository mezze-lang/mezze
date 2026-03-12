package com.mezze.interpreter.v2.nodes.seq;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.DoubleSeqStorage;
import com.mezze.datastructures.seq.ObjectSeqStorage;
import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * JIT-optimized map operation for MezzeSeq in the interpreter.
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
 * - Loop unrolling for small sequences
 */
@NodeInfo(shortName = "seq.map.call")
public final class SeqMapCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

    @Child
    private MezzeNode mapperNode;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedMapper;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    @CompilationFinal
    private int cachedClosureLength;

    // Storage type profiling for JIT optimization
    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

    public SeqMapCallNode(MezzeNode seqNode, MezzeNode mapperNode) {
        this.seqNode = seqNode;
        this.mapperNode = mapperNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
        MezzeFunction mapper = (MezzeFunction) mapperNode.execute(frame);

        // Fast path: same mapper function
        if (callNode != null && mapper == cachedMapper) {
            return executeMap(seq);
        }

        // Slow path: resolve mapper
        resolveMapper(mapper);
        return executeMap(seq);
    }

    private Object executeMap(MezzeSeq<?> seq) {
        int size = seq.size();
        if (size == 0) {
            return MezzeSeq.empty();
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
                return mapLongSeq((LongSeqStorage) storage, size);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return mapDoubleSeq((DoubleSeqStorage) storage, size);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return mapObjectSeq((ObjectSeqStorage) storage, size);
            }
        }

        // Megamorphic fallback
        return mapGeneric(seq, size);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private MezzeSeq<?> mapLongSeq(LongSeqStorage storage, int size) {
        Object[] results = new Object[size];
        long[] data = storage.getArray();

        if (size <= 8) {
            mapLongSmall(results, data, size);
        } else {
            mapLongLarge(results, data, size);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapLongSmall(Object[] results, long[] data, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i]);
        }
    }

    @TruffleBoundary
    private void mapLongLarge(Object[] results, long[] data, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i]);
        }
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private MezzeSeq<?> mapDoubleSeq(DoubleSeqStorage storage, int size) {
        Object[] results = new Object[size];
        double[] data = storage.getArray();

        if (size <= 8) {
            mapDoubleSmall(results, data, size);
        } else {
            mapDoubleLarge(results, data, size);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapDoubleSmall(Object[] results, double[] data, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i]);
        }
    }

    @TruffleBoundary
    private void mapDoubleLarge(Object[] results, double[] data, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i]);
        }
    }

    /**
     * Specialized path for object sequences.
     */
    private MezzeSeq<?> mapObjectSeq(ObjectSeqStorage storage, int size) {
        Object[] results = new Object[size];
        Object[] data = storage.getArray();

        if (size <= 8) {
            mapObjectSmall(results, data, size);
        } else {
            mapObjectLarge(results, data, size);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapObjectSmall(Object[] results, Object[] data, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i]);
        }
    }

    @TruffleBoundary
    private void mapObjectLarge(Object[] results, Object[] data, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i]);
        }
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private MezzeSeq<?> mapGeneric(MezzeSeq<?> seq, int size) {
        Object[] results = new Object[size];

        if (size <= 8) {
            mapGenericSmall(seq, results, size);
        } else {
            mapGenericLarge(seq, results, size);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapGenericSmall(MezzeSeq<?> seq, Object[] results, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i));
        }
    }

    @TruffleBoundary
    private void mapGenericLarge(MezzeSeq<?> seq, Object[] results, int size) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i));
        }
    }

    @ExplodeLoop
    private Object callMapper(Object element) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapX(element);

        if (cachedClosureLength == 0) {
            return callNode.call(new Object[] { arg });
        } else {
            Object[] args = new Object[cachedClosureLength + 1];
            // Closure copy loop - JIT can unroll for small closures
            for (int i = 0; i < cachedClosureLength; i++) {
                args[i] = cachedClosureValues[i];
            }
            args[cachedClosureLength] = arg;
            return callNode.call(args);
        }
    }

    @TruffleBoundary
    private void resolveMapper(MezzeFunction mapper) {
        cachedMapper = mapper;
        cachedClosureValues = mapper.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        callNode = DirectCallNode.create(mapper.getCallTarget());
        insert(callNode);
    }

    public static SeqMapCallNode create(MezzeNode seqNode, MezzeNode mapperNode) {
        return new SeqMapCallNode(seqNode, mapperNode);
    }
}
