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

    /**
     * Per-mapper-CallTarget LoopNode-driven path for large LONG-storage
     * maps. Size-1 PIC: one instance per distinct mapper. Lets Truffle's
     * inliner inline the mapper into the OSR'd loop body (the
     * {@link DirectCallNode} lives as {@code @Child} of the repeating
     * node, not passed as a method argument). Mirrors the fix shipped
     * for {@link SeqFoldCallNode}.
     */
    @Child
    private SeqMapLongLoopNode longLoopNode;

    /**
     * Body-adopted (real AST inlining) fast path for the canonical
     * mapper shape: 1 param + 0 closures. The mapper's body AST is
     * cloned and runs in-place per iter — no DirectCallNode boundary,
     * no MezzeRecord alloc, no ABI dispatch. Selected over
     * {@link #longLoopNode} when {@link SeqMapLongInlinedLoopNode#canHandle}
     * is true.
     */
    @Child
    private SeqMapLongInlinedLoopNode longInlinedLoopNode;

    @Child
    private SeqMapDoubleLoopNode doubleLoopNode;

    @Child
    private SeqMapObjectLoopNode objectLoopNode;

    @Child
    private SeqMapGenericLoopNode genericLoopNode;

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
        // Re-entrancy snapshot — see #174 part 2 / SeqFoldCallNode. When
        // a user mapper transitively calls seq::map again on the same
        // node instance, the inner call replaces this.callNode. Without
        // a local snapshot the outer loop would resume with the wrong
        // target. Captured here so executeMap's small @ExplodeLoop paths
        // (which read this.callNode via callMapper) keep using the
        // outer-call's target.
        DirectCallNode localCallNode = this.callNode;

        return executeMap(seq, closureValues, closureLength, localCallNode);
    }

    private Object executeMap(MezzeSeq<?> seq, Object[] closureValues, int closureLength,
                              DirectCallNode localCallNode) {
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
                return mapLongSeq((LongSeqStorage) storage, size, closureValues, closureLength, localCallNode);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return mapDoubleSeq((DoubleSeqStorage) storage, size, closureValues, closureLength, localCallNode);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return mapObjectSeq((ObjectSeqStorage) storage, size, closureValues, closureLength, localCallNode);
            }
        }

        // Megamorphic fallback
        return mapGeneric(seq, size, closureValues, closureLength, localCallNode);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private MezzeSeq<?> mapLongSeq(LongSeqStorage storage, int size,
                                   Object[] closureValues, int closureLength,
                                   DirectCallNode localCallNode) {
        Object[] results = new Object[size];
        long[] data = storage.getArray();

        if (size <= 8) {
            mapLongSmall(results, data, size, closureValues, closureLength, localCallNode);
        } else {
            mapLongLarge(results, data, size, closureValues, closureLength, localCallNode);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapLongSmall(Object[] results, long[] data, int size,
                              Object[] closureValues, int closureLength,
                              DirectCallNode localCallNode) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength, localCallNode);
        }
    }

    /**
     * Large LONG-storage map. Pre-fix this was {@code @TruffleBoundary} —
     * opaque to PE; every iter paid an interpreter→JIT boundary into the
     * (separately compiled) mapper, and the per-iter wrapX record +
     * Object[] + Long-boxing allocs couldn't be escape-analyzed away.
     *
     * <p>Now driven by {@link SeqMapLongLoopNode}: a Truffle
     * {@link com.oracle.truffle.api.nodes.LoopNode} holds an inner
     * {@link DirectCallNode} as {@code @Child} so Truffle's inliner
     * inlines the mapper into the OSR'd loop body
     * ({@code <OSR>} compilation with {@code inlinedCalls=2}).
     *
     * <p>Re-entrancy: per-mapper-target PIC (size 1) — recursive maps
     * with different mappers get separate {@link SeqMapLongLoopNode}
     * instances, so the cached {@link DirectCallNode} never gets
     * clobbered by an inner call (the #174 part 2 issue).
     */
    private void mapLongLarge(Object[] results, long[] data, int size,
                              Object[] closureValues, int closureLength,
                              DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();

        // FAST PATH: body adoption (real AST inlining). When the mapper
        // is the canonical {x} -> ... shape with 1 param and 0 closures,
        // we clone the mapper's body and run it directly in-place — no
        // DirectCallNode boundary, no MezzeRecord alloc, no ABI dispatch.
        // Single biggest closer of the user-lambda dispatch gap for
        // functional-style maps (x * 2, etc).
        if (closureLength == 0) {
            SeqMapLongInlinedLoopNode inlined = longInlinedLoopNode;
            if (inlined == null || inlined.getMapperTarget() != target) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (SeqMapLongInlinedLoopNode.canHandle(target)) {
                    com.mezze.interpreter.nodes.function.MezzeFunctionRootNode mrn =
                        (com.mezze.interpreter.nodes.function.MezzeFunctionRootNode)
                            ((com.oracle.truffle.api.RootCallTarget) target).getRootNode();
                    inlined = new SeqMapLongInlinedLoopNode(target, mrn);
                    longInlinedLoopNode = insert(inlined);
                } else {
                    inlined = null;
                }
            }
            if (inlined != null) {
                inlined.execute(data, results, size);
                return;
            }
        }

        // FALLBACK: per-mapper LoopNode + DirectCallNode (no body
        // adoption). Used when the mapper has closures or non-matching
        // shape. The DirectCallNode is a stable @Child of the LoopNode
        // so Truffle's inliner inlines through it.
        SeqMapLongLoopNode loop = longLoopNode;
        if (loop == null || loop.getMapperTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqMapLongLoopNode(target);
            longLoopNode = insert(loop);
        }
        loop.execute(data, results, size, closureValues, closureLength);
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private MezzeSeq<?> mapDoubleSeq(DoubleSeqStorage storage, int size,
                                     Object[] closureValues, int closureLength,
                                     DirectCallNode localCallNode) {
        Object[] results = new Object[size];
        double[] data = storage.getArray();

        if (size <= 8) {
            mapDoubleSmall(results, data, size, closureValues, closureLength, localCallNode);
        } else {
            mapDoubleLarge(results, data, size, closureValues, closureLength, localCallNode);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapDoubleSmall(Object[] results, double[] data, int size,
                                Object[] closureValues, int closureLength,
                                DirectCallNode localCallNode) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength, localCallNode);
        }
    }

    private void mapDoubleLarge(Object[] results, double[] data, int size,
                                Object[] closureValues, int closureLength,
                                DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();
        SeqMapDoubleLoopNode loop = doubleLoopNode;
        if (loop == null || loop.getMapperTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqMapDoubleLoopNode(target);
            doubleLoopNode = insert(loop);
        }
        loop.execute(data, results, size, closureValues, closureLength);
    }

    /**
     * Specialized path for object sequences.
     */
    private MezzeSeq<?> mapObjectSeq(ObjectSeqStorage storage, int size,
                                     Object[] closureValues, int closureLength,
                                     DirectCallNode localCallNode) {
        Object[] results = new Object[size];
        Object[] data = storage.getArray();

        if (size <= 8) {
            mapObjectSmall(results, data, size, closureValues, closureLength, localCallNode);
        } else {
            mapObjectLarge(results, data, size, closureValues, closureLength, localCallNode);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapObjectSmall(Object[] results, Object[] data, int size,
                                Object[] closureValues, int closureLength,
                                DirectCallNode localCallNode) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(data[i], closureValues, closureLength, localCallNode);
        }
    }

    private void mapObjectLarge(Object[] results, Object[] data, int size,
                                Object[] closureValues, int closureLength,
                                DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();
        SeqMapObjectLoopNode loop = objectLoopNode;
        if (loop == null || loop.getMapperTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqMapObjectLoopNode(target);
            objectLoopNode = insert(loop);
        }
        loop.execute(data, results, size, closureValues, closureLength);
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private MezzeSeq<?> mapGeneric(MezzeSeq<?> seq, int size,
                                   Object[] closureValues, int closureLength,
                                   DirectCallNode localCallNode) {
        Object[] results = new Object[size];

        if (size <= 8) {
            mapGenericSmall(seq, results, size, closureValues, closureLength, localCallNode);
        } else {
            mapGenericLarge(seq, results, size, closureValues, closureLength, localCallNode);
        }

        return MezzeSeq.fromArray(results);
    }

    @ExplodeLoop
    private void mapGenericSmall(MezzeSeq<?> seq, Object[] results, int size,
                                 Object[] closureValues, int closureLength,
                                 DirectCallNode localCallNode) {
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i), closureValues, closureLength, localCallNode);
        }
    }

    private void mapGenericLarge(MezzeSeq<?> seq, Object[] results, int size,
                                 Object[] closureValues, int closureLength,
                                 DirectCallNode localCallNode) {
        CallTarget target = localCallNode.getCallTarget();
        SeqMapGenericLoopNode loop = genericLoopNode;
        if (loop == null || loop.getMapperTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqMapGenericLoopNode(target);
            genericLoopNode = insert(loop);
        }
        loop.execute(seq, results, size, closureValues, closureLength);
    }

    @ExplodeLoop
    private Object callMapper(Object element, Object[] closureValues, int closureLength,
                              DirectCallNode localCallNode) {
        // Use fast wrapper - no @TruffleBoundary, pre-cached shape
        MezzeRecord arg = MezzeRecord.wrapX(element);

        if (closureLength == 0) {
            return localCallNode.call(new Object[] { arg });
        } else {
            Object[] args = new Object[closureLength + 1];
            // Closure copy loop - JIT can unroll for small closures
            for (int i = 0; i < closureLength; i++) {
                args[i] = closureValues[i];
            }
            args[closureLength] = arg;
            return localCallNode.call(args);
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
