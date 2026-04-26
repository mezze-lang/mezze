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
 * JIT-optimized filter operation for MezzeList in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Two-pass algorithm (count then collect) to avoid ArrayList
 * 3. Loop unrolling for small lists
 * 4. Pre-cached record shapes via MezzeRecord.wrapX
 */
@NodeInfo(shortName = "list.filter.call")
public final class ListFilterCallNode extends MezzeNode {

    @Child
    private MezzeNode listNode;

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

    public ListFilterCallNode(MezzeNode listNode, MezzeNode predicateNode) {
        this.listNode = listNode;
        this.predicateNode = predicateNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeList list = (MezzeList) listNode.execute(frame);
        MezzeFunction predicate = (MezzeFunction) predicateNode.execute(frame);

        // Fast path: same predicate function (monomorphic)
        if (callNode != null && predicate == cachedPredicate) {
            return executeFilter(list);
        }

        // Slow path: resolve predicate
        resolvePredicate(predicate);
        return executeFilter(list);
    }

    private MezzeList executeFilter(MezzeList list) {
        int size = list.size();
        if (size == 0) {
            return list;
        }

        // Use size-based dispatch for loop unrolling
        if (size <= 8) {
            return filterSmall(list, size);
        } else {
            return filterLarge(list, size);
        }
    }

    @ExplodeLoop
    private MezzeList filterSmall(MezzeList list, int size) {
        // Two-pass: first count matching elements
        int count = 0;
        boolean[] matches = new boolean[size];
        for (int i = 0; i < size; i++) {
            Object result = callPredicate(list.get(i));
            if (result instanceof Boolean && (Boolean) result) {
                matches[i] = true;
                count++;
            }
        }

        if (count == 0) {
            return MezzeList.empty();
        }
        if (count == size) {
            return list;
        }

        // Second pass: collect matching elements
        Object[] filtered = new Object[count];
        int idx = 0;
        for (int i = 0; i < size; i++) {
            if (matches[i]) {
                filtered[idx++] = list.get(i);
            }
        }
        return MezzeList.of(filtered);
    }

    @TruffleBoundary
    private MezzeList filterLarge(MezzeList list, int size) {
        // Two-pass: first count matching elements
        int count = 0;
        boolean[] matches = new boolean[size];
        for (int i = 0; i < size; i++) {
            Object result = callPredicate(list.get(i));
            if (result instanceof Boolean && (Boolean) result) {
                matches[i] = true;
                count++;
            }
        }

        if (count == 0) {
            return MezzeList.empty();
        }
        if (count == size) {
            return list;
        }

        // Second pass: collect matching elements
        Object[] filtered = new Object[count];
        int idx = 0;
        for (int i = 0; i < size; i++) {
            if (matches[i]) {
                filtered[idx++] = list.get(i);
            }
        }
        return MezzeList.of(filtered);
    }

    @ExplodeLoop
    private Object callPredicate(Object element) {
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
    private void resolvePredicate(MezzeFunction predicate) {
        cachedPredicate = predicate;
        cachedClosureValues = predicate.getClosureValues();
        cachedClosureLength = cachedClosureValues.length;
        callNode = DirectCallNode.create(predicate.getCallTarget());
        insert(callNode);
    }

    public static ListFilterCallNode create(MezzeNode listNode, MezzeNode predicateNode) {
        return new ListFilterCallNode(listNode, predicateNode);
    }
}
