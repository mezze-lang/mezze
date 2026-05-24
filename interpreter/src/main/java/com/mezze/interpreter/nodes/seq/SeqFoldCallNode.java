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

    // Cached CallTarget — safe across threads (monomorphic by source position).
    // We deliberately do NOT cache the folder's MezzeFunction or its
    // closureValues array on the node, because concurrent threads with
    // different folder lambdas would race on these fields (#173). The
    // per-call closure values are passed as method args.
    @CompilationFinal
    private com.oracle.truffle.api.CallTarget cachedFolderTarget;

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

        // Per-call closure values — DO NOT cache on the node (see #173).
        Object[] closureValues = folder.getClosureValues();
        int closureLength = closureValues.length;

        if (callNode == null || folder.getCallTarget() != cachedFolderTarget) {
            resolveCallNode(folder);
        }

        return executeFold(seq, init, closureValues, closureLength);
    }

    private Object executeFold(MezzeSeq<?> seq, Object init,
                               Object[] closureValues, int closureLength) {
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
                return foldLongSeq((LongSeqStorage) storage, init, size, closureValues, closureLength);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return foldDoubleSeq((DoubleSeqStorage) storage, init, size, closureValues, closureLength);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return foldObjectSeq((ObjectSeqStorage) storage, init, size, closureValues, closureLength);
            }
        }

        // Megamorphic fallback
        return foldGeneric(seq, init, size, closureValues, closureLength);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private Object foldLongSeq(LongSeqStorage storage, Object init, int size,
                               Object[] closureValues, int closureLength) {
        long[] data = storage.getArray();

        if (size <= 8) {
            return foldLongSmall(data, init, size, closureValues, closureLength);
        } else {
            return foldLongLarge(data, init, size, closureValues, closureLength);
        }
    }

    @ExplodeLoop
    private Object foldLongSmall(long[] data, Object init, int size,
                                 Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldLongLarge(long[] data, Object init, int size,
                                 Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength);
        }
        return acc;
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private Object foldDoubleSeq(DoubleSeqStorage storage, Object init, int size,
                                 Object[] closureValues, int closureLength) {
        double[] data = storage.getArray();

        if (size <= 8) {
            return foldDoubleSmall(data, init, size, closureValues, closureLength);
        } else {
            return foldDoubleLarge(data, init, size, closureValues, closureLength);
        }
    }

    @ExplodeLoop
    private Object foldDoubleSmall(double[] data, Object init, int size,
                                   Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldDoubleLarge(double[] data, Object init, int size,
                                   Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength);
        }
        return acc;
    }

    /**
     * Specialized path for object sequences.
     */
    private Object foldObjectSeq(ObjectSeqStorage storage, Object init, int size,
                                 Object[] closureValues, int closureLength) {
        Object[] data = storage.getArray();

        if (size <= 8) {
            return foldObjectSmall(data, init, size, closureValues, closureLength);
        } else {
            return foldObjectLarge(data, init, size, closureValues, closureLength);
        }
    }

    @ExplodeLoop
    private Object foldObjectSmall(Object[] data, Object init, int size,
                                   Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldObjectLarge(Object[] data, Object init, int size,
                                   Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength);
        }
        return acc;
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private Object foldGeneric(MezzeSeq<?> seq, Object init, int size,
                               Object[] closureValues, int closureLength) {
        if (size <= 8) {
            return foldGenericSmall(seq, init, size, closureValues, closureLength);
        } else {
            return foldGenericLarge(seq, init, size, closureValues, closureLength);
        }
    }

    @ExplodeLoop
    private Object foldGenericSmall(MezzeSeq<?> seq, Object init, int size,
                                    Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, seq.get(i), closureValues, closureLength);
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldGenericLarge(MezzeSeq<?> seq, Object init, int size,
                                    Object[] closureValues, int closureLength) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, seq.get(i), closureValues, closureLength);
        }
        return acc;
    }

    @ExplodeLoop
    private Object callFolder(Object acc, Object element,
                              Object[] closureValues, int closureLength) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapAccX(acc, element);

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
    private void resolveCallNode(MezzeFunction folder) {
        cachedFolderTarget = folder.getCallTarget();
        callNode = DirectCallNode.create(folder.getCallTarget());
        insert(callNode);
    }

    public static SeqFoldCallNode create(MezzeNode seqNode, MezzeNode initNode, MezzeNode folderNode) {
        return new SeqFoldCallNode(seqNode, initNode, folderNode);
    }
}
