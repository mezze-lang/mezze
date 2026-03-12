package com.mezze.datastructures.nodes;

import com.mezze.datastructures.map.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node for map key containment check.
 *
 * Optimized for different storage strategies.
 */
@NodeInfo(shortName = "map.containsKey")
public final class MapContainsKeyNode extends Node {

    public boolean execute(MapStorage storage, Object key) {
        if (storage.isEmpty()) {
            return false;
        }
        return executeContains(storage, key);
    }

    @TruffleBoundary
    private boolean executeContains(MapStorage storage, Object key) {
        // Fast path for long-keyed map with long key
        if (storage instanceof LongKeyMapStorage lkm && key instanceof Long l) {
            return lkm.containsKey(l);
        }
        return storage.containsKey(key);
    }

    public static MapContainsKeyNode create() {
        return new MapContainsKeyNode();
    }
}
