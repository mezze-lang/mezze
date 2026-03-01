package com.mezze.interpreter.v2.nodes.inline;

import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * INLINABLE NOT node - embeds directly into parent nodes.
 *
 * This is a pure computation node without @NodeChild.
 * Use this when you want to inline NOT into a parent node.
 *
 * Example usage in parent node:
 * {@code
 * @Specialization
 * protected boolean doNot(boolean value,
 *     @Cached InlineNotNode notNode) {
 *     return notNode.execute(this, value);
 * }
 * }
 */
@GenerateInline
@GenerateCached(false)
public abstract class InlineNotNode extends Node {

    public abstract boolean execute(Node node, boolean value);

    @Specialization
    protected static boolean not(boolean value) {
        return !value;
    }
}
