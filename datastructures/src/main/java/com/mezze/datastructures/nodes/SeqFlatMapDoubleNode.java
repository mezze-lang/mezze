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
 * Optimized flatMap node for double sequences.
 *
 * Maps each element to a DoubleSeqStorage and flattens the results.
 * Critical for monadic operations.
 *
 * Uses:
 * - Two-pass approach: count then allocate
 * - Pre-sized result array
 * - DirectCallNode with inline caching
 */
@NodeInfo(shortName = "seq.flatmap.double")
public abstract class SeqFlatMapDoubleNode extends Node {

    public abstract DoubleSeqStorage execute(DoubleSeqStorage storage, CallTarget mapper, Object[] closureValues);

    @Specialization(guards = "storage.size() == 0")
    protected DoubleSeqStorage flatMapEmpty(DoubleSeqStorage storage, CallTarget mapper, Object[] closureValues) {
        return storage;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected DoubleSeqStorage flatMapTiny(
            DoubleSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();

        // First pass: collect all result sequences
        DoubleSeqStorage[] results = new DoubleSeqStorage[size];
        int totalSize = 0;
        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            DoubleSeqStorage result = extractDoubleStorage(callNode.call(args));
            results[i] = result;
            totalSize += result.size();
        }

        // Second pass: flatten into single array
        return flattenToArray(results, totalSize);
    }

    @Specialization(guards = "storage.size() > 4", replaces = "flatMapTiny")
    protected DoubleSeqStorage flatMapLarge(
            DoubleSeqStorage storage,
            CallTarget mapper,
            Object[] closureValues,
            @Cached("create(mapper)") DirectCallNode callNode) {

        int size = storage.size();
        double[] data = storage.getArray();

        // First pass: collect all result sequences
        DoubleSeqStorage[] results = new DoubleSeqStorage[size];
        int totalSize = 0;
        for (int i = 0; i < size; i++) {
            Object[] args = prepareArgs(closureValues, data[i]);
            DoubleSeqStorage result = extractDoubleStorage(callNode.call(args));
            results[i] = result;
            totalSize += result.size();
        }

        // Second pass: flatten into single array
        return flattenToArray(results, totalSize);
    }

    // No @TruffleBoundary - let JIT inline this simple array copy
    @ExplodeLoop
    private static Object[] prepareArgs(Object[] closureValues, double value) {
        int len = closureValues.length;
        Object[] args = new Object[len + 1];
        for (int i = 0; i < len; i++) {
            args[i] = closureValues[i];
        }
        args[len] = value;
        return args;
    }

    private static DoubleSeqStorage extractDoubleStorage(Object value) {
        if (value instanceof MezzeSeq seq) {
            SeqStorage storage = seq.getStorage();
            if (storage instanceof DoubleSeqStorage ds) {
                return ds;
            }
        }
        if (value instanceof DoubleSeqStorage ds) {
            return ds;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new IllegalStateException("Expected DoubleSeqStorage but got " + value.getClass());
    }

    private static DoubleSeqStorage flattenToArray(DoubleSeqStorage[] results, int totalSize) {
        if (totalSize == 0) {
            return new DoubleSeqStorage(new double[0]);
        }

        double[] flattened = new double[totalSize];
        int offset = 0;
        for (DoubleSeqStorage result : results) {
            int len = result.size();
            if (len > 0) {
                System.arraycopy(result.getArray(), 0, flattened, offset, len);
                offset += len;
            }
        }
        return new DoubleSeqStorage(flattened);
    }

    public static SeqFlatMapDoubleNode create() {
        return SeqFlatMapDoubleNodeGen.create();
    }

    public static SeqFlatMapDoubleNode getUncached() {
        return SeqFlatMapDoubleNodeGen.getUncached();
    }
}
