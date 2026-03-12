package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for taking first N elements from a sequence.
 *
 * Uses slice operations which preserves storage type when possible.
 */
@NodeInfo(shortName = "seq.take")
public final class SeqTakeNode extends Node {

    public SeqStorage execute(SeqStorage storage, int n) {
        if (n <= 0) {
            return EmptySeqStorage.INSTANCE;
        }
        if (n >= storage.size()) {
            return storage;
        }
        return executeSlice(storage, n);
    }

    @TruffleBoundary
    private SeqStorage executeSlice(SeqStorage storage, int n) {
        return storage.slice(0, n);
    }

    public static SeqTakeNode create() {
        return new SeqTakeNode();
    }
}
