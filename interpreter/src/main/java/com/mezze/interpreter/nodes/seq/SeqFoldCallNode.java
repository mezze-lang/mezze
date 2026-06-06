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

    /**
     * Lazy-initialized LoopNode-driven loop for the large-LONG path. Lives
     * here as a {@code @Child} so PE adopts it into the SeqFoldCallNode
     * tree. We use it for size > 8 LONG-storage sequences: removes the
     * {@code @TruffleBoundary} that previously made the fold loop opaque
     * to PE, and gives the loop body access to OSR.
     */
    @Child
    private SeqFoldLongLoopNode longLoopNode;

    /**
     * Body-adopted (real AST inlining) fast path for the canonical
     * shape: 2 LONG params + 0 closures + Long init. The folder's
     * body AST is cloned and runs in-place per iter — no DirectCallNode
     * boundary, no MezzeRecord alloc, no ABI dispatch. Selected over
     * {@link #longLoopNode} when {@link SeqFoldLongInlinedLoopNode#canHandle}
     * is true.
     */
    @Child
    private SeqFoldLongInlinedLoopNode longInlinedLoopNode;

    @Child
    private SeqFoldDoubleLoopNode doubleLoopNode;

    @Child
    private SeqFoldObjectLoopNode objectLoopNode;

    @Child
    private SeqFoldGenericLoopNode genericLoopNode;

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

        // Resolve the DirectCallNode lazily on first call. Subsequent calls
        // reuse the @Child node UNLESS the folder's CallTarget changes — at
        // which point we update both `cachedFolderTarget` and the node.
        //
        // Re-entrancy hazard (fixed #174 part 2): the same SeqFoldCallNode
        // gets shared by every `seq::fold` call site in compiled stdlib
        // code. When an outer fold's `f` lambda transitively triggers an
        // INNER fold (e.g. `f = {acc, x} -> ... sum_arr {arr = x}` where
        // `sum_arr` calls fold internally), the inner call mutates
        // `cachedFolderTarget` + `callNode` to point at the inner folder.
        // When the outer fold's loop resumes for iteration N+1, the @Child
        // `callNode` is still pointing at the INNER folder — so the wrong
        // function gets called with the outer's args, leading to ABI
        // mismatches and weird runtime errors.
        //
        // Fix: capture the CallTarget we need locally before entering the
        // fold loop, and pass it as a parameter into the loop body. This
        // way each `execute` invocation uses its own target regardless of
        // any concurrent mutation of `cachedFolderTarget`.
        com.oracle.truffle.api.CallTarget targetForThisCall = folder.getCallTarget();
        if (callNode == null || targetForThisCall != cachedFolderTarget) {
            resolveCallNode(folder);
        }
        // Snapshot the DirectCallNode now — if the recursive fold replaces
        // `this.callNode`, our local copy still points at the right target.
        DirectCallNode localCallNode = this.callNode;

        return executeFold(seq, init, closureValues, closureLength, localCallNode);
    }

    private Object executeFold(MezzeSeq<?> seq, Object init,
                               Object[] closureValues, int closureLength, DirectCallNode callNode) {
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
                return foldLongSeq((LongSeqStorage) storage, init, size, closureValues, closureLength, callNode);
            } else if (seenStorageType == DoubleSeqStorage.class) {
                return foldDoubleSeq((DoubleSeqStorage) storage, init, size, closureValues, closureLength, callNode);
            } else if (seenStorageType == ObjectSeqStorage.class) {
                return foldObjectSeq((ObjectSeqStorage) storage, init, size, closureValues, closureLength, callNode);
            }
        }

        // Megamorphic fallback
        return foldGeneric(seq, init, size, closureValues, closureLength, callNode);
    }

    /**
     * Specialized path for long sequences.
     * JIT can optimize knowing elements are longs.
     */
    private Object foldLongSeq(LongSeqStorage storage, Object init, int size,
                               Object[] closureValues, int closureLength, DirectCallNode callNode) {
        long[] data = storage.getArray();

        if (size <= 8) {
            return foldLongSmall(data, init, size, closureValues, closureLength, callNode);
        } else {
            return foldLongLarge(data, init, size, closureValues, closureLength, callNode);
        }
    }

    @ExplodeLoop
    private Object foldLongSmall(long[] data, Object init, int size,
                                 Object[] closureValues, int closureLength, DirectCallNode callNode) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength, callNode);
        }
        return acc;
    }

    private Object foldLongLarge(long[] data, Object init, int size,
                                 Object[] closureValues, int closureLength, DirectCallNode callNode) {
        CallTarget target = callNode.getCallTarget();

        // FAST PATH: body adoption (real AST inlining). When the folder
        // is the canonical {acc, x} -> ... shape with 2 LONG params and
        // 0 closures, AND the init is a Long, we clone the folder's
        // body and run it directly in-place — no DirectCallNode
        // boundary, no MezzeRecord alloc, no ABI dispatch. This is the
        // single biggest closer of the user-lambda dispatch gap for
        // functional-style folds (sum, count-style, arithmetic
        // reductions, etc).
        if (init instanceof Long initLong) {
            SeqFoldLongInlinedLoopNode inlined = longInlinedLoopNode;
            if (inlined == null || inlined.getFolderTarget() != target) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                if (SeqFoldLongInlinedLoopNode.canHandle(target)) {
                    com.mezze.interpreter.nodes.function.MezzeFunctionRootNode mrn =
                        (com.mezze.interpreter.nodes.function.MezzeFunctionRootNode)
                            ((com.oracle.truffle.api.RootCallTarget) target).getRootNode();
                    inlined = new SeqFoldLongInlinedLoopNode(target, mrn);
                    longInlinedLoopNode = insert(inlined);
                } else {
                    // Folder shape doesn't match — fall through to the
                    // DirectCallNode path below.
                    inlined = null;
                }
            }
            if (inlined != null) {
                return inlined.execute(data, initLong, size);
            }
        }

        // FALLBACK: per-folder LoopNode + DirectCallNode (no body
        // adoption). Used when the folder has closures, non-LONG
        // params, or init isn't a Long. The DirectCallNode is a stable
        // @Child of the LoopNode so Truffle's inliner inlines through
        // it (better than passing it as an arg).
        SeqFoldLongLoopNode loop = longLoopNode;
        if (loop == null || loop.getFolderTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFoldLongLoopNode(target);
            longLoopNode = insert(loop);
        }
        return loop.execute(data, init, size, closureValues, closureLength);
    }

    /**
     * Specialized path for double sequences.
     * JIT can optimize knowing elements are doubles.
     */
    private Object foldDoubleSeq(DoubleSeqStorage storage, Object init, int size,
                                 Object[] closureValues, int closureLength, DirectCallNode callNode) {
        double[] data = storage.getArray();

        if (size <= 8) {
            return foldDoubleSmall(data, init, size, closureValues, closureLength, callNode);
        } else {
            return foldDoubleLarge(data, init, size, closureValues, closureLength, callNode);
        }
    }

    @ExplodeLoop
    private Object foldDoubleSmall(double[] data, Object init, int size,
                                   Object[] closureValues, int closureLength, DirectCallNode callNode) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength, callNode);
        }
        return acc;
    }

    private Object foldDoubleLarge(double[] data, Object init, int size,
                                   Object[] closureValues, int closureLength, DirectCallNode callNode) {
        // Per-folder LoopNode instance so the inner DirectCallNode is a
        // stable @Child of the same Truffle tree as the LoopNode that
        // drives the loop. Truffle's inliner only inlines through
        // @Child DirectCallNodes — passing one as an arg defeats it.
        // For monomorphic call sites (the common case) size-1 PIC.
        SeqFoldDoubleLoopNode loop = doubleLoopNode;
        CallTarget target = callNode.getCallTarget();
        if (loop == null || loop.getFolderTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFoldDoubleLoopNode(target);
            doubleLoopNode = insert(loop);
        }
        return loop.execute(data, init, size, closureValues, closureLength);
    }

    /**
     * Specialized path for object sequences.
     */
    private Object foldObjectSeq(ObjectSeqStorage storage, Object init, int size,
                                 Object[] closureValues, int closureLength, DirectCallNode callNode) {
        Object[] data = storage.getArray();

        if (size <= 8) {
            return foldObjectSmall(data, init, size, closureValues, closureLength, callNode);
        } else {
            return foldObjectLarge(data, init, size, closureValues, closureLength, callNode);
        }
    }

    @ExplodeLoop
    private Object foldObjectSmall(Object[] data, Object init, int size,
                                   Object[] closureValues, int closureLength, DirectCallNode callNode) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, data[i], closureValues, closureLength, callNode);
        }
        return acc;
    }

    private Object foldObjectLarge(Object[] data, Object init, int size,
                                   Object[] closureValues, int closureLength, DirectCallNode callNode) {
        // Per-folder LoopNode instance so the inner DirectCallNode is a
        // stable @Child of the same Truffle tree as the LoopNode that
        // drives the loop. Truffle's inliner only inlines through
        // @Child DirectCallNodes — passing one as an arg defeats it.
        // For monomorphic call sites (the common case) size-1 PIC.
        SeqFoldObjectLoopNode loop = objectLoopNode;
        CallTarget target = callNode.getCallTarget();
        if (loop == null || loop.getFolderTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFoldObjectLoopNode(target);
            objectLoopNode = insert(loop);
        }
        return loop.execute(data, init, size, closureValues, closureLength);
    }

    /**
     * Generic fallback for megamorphic sites.
     */
    private Object foldGeneric(MezzeSeq<?> seq, Object init, int size,
                               Object[] closureValues, int closureLength, DirectCallNode callNode) {
        if (size <= 8) {
            return foldGenericSmall(seq, init, size, closureValues, closureLength, callNode);
        } else {
            return foldGenericLarge(seq, init, size, closureValues, closureLength, callNode);
        }
    }

    @ExplodeLoop
    private Object foldGenericSmall(MezzeSeq<?> seq, Object init, int size,
                                    Object[] closureValues, int closureLength, DirectCallNode callNode) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, seq.get(i), closureValues, closureLength, callNode);
        }
        return acc;
    }

    private Object foldGenericLarge(MezzeSeq<?> seq, Object init, int size,
                                    Object[] closureValues, int closureLength, DirectCallNode callNode) {
        // Per-folder LoopNode instance so the inner DirectCallNode is a
        // stable @Child of the same Truffle tree as the LoopNode that
        // drives the loop. Truffle's inliner only inlines through
        // @Child DirectCallNodes — passing one as an arg defeats it.
        // For monomorphic call sites (the common case) size-1 PIC.
        SeqFoldGenericLoopNode loop = genericLoopNode;
        CallTarget target = callNode.getCallTarget();
        if (loop == null || loop.getFolderTarget() != target) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loop = new SeqFoldGenericLoopNode(target);
            genericLoopNode = insert(loop);
        }
        return loop.execute(seq, init, size, closureValues, closureLength);
    }

    @ExplodeLoop
    private Object callFolder(Object acc, Object element,
                              Object[] closureValues, int closureLength, DirectCallNode callNode) {
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
