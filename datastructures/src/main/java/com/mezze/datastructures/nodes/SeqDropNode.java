package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for dropping first N elements from a sequence.
 *
 * Uses slice operations which preserves storage type when possible.
 */
@NodeInfo(shortName = "seq.drop")
public final class SeqDropNode extends Node {

    public SeqStorage execute(SeqStorage storage, int n) {
        if (n <= 0) {
            return storage;
        }
        if (n >= storage.size()) {
            return EmptySeqStorage.INSTANCE;
        }
        return executeSlice(storage, n);
    }

    @TruffleBoundary
    private SeqStorage executeSlice(SeqStorage storage, int n) {
        return storage.slice(n, storage.size());
    }

    public static SeqDropNode create() {
        return new SeqDropNode();
    }
}
