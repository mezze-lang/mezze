package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for zipping two sequences into a sequence of pairs.
 *
 * Truncates to the shorter length.
 * Result is always an ObjectSeqStorage containing pair records.
 */
@NodeInfo(shortName = "seq.zip")
public final class SeqZipNode extends Node {

    // Pair record to hold zipped elements
    public record Pair(Object first, Object second) {}

    public ObjectSeqStorage execute(SeqStorage left, SeqStorage right) {
        if (left.isEmpty() || right.isEmpty()) {
            return new ObjectSeqStorage(new Object[0]);
        }
        return executeZip(left, right);
    }

    @TruffleBoundary
    private ObjectSeqStorage executeZip(SeqStorage left, SeqStorage right) {
        int minLen = Math.min(left.size(), right.size());
        Object[] pairs = new Object[minLen];

        for (int i = 0; i < minLen; i++) {
            pairs[i] = new Pair(left.get(i), right.get(i));
        }

        return new ObjectSeqStorage(pairs);
    }

    public static SeqZipNode create() {
        return new SeqZipNode();
    }
}
