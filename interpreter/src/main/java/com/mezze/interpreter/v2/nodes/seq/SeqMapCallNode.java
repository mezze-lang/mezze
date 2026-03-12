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
 * JIT-optimized map operation for MezzeSeq in the interpreter.
 *
 * This node provides specialized implementations with:
 * 1. DirectCallNode for monomorphic call sites (JIT-inlinable)
 * 2. Loop unrolling for small sequences
 * 3. Proper MezzeRecord argument wrapping
 *
 * Usage: Used when typechecker provides monomorphized map calls.
 */
@NodeInfo(shortName = "seq.map.call")
public final class SeqMapCallNode extends MezzeNode {

    @Child
    private MezzeNode seqNode;

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

    public SeqMapCallNode(MezzeNode seqNode, MezzeNode mapperNode) {
        this.seqNode = seqNode;
        this.mapperNode = mapperNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        MezzeSeq<?> seq = (MezzeSeq<?>) seqNode.execute(frame);
        MezzeFunction mapper = (MezzeFunction) mapperNode.execute(frame);

        // Fast path: same mapper function
        if (callNode != null && mapper == cachedMapper) {
            return executeMap(seq);
        }

        // Slow path: resolve mapper
        resolveMapper(mapper);
        return executeMap(seq);
    }

    private Object executeMap(MezzeSeq<?> seq) {
        int size = seq.size();
        if (size == 0) {
            return MezzeSeq.empty();
        }

        // Use size-based dispatch
        if (size <= 8) {
            return mapSmall(seq, size);
        } else {
            return mapLarge(seq, size);
        }
    }

    @ExplodeLoop
    private MezzeSeq<?> mapSmall(MezzeSeq<?> seq, int size) {
        Object[] results = new Object[size];
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i));
        }
        return MezzeSeq.fromArray(results);
    }

    @TruffleBoundary
    private MezzeSeq<?> mapLarge(MezzeSeq<?> seq, int size) {
        Object[] results = new Object[size];
        for (int i = 0; i < size; i++) {
            results[i] = callMapper(seq.get(i));
        }
        return MezzeSeq.fromArray(results);
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

    public static SeqMapCallNode create(MezzeNode seqNode, MezzeNode mapperNode) {
        return new SeqMapCallNode(seqNode, mapperNode);
    }
}
