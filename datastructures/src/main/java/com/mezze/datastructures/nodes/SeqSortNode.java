package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Arrays;

/**
 * Node for sorting sequences.
 *
 * Uses Arrays.sort which is highly optimized (TimSort for objects,
 * dual-pivot quicksort for primitives).
 *
 * Specializations:
 * - LongSeqStorage: Uses primitive long[] sort (no boxing)
 * - DoubleSeqStorage: Uses primitive double[] sort (no boxing)
 * - ObjectSeqStorage: Uses Comparable-based sort
 */
@NodeInfo(shortName = "seq.sort")
public final class SeqSortNode extends Node {

    public SeqStorage execute(SeqStorage storage) {
        if (storage.isEmpty() || storage.size() == 1) {
            return storage;
        }
        return executeSort(storage);
    }

    @TruffleBoundary
    private SeqStorage executeSort(SeqStorage storage) {
        // Use type-specific sort for unboxed types
        if (storage instanceof LongSeqStorage ls) {
            return ls.sort();
        }
        if (storage instanceof DoubleSeqStorage ds) {
            return ds.sort();
        }

        // For ObjectSeqStorage, use Comparable sort
        int size = storage.size();
        Object[] arr = new Object[size];
        for (int i = 0; i < size; i++) {
            arr[i] = storage.get(i);
        }

        @SuppressWarnings("unchecked")
        Comparable<Object>[] comparables = (Comparable<Object>[]) arr;
        Arrays.sort(comparables);

        return new ObjectSeqStorage(arr);
    }

    public static SeqSortNode create() {
        return new SeqSortNode();
    }
}
