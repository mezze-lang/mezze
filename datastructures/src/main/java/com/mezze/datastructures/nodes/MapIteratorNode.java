package com.mezze.datastructures.nodes;

import com.mezze.datastructures.map.*;
import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for efficient map iteration.
 *
 * Provides specialized iteration for different map storage types.
 */
@NodeInfo(shortName = "map.iterator")
public final class MapIteratorNode extends Node {

    // Entry record for map key-value pairs
    public record Entry(Object key, Object value) {}

    /**
     * Convert map to sequence of Entry records.
     */
    public ObjectSeqStorage toEntrySeq(MapStorage storage) {
        if (storage.isEmpty()) {
            return new ObjectSeqStorage(new Object[0]);
        }
        return executeToEntrySeq(storage);
    }

    @TruffleBoundary
    private ObjectSeqStorage executeToEntrySeq(MapStorage storage) {
        // Fast path for SmallFlatMapStorage - can use loop explosion
        if (storage instanceof SmallFlatMapStorage sfm) {
            return toEntrySeqSmallFlat(sfm);
        }

        // General path
        Object[] keys = storage.keys();
        Object[] values = storage.values();
        int size = keys.length;
        Object[] entries = new Object[size];

        for (int i = 0; i < size; i++) {
            entries[i] = new Entry(keys[i], values[i]);
        }

        return new ObjectSeqStorage(entries);
    }

    @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.FULL_UNROLL)
    private ObjectSeqStorage toEntrySeqSmallFlat(SmallFlatMapStorage storage) {
        int size = storage.size();
        Object[] entries = new Object[size];

        for (int i = 0; i < size; i++) {
            entries[i] = new Entry(storage.getKeyAt(i), storage.getValueAt(i));
        }

        return new ObjectSeqStorage(entries);
    }

    /**
     * Convert map to sequence of keys.
     */
    public SeqStorage toKeySeq(MapStorage storage) {
        if (storage.isEmpty()) {
            return EmptySeqStorage.INSTANCE;
        }
        return executeToKeySeq(storage);
    }

    @TruffleBoundary
    private ObjectSeqStorage executeToKeySeq(MapStorage storage) {
        return new ObjectSeqStorage(storage.keys());
    }

    /**
     * Convert map to sequence of values.
     */
    public SeqStorage toValueSeq(MapStorage storage) {
        if (storage.isEmpty()) {
            return EmptySeqStorage.INSTANCE;
        }
        return executeToValueSeq(storage);
    }

    @TruffleBoundary
    private ObjectSeqStorage executeToValueSeq(MapStorage storage) {
        return new ObjectSeqStorage(storage.values());
    }

    public static MapIteratorNode create() {
        return new MapIteratorNode();
    }
}
