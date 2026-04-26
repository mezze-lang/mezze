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
 * JIT-optimized map operation for MezzeList in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Loop unrolling for small lists
 * 3. Pre-cached record shapes via MezzeRecord.wrapX
 * 4. No @TruffleBoundary in hot paths
 */
@NodeInfo(shortName = "list.map.call")
public final class ListMapCallNode extends MezzeNode {

    @Child
    private MezzeNode listNode;

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

    public ListMapCallNode(MezzeNode listNode, MezzeNode mapperNode) {
        this.listNode = listNode;
        this.mapperNode = mapperNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeList list = (MezzeList) listNode.execute(frame);
        MezzeFunction mapper = (MezzeFunction) mapperNode.execute(frame);

        // Fast path: same mapper function (monomorphic)
        if (callNode != null && mapper == cachedMapper) {
            return executeMap(list);
        }

        // Slow path: resolve mapper
        resolveMapper(mapper);
        return executeMap(list);
    }

    private MezzeList executeMap(MezzeList list) {
        int size = list.size();
        if (size == 0) {
            return list;
        }

        // Use size-based dispatch for loop unrolling
        if (size <= 8) {
            return mapSmall(list, size);
        } else {
            return mapLarge(list, size);
        }
    }

    @ExplodeLoop
    private MezzeList mapSmall(MezzeList list, int size) {
        Object[] results = new Object[size];
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(list.get(i));
        }
        return MezzeList.of(results);
    }

    @TruffleBoundary
    private MezzeList mapLarge(MezzeList list, int size) {
        Object[] results = new Object[size];
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(list.get(i));
        }
        return MezzeList.of(results);
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

    public static ListMapCallNode create(MezzeNode listNode, MezzeNode mapperNode) {
        return new ListMapCallNode(listNode, mapperNode);
    }
}
