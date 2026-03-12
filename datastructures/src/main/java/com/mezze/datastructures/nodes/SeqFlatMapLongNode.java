package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized flatMap node for long sequences.
 *
 * Maps each element to a LongSeqStorage and flattens the results.
 * Critical for monadic operations.
 *
 * Uses:
 * - Two-pass approach: count then allocate
 * - Pre-sized result array
 * - DirectCallNode with inline caching
 */
@NodeInfo(shortName = "seq.flatmap.long")
public abstract class SeqFlatMapLongNode extends Node {

    public abstract LongSeqStorage execute(LongSeqStorage storage, CallTarget mapper, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected LongSeqStorage flatMapEmpty(LongSeqStorage storage, CallTarget mapper, Object[] closureValues) {
        return storage;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected LongSeqStorage flatMapTiny(
            LongSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();

        // First pass: collect all result sequences
        LongSeqStorage[] results = new LongSeqStorage[size];
        int totalSize = 0;
        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            LongSeqStorage result = extractLongStorage(callNode.call(args));
            results[i] = result;
            totalSize += result.size();
        }

        // Second pass: flatten into single array
        return flattenToArray(results, totalSize);
    }

    @Specialization(guards = "storage.size() > 4", replaces = "flatMapTiny")
    protected LongSeqStorage flatMapLarge(
            LongSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        long[] data = storage.getArray();

        // First pass: collect all result sequences
        LongSeqStorage[] results = new LongSeqStorage[size];
        int totalSize = 0;
        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            LongSeqStorage result = extractLongStorage(callNode.call(args));
            results[i] = result;
            totalSize += result.size();
        }

        // Second pass: flatten into single array
        return flattenToArray(results, totalSize);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, long value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value;
        return args;
    }

    private static LongSeqStorage extractLongStorage(Object value) {
        if (value instanceof MezzeSeq seq) {
            SeqStorage storage = seq.getStorage();
            if (storage instanceof LongSeqStorage ls) {
                return ls;
            }
        }
        if (value instanceof LongSeqStorage ls) {
            return ls;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new IllegalStateException("Expected LongSeqStorage but got " + value.getClass());
    }

    private static LongSeqStorage flattenToArray(LongSeqStorage[] results, int totalSize) {
        if (totalSize == 0) {
            return new LongSeqStorage(new long[0]);
        }

        long[] flattened = new long[totalSize];
        int offset = 0;
        for (LongSeqStorage result : results) {
            int len = result.size();
            if (len > 0) {
                System.arraycopy(result.getArray(), 0, flattened, offset, len);
                offset += len;
            }
        }
        return new LongSeqStorage(flattened);
    }

    public static SeqFlatMapLongNode create() {
        return SeqFlatMapLongNodeGen.create();
    }

    public static SeqFlatMapLongNode getUncached() {
        return SeqFlatMapLongNodeGen.getUncached();
    }
}
