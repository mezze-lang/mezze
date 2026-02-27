package com.mezze.interpreter.v2.nodes.variant;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * A single branch in a match expression.
 *
 * Handles:
 * - Variant patterns: Some(x) -> ...
 * - Literal patterns: 1 -> ..., "hello" -> ...
 * - Wildcard patterns: _ -> ..., x -> ...
 */
@NodeInfo(shortName = "match-branch")
public final class MatchBranchNode extends MezzeNode {

    // For variant patterns: the tag to match, or -1 for wildcard
    @CompilationFinal
    private final int tag;

    // Frame slots to bind payload values (for variant patterns)
    @CompilationFinal(dimensions = 1)
    private final int[] payloadSlots;

    // Frame slot to bind the entire matched value (for wildcard with binding)
    @CompilationFinal
    private final int valueSlot;

    // For literal patterns: the value to match
    @CompilationFinal
    private final Object literalValue;

    @Child
    private MezzeNode bodyNode;

    /**
     * Create a variant pattern branch.
     */
    public static MatchBranchNode variantPattern(int tag, int[] payloadSlots, MezzeNode bodyNode) {
        return new MatchBranchNode(tag, payloadSlots, -1, null, bodyNode);
    }

    /**
     * Create a wildcard branch (matches anything).
     */
    public static MatchBranchNode wildcard(int valueSlot, MezzeNode bodyNode) {
        return new MatchBranchNode(-1, new int[0], valueSlot, null, bodyNode);
    }

    /**
     * Create a wildcard branch with no binding.
     */
    public static MatchBranchNode wildcardNoBinding(MezzeNode bodyNode) {
        return new MatchBranchNode(-1, new int[0], -1, null, bodyNode);
    }

    /**
     * Create a literal pattern branch.
     */
    public static MatchBranchNode literalPattern(Object literalValue, MezzeNode bodyNode) {
        return new MatchBranchNode(-2, new int[0], -1, literalValue, bodyNode);
    }

    private MatchBranchNode(int tag, int[] payloadSlots, int valueSlot,
                            Object literalValue, MezzeNode bodyNode) {
        this.tag = tag;
        this.payloadSlots = payloadSlots;
        this.valueSlot = valueSlot;
        this.literalValue = literalValue;
        this.bodyNode = bodyNode;
    }

    /**
     * Check if this branch matches the given tag.
     */
    public boolean matches(int variantTag) {
        return tag == variantTag;
    }

    /**
     * Check if this is a wildcard branch.
     */
    public boolean isWildcard() {
        return tag == -1;
    }

    /**
     * Check if this matches a literal value.
     */
    public boolean matchesLiteral(Object value) {
        if (literalValue == null) return false;

        if (literalValue instanceof Long l && value instanceof Long v) {
            return l.equals(v);
        }
        if (literalValue instanceof Double d && value instanceof Double v) {
            return d.equals(v);
        }
        if (literalValue instanceof Boolean b && value instanceof Boolean v) {
            return b.equals(v);
        }
        if (literalValue instanceof String s && value instanceof String v) {
            return s.equals(v);
        }

        return literalValue.equals(value);
    }

    /**
     * Bind payload values from a variant to frame slots.
     */
    public void bindPayload(VirtualFrame frame, MezzeVariant variant) {
        Object[] payload = variant.getPayload();
        for (int i = 0; i < payloadSlots.length && i < payload.length; i++) {
            frame.setObject(payloadSlots[i], payload[i]);
        }
    }

    /**
     * Bind the entire matched value to a frame slot.
     */
    public void bindValue(VirtualFrame frame, Object value) {
        if (valueSlot >= 0) {
            frame.setObject(valueSlot, value);
        }
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return bodyNode.execute(frame);
    }
}
