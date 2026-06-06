package com.mezze.interpreter.nodes.seq;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.seq.SeqStorage;
import com.mezze.datastructures.seq.LongSeqStorage;
import com.mezze.datastructures.seq.DoubleSeqStorage;
import com.mezze.datastructures.seq.ObjectSeqStorage;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
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

    /**
     * Per-predicate-CallTarget LoopNode-driven path for large LONG-storage
     * filters. Size-1 PIC: one instance per distinct predicate. Lets
     * Truffle's inliner inline the predicate into the OSR'd loop body
     * (the {@link DirectCallNode} lives as {@code @Child} of the
     * repeating node, not passed as a method argument). Same pattern as
     * {@link SeqFoldCallNode#longLoopNode} and
     * {@link SeqMapCallNode#longLoopNode}.
     */
    @Child
    private SeqFilterLongLoopNode longLoopNode;

    /**
     * Body-adoption (real AST inlining) fast path for the canonical
     * single-param closure-free predicate shape. Same idea as
     * {@link SeqFoldCallNode#longInlinedLoopNode}: clone the predicate's
     * body into a repeating node so PE sees a straight-line graph with
     * no call boundary. Falls back to {@link #longLoopNode} when the
     * predicate has closures or doesn't match the shape.
     */
    @Child
    private SeqFilterLongInlinedLoopNode longInlinedLoopNode;

    @Child
    private SeqFilterDoubleLoopNode doubleLoopNode;

    @Child
    private SeqFilterObjectLoopNode objectLoopNode;

    @Child
    private SeqFilterGenericLoopNode genericLoopNode;

    // Cached CallTarget — safe across threads (monomorphic by source position).
    // We deliberately do NOT cache the predicate's MezzeFunction or its
    // closureValues array on the node, because concurrent threads with
    // different predicate lambdas would race on these fields (#173). The
    // per-call closure values are passed as method args.
    @CompilationFinal
    private com.oracle.truffle.api.CallTarget cachedPredicateTarget;

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

        // Per-call closure values — DO NOT cache on the node (see #173).
        Object[] closureValues = predicate.getClosureValues();
        int closureLength = closureValues.length;

        if (callNode == null || predicate.getCallTarget() != cachedPredicateTarget) {
            resolveCallNode(predicate);
        }
        // Re-entrancy snapshot — see #174 part 2. A predicate that
        // transitively calls seq::filter again on the same shared node
        // instance would clobber this.callNode; the local snapshot keeps
        // the outer loop pinned to its own target. LONG-large path is
        // safe via the per-predicate longLoopNode PIC; small/double/
        // object/generic paths now take localCallNode as a parameter.
        DirectCallNode localCallNode = this.callNode;

        return executeFilter(seq, closureValues, closureLength, localCallNode);
    }

    private Object executeFilter(MezzeSeq<?> seq,
                                 Object[] closureValues, int closureLength,
                                 DirectCallNode localCallNode) {
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
                return filterLongSeq((LongSeqStorage) storage, size, closureValues, closureLength, localCallNode);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return filterDoubleSeq((DoubleSeqStorage) storage, size, closureValues, closureLength, localCallNode);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return filterObjectSeq((ObjectSeqStorage) storage, size, closureValues, closureLength, localCallNode);
            }
        }

        // Megamorphic fallback
        return filterGeneric(seq, size, closureValues, closureLength, localCallNode);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private MezzeSeq<?> filterLongSeq(LongSeqStorage storage, int size,
                                      Object[] closureValues, int closureLength,
                                      DirectCallNode localCallNode) {
        long[] data = storage.getArray();
        long[] results = new long[size];
        int count = 0;

        if (size <= 8) {
            count = filterLongSmall(data, results, size, closureValues, closureLength, localCallNode);
        } else {
            count = filterLongLarge(data, results, size, closureValues, closureLength, localCallNode);
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
    private int filterLongSmall(long[] data, long[] results, int size,
                                Object[] closureValues, int closureLength,
                                DirectCallNode localCallNode) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long elem = data[i];
            if (testPredicate(elem, closureValues, closureLength, localCallNode)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    /**
     * Large LONG-storage filter. Pre-fix this was {@code @TruffleBoundary}.
     * Now driven by {@link SeqFilterLongLoopNode}: a Truffle LoopNode
     * with the predicate's {@link DirectCallNode} as {@code @Child} of
     * the repeating node, so Truffle's inliner inlines the predicate
     * into the OSR'd loop body. Re-entrancy-safe via per-predicate PIC.
     */
    private int filterLongLarge(long[] data, long[] results, int size,
                                Object[] closureValues, int closureLength,
                                DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();

        // FAST PATH: body adoption (real AST inlining). When the
        // predicate is the canonical {x} -> bool shape with 1 LONG/OBJECT
        // param and 0 closures, clone the predicate body and execute it
        // in-place per iter — no DirectCallNode boundary, no MezzeRecord
        // alloc, no ABI dispatch.
        if (closureLength == 0) {
            SeqFilterLongInlinedLoopNode inlined = longInlinedLoopNode;
            if (inlined == null || inlined.getPredicateTarget() != target) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (SeqFilterLongInlinedLoopNode.canHandle(target)) {
                    com.mezze.interpreter.nodes.function.MezzeFunctionRootNode mrn =
                        (com.mezze.interpreter.nodes.function.MezzeFunctionRootNode)
                            ((com.oracle.truffle.api.RootCallTarget) target).getRootNode();
                    inlined = new SeqFilterLongInlinedLoopNode(target, mrn);
                    longInlinedLoopNode = insert(inlined);
                } else {
                    inlined = null;
                }
            }
            if (inlined != null) {
                return inlined.execute(data, results, size);
            }
        }

        // FALLBACK: per-predicate LoopNode + DirectCallNode. Used when
        // the predicate has closures or doesn't match the body-adoption
        // shape.
        SeqFilterLongLoopNode loop = longLoopNode;
        if (loop == null || loop.getPredicateTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFilterLongLoopNode(target);
            longLoopNode = insert(loop);
        }
        return loop.execute(data, results, size, closureValues, closureLength);
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
    private MezzeSeq<?> filterDoubleSeq(DoubleSeqStorage storage, int size,
                                        Object[] closureValues, int closureLength,
                                        DirectCallNode localCallNode) {
        double[] data = storage.getArray();
        double[] results = new double[size];
        int count = 0;

        if (size <= 8) {
            count = filterDoubleSmall(data, results, size, closureValues, closureLength, localCallNode);
        } else {
            count = filterDoubleLarge(data, results, size, closureValues, closureLength, localCallNode);
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
    private int filterDoubleSmall(double[] data, double[] results, int size,
                                  Object[] closureValues, int closureLength,
                                  DirectCallNode localCallNode) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            double elem = data[i];
            if (testPredicate(elem, closureValues, closureLength, localCallNode)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    private int filterDoubleLarge(double[] data, double[] results, int size,
                                  Object[] closureValues, int closureLength,
                                  DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();
        SeqFilterDoubleLoopNode loop = doubleLoopNode;
        if (loop == null || loop.getPredicateTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFilterDoubleLoopNode(target);
            doubleLoopNode = insert(loop);
        }
        return loop.execute(data, results, size, closureValues, closureLength);
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
    private MezzeSeq<?> filterObjectSeq(ObjectSeqStorage storage, int size,
                                        Object[] closureValues, int closureLength,
                                        DirectCallNode localCallNode) {
        Object[] data = storage.getArray();
        Object[] results = new Object[size];
        int count = 0;

        if (size <= 8) {
            count = filterObjectSmall(data, results, size, closureValues, closureLength, localCallNode);
        } else {
            count = filterObjectLarge(data, results, size, closureValues, closureLength, localCallNode);
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
    private int filterObjectSmall(Object[] data, Object[] results, int size,
                                  Object[] closureValues, int closureLength,
                                  DirectCallNode localCallNode) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object elem = data[i];
            if (testPredicate(elem, closureValues, closureLength, localCallNode)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    private int filterObjectLarge(Object[] data, Object[] results, int size,
                                  Object[] closureValues, int closureLength,
                                  DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();
        SeqFilterObjectLoopNode loop = objectLoopNode;
        if (loop == null || loop.getPredicateTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFilterObjectLoopNode(target);
            objectLoopNode = insert(loop);
        }
        return loop.execute(data, results, size, closureValues, closureLength);
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private MezzeSeq<?> filterGeneric(MezzeSeq<?> seq, int size,
                                      Object[] closureValues, int closureLength,
                                      DirectCallNode localCallNode) {
        Object[] results = new Object[size];
        int count = 0;

        if (size <= 8) {
            count = filterGenericSmall(seq, results, size, closureValues, closureLength, localCallNode);
        } else {
            count = filterGenericLarge(seq, results, size, closureValues, closureLength, localCallNode);
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
    private int filterGenericSmall(MezzeSeq<?> seq, Object[] results, int size,
                                   Object[] closureValues, int closureLength,
                                   DirectCallNode localCallNode) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            Object elem = seq.get(i);
            if (testPredicate(elem, closureValues, closureLength, localCallNode)) {
                results[count++] = elem;
            }
        }
        return count;
    }

    private int filterGenericLarge(MezzeSeq<?> seq, Object[] results, int size,
                                   Object[] closureValues, int closureLength,
                                   DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();
        SeqFilterGenericLoopNode loop = genericLoopNode;
        if (loop == null || loop.getPredicateTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFilterGenericLoopNode(target);
            genericLoopNode = insert(loop);
        }
        return loop.execute(seq, results, size, closureValues, closureLength);
    }

    @ExplodeLoop
    private boolean testPredicate(Object element,
                                  Object[] closureValues, int closureLength,
                                  DirectCallNode localCallNode) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapX(element);

        Object result;
        if (closureLength == 0) {
            result = localCallNode.call(new Object[] { arg });
        } else {
            Object[] args = new Object[closureLength + 1];
            // Unrolled closure copy for small closures (JIT can unroll this)
            for (int i = 0; i < closureLength; i++) {
                args[i] = closureValues[i];
            }
            args[closureLength] = arg;
            result = localCallNode.call(args);
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
    private void resolveCallNode(MezzeFunction predicate) {
        cachedPredicateTarget = predicate.getCallTarget();
        callNode = DirectCallNode.create(predicate.getCallTarget());
        insert(callNode);
    }

    public static SeqFilterCallNode create(MezzeNode seqNode, MezzeNode predicateNode) {
        return new SeqFilterCallNode(seqNode, predicateNode);
    }
}
