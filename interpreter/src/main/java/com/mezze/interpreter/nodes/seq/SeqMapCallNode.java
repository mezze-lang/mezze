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

    // Cached CallTarget for monomorphic call sites. Safe to share across
    // threads because the CallTarget is determined by the source position,
    // not by the per-call closureValues. The mapper's closureValues are
    // looked up fresh on every execute() to avoid the race condition that
    // gave rise to #173.
    @CompilationFinal
    private com.oracle.truffle.api.CallTarget cachedMapperTarget;

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

        // Per-call closure values — DO NOT cache on the node, because the
        // node is shared across threads. Closure values come from the
        // mapper instance, which is per-call. Race condition #173 was
        // caused by two concurrent threads overwriting each other's
        // cached `cachedClosureValues`.
        Object[] closureValues = mapper.getClosureValues();
        int closureLength = closureValues.length;

        // Cache the DirectCallNode for the mapper's CallTarget. The
        // CallTarget is monomorphic across threads (same source position
        // → same CallTarget after content-hash dedup), so this cache is
        // safe to share.
        if (callNode == null || mapper.getCallTarget() != cachedMapperTarget) {
            resolveCallNode(mapper);
        }

        return executeMap(seq, closureValues, closureLength);
    }

    private Object executeMap(MezzeSeq<?> seq, Object[] closureValues, int closureLength) {
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
                return mapLongSeq((LongSeqStorage) storage, size, closureValues, closureLength);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return mapDoubleSeq((DoubleSeqStorage) storage, size, closureValues, closureLength);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return mapObjectSeq((ObjectSeqStorage) storage, size, closureValues, closureLength);
            }
        }

        // Megamorphic fallback
        return mapGeneric(seq, size, closureValues, closureLength);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private MezzeSeq<?> mapLongSeq(LongSeqStorage storage, int size,
                                   Object[] closureValues, int closureLength) {
        Object[] results = new Object[size];
        long[] data = storage.getArray();

        if (size <= 8) {
            mapLongSmall(results, data, size, closureValues, closureLength);
        } else {
            mapLongLarge(results, data, size, closureValues, closureLength);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapLongSmall(Object[] results, long[] data, int size,
                              Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength);
        }
    }

    @TruffleBoundary
    private void mapLongLarge(Object[] results, long[] data, int size,
                              Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength);
        }
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private MezzeSeq<?> mapDoubleSeq(DoubleSeqStorage storage, int size,
                                     Object[] closureValues, int closureLength) {
        Object[] results = new Object[size];
        double[] data = storage.getArray();

        if (size <= 8) {
            mapDoubleSmall(results, data, size, closureValues, closureLength);
        } else {
            mapDoubleLarge(results, data, size, closureValues, closureLength);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapDoubleSmall(Object[] results, double[] data, int size,
                                Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength);
        }
    }

    @TruffleBoundary
    private void mapDoubleLarge(Object[] results, double[] data, int size,
                                Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength);
        }
    }

    /**
     * Specialized path for object sequences.
     */
    private MezzeSeq<?> mapObjectSeq(ObjectSeqStorage storage, int size,
                                     Object[] closureValues, int closureLength) {
        Object[] results = new Object[size];
        Object[] data = storage.getArray();

        if (size <= 8) {
            mapObjectSmall(results, data, size, closureValues, closureLength);
        } else {
            mapObjectLarge(results, data, size, closureValues, closureLength);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapObjectSmall(Object[] results, Object[] data, int size,
                                Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength);
        }
    }

    @TruffleBoundary
    private void mapObjectLarge(Object[] results, Object[] data, int size,
                                Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength);
        }
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private MezzeSeq<?> mapGeneric(MezzeSeq<?> seq, int size,
                                   Object[] closureValues, int closureLength) {
        Object[] results = new Object[size];

        if (size <= 8) {
            mapGenericSmall(seq, results, size, closureValues, closureLength);
        } else {
            mapGenericLarge(seq, results, size, closureValues, closureLength);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapGenericSmall(MezzeSeq<?> seq, Object[] results, int size,
                                 Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i), closureValues, closureLength);
        }
    }

    @TruffleBoundary
    private void mapGenericLarge(MezzeSeq<?> seq, Object[] results, int size,
                                 Object[] closureValues, int closureLength) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i), closureValues, closureLength);
        }
    }

    @ExplodeLoop
    private Object callMapper(Object element, Object[] closureValues, int closureLength) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapX(element);

        if (closureLength == 0) {
            return callNode.call(new Object[] { arg });
        } else {
            Object[] args = new Object[closureLength + 1];
            // Closure copy loop - JIT can unroll for small closures
            for (int i = 0; i < closureLength; i++) {
                args[i] = closureValues[i];
            }
            args[closureLength] = arg;
            return callNode.call(args);
        }
    }

    @TruffleBoundary
    private void resolveCallNode(MezzeFunction mapper) {
        cachedMapperTarget = mapper.getCallTarget();
        callNode = DirectCallNode.create(mapper.getCallTarget());
        insert(callNode);
    }

    public static SeqMapCallNode create(MezzeNode seqNode, MezzeNode mapperNode) {
        return new SeqMapCallNode(seqNode, mapperNode);
    }
}
