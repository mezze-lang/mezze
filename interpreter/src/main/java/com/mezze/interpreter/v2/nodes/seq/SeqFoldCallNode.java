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
 * JIT-optimized fold (reduce) operation for MezzeSeq in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Loop unrolling for small sequences
 * 3. No intermediate allocations during fold
 * 4. Proper MezzeRecord argument wrapping for { acc, x }
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

        // Use size-based dispatch
        if (size <= 8) {
            return foldSmall(seq, init, size);
        } else {
            return foldLarge(seq, init, size);
        }
    }

    @ExplodeLoop
    private Object foldSmall(MezzeSeq<?> seq, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, seq.get(i));
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldLarge(MezzeSeq<?> seq, Object init, int size) {
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
