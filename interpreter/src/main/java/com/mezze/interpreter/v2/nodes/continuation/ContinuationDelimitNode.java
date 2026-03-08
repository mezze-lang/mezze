package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * __native_continuation_delimit - Creates a new delimiter.
 *
 * FULLY JIT-COMPILABLE - no @TruffleBoundary needed.
 * Delimiter creation is O(1) using atomic counter.
 *
 * Takes: {}
 * Returns: MezzeCont (opaque delimiter handle)
 */
@NodeInfo(shortName = "cont-delimit")
public final class ContinuationDelimitNode extends MezzeNode {

    @Override
    public Object execute(VirtualFrame frame) {
        // Create new delimiter - O(1), thread-safe
        return MezzeCont.create();
    }

    public static ContinuationDelimitNode create() {
        return new ContinuationDelimitNode();
    }
}
