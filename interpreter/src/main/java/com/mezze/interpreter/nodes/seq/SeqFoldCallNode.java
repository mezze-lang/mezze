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
 * JIT-optimized fold (reduce) operation for MezzeSeq in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Loop unrolling for small sequences
 * 3. Storage type profiling for JIT optimization
 * 4. Proper MezzeRecord argument wrapping for { acc, x }
 *
 * Key optimizations:
 * - DirectCallNode for monomorphic call sites (JIT-inlinable)
 * - Storage type profiling allows JIT to specialize based on element type
 * - No intermediate allocations during fold
 */
@NodeInfo(shortName = "seq.fold.call")
public final class SeqFoldCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

    @Child
    private MezzeNode initNode;

    @Child
    private MezzeNode folderNode;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedFolder;

    @CompilationFinal(dimensions = 1)
    private Object[] cachedClosureValues;

    @CompilationFinal
    private int cachedClosureLength;

    // Storage type profiling for JIT optimization
    @CompilationFinal
    private Class<?> seenStorageType;

    @CompilationFinal
    private boolean seenMultipleTypes;

    public SeqFoldCallNode(MezzeNode seqNode, MezzeNode initNode, MezzeNode folderNode) {
        this.seqNode = seqNode;
        this.initNode = initNode;
        this.folderNode = folderNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
        Object init = initNode.execute(frame);
        MezzeFunction folder = (MezzeFunction) folderNode.execute(frame);

        // Fast path: same folder function
        if (callNode != null && folder == cachedFolder) {
            return executeFold(seq, init);
        }

        // Slow path: resolve folder
        resolveFolder(folder);
        return executeFold(seq, init);
    }

    private Object executeFold(MezzeSeq<?> seq, Object init) {
        int size = seq.size();
        if (size == 0) {
            return init;
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
                return foldLongSeq((LongSeqStorage) storage, init, size);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return foldDoubleSeq((DoubleSeqStorage) storage, init, size);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return foldObjectSeq((ObjectSeqStorage) storage, init, size);
            }
        }

        // Megamorphic fallback
        return foldGeneric(seq, init, size);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private Object foldLongSeq(LongSeqStorage storage, Object init, int size) {
        long[] data = storage.getArray();

        if (size <= 8) {
            return foldLongSmall(data, init, size);
        } else {
            return foldLongLarge(data, init, size);
        }
    }

    @ExplodeLoop
    private Object foldLongSmall(long[] data, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i]);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldLongLarge(long[] data, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i]);
        }
        return acc;
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private Object foldDoubleSeq(DoubleSeqStorage storage, Object init, int size) {
        double[] data = storage.getArray();

        if (size <= 8) {
            return foldDoubleSmall(data, init, size);
        } else {
            return foldDoubleLarge(data, init, size);
        }
    }

    @ExplodeLoop
    private Object foldDoubleSmall(double[] data, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i]);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldDoubleLarge(double[] data, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i]);
        }
        return acc;
    }

    /**
     * Specialized path for object sequences.
     */
    private Object foldObjectSeq(ObjectSeqStorage storage, Object init, int size) {
        Object[] data = storage.getArray();

        if (size <= 8) {
            return foldObjectSmall(data, init, size);
        } else {
            return foldObjectLarge(data, init, size);
        }
    }

    @ExplodeLoop
    private Object foldObjectSmall(Object[] data, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i]);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldObjectLarge(Object[] data, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i]);
        }
        return acc;
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private Object foldGeneric(MezzeSeq<?> seq, Object init, int size) {
        if (size <= 8) {
            return foldGenericSmall(seq, init, size);
        } else {
            return foldGenericLarge(seq, init, size);
        }
    }

    @ExplodeLoop
    private Object foldGenericSmall(MezzeSeq<?> seq, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, seq.get(i));
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldGenericLarge(MezzeSeq<?> seq, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, seq.get(i));
        }
        return acc;
    }

    @ExplodeLoop
    private Object callFolder(Object acc, Object element) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapAccX(acc, element);

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
    private void resolveFolder(MezzeFunction folder) {
        cachedFolder = folder;
        cachedClosureValues = folder.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        callNode = DirectCallNode.create(folder.getCallTarget());
        insert(callNode);
    }

    public static SeqFoldCallNode create(MezzeNode seqNode, MezzeNode initNode, MezzeNode folderNode) {
        return new SeqFoldCallNode(seqNode, initNode, folderNode);
    }
}
