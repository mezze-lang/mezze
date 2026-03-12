package com.mezze.datastructures.nodes;

import com.mezze.datastructures.map.*;
import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.*;

/**
 * Optimized groupBy node for sequences.
 *
 * Groups elements by a key function, producing Map[K, Seq[V]].
 *
 * Uses:
 * - LinkedHashMap for insertion-order preservation
 * - ArrayList collectors that convert to MezzeSeq
 * - DirectCallNode for key function
 */
@NodeInfo(shortName = "seq.groupby")
public abstract class SeqGroupByNode extends Node {

    public abstract MezzeMap<?, ?> execute(MezzeSeq<?> seq, CallTarget keyFn, Object[] closureValues);

    @Specialization(guards = "seq.size() == 0")
    protected MezzeMap<?, ?> groupByEmpty(MezzeSeq<?> seq, CallTarget keyFn, Object[] closureValues) {
        return MezzeMap.empty();
    }

    @Specialization(guards = "seq.size() > 0")
    @TruffleBoundary
    protected MezzeMap<?, ?> groupByNonEmpty(
            MezzeSeq<?> seq,
            CallTarget keyFn,
            Object[] closureValues,
            @Cached("create(keyFn)") DirectCallNode callNode) {

        int size = seq.size();

        // Use LinkedHashMap to preserve insertion order
        Map<Object, List<Object>> groups = new LinkedHashMap<>();

        for (int i = 0; i < size; i++) {
            Object elem = seq.get(i);
            Object[] args = prepareArgs(closureValues, elem);
            Object key = callNode.call(args);

            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(elem);
        }

        // Convert to MezzeMap with MezzeSeq values
        MezzeMap<Object, Object> result = MezzeMap.empty();
        for (Map.Entry<Object, List<Object>> entry : groups.entrySet()) {
            MezzeSeq<?> groupSeq = MezzeSeq.fromArray(entry.getValue().toArray());
            result = result.put(entry.getKey(), groupSeq);
        }

        return result;
    }

    private static Object[] prepareArgs(Object[] closureValues, Object value) {
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = value;
        return args;
    }

    public static SeqGroupByNode create() {
        return SeqGroupByNodeGen.create();
    }

    public static SeqGroupByNode getUncached() {
        return SeqGroupByNodeGen.getUncached();
    }
}
