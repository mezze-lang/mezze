package com.mezze.interpreter.nodes.list;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * JIT-optimized fold (reduce) operation for MezzeList in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Loop unrolling for small lists
 * 3. No intermediate allocations during fold
 * 4. Pre-cached record shapes via MezzeRecord.wrapAccX
 */
@NodeInfo(shortName = "list.fold.call")
public final class ListFoldCallNode extends MezzeNode {

    @Child
    private MezzeNode listNode;

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

    public ListFoldCallNode(MezzeNode listNode, MezzeNode initNode, MezzeNode folderNode) {
        this.listNode = listNode;
        this.initNode = initNode;
        this.folderNode = folderNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeList list = (MezzeList) listNode.execute(frame);
        Object init = initNode.execute(frame);
        MezzeFunction folder = (MezzeFunction) folderNode.execute(frame);

        // Fast path: same folder function (monomorphic)
        if (callNode != null && folder == cachedFolder) {
            return executeFold(list, init);
        }

        // Slow path: resolve folder
        resolveFolder(folder);
        return executeFold(list, init);
    }

    private Object executeFold(MezzeList list, Object init) {
        int size = list.size();
        if (size == 0) {
            return init;
        }

        // Use size-based dispatch for loop unrolling
        if (size <= 8) {
            return foldSmall(list, init, size);
        } else {
            return foldLarge(list, init, size);
        }
    }

    @ExplodeLoop
    private Object foldSmall(MezzeList list, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, list.get(i));
        }
        return acc;
    }

    @TruffleBoundary
    private Object foldLarge(MezzeList list, Object init, int size) {
        Object acc = init;
        for (int i = 0; i < size; i++) {
            acc = callFolder(acc, list.get(i));
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

    public static ListFoldCallNode create(MezzeNode listNode, MezzeNode initNode, MezzeNode folderNode) {
        return new ListFoldCallNode(listNode, initNode, folderNode);
    }
}
