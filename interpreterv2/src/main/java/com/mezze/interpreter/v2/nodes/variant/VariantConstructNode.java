package com.mezze.interpreter.v2.nodes.variant;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.mezze.interpreter.v2.values.MezzeVariant.TagRegistry;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Variant constructor node.
 *
 * Creates a variant with an integer tag instead of a string constructor name.
 * The tag is determined at compile time from the variant type.
 */
@NodeInfo(shortName = "variant")
public final class VariantConstructNode extends MezzeNode {

    @CompilationFinal
    private final TagRegistry registry;

    @CompilationFinal
    private final int tag;

    @Children
    private final MezzeNode[] payloadNodes;

    public VariantConstructNode(TagRegistry registry, int tag, MezzeNode[] payloadNodes) {
        this.registry = registry;
        this.tag = tag;
        this.payloadNodes = payloadNodes;
    }

    /**
     * Create a variant with no payload (e.g., None).
     */
    public static VariantConstructNode noPayload(TagRegistry registry, int tag) {
        return new VariantConstructNode(registry, tag, new MezzeNode[0]);
    }

    /**
     * Create a variant with a single payload (e.g., Some(x)).
     */
    public static VariantConstructNode singlePayload(TagRegistry registry, int tag, MezzeNode payloadNode) {
        return new VariantConstructNode(registry, tag, new MezzeNode[] { payloadNode });
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (payloadNodes.length == 0) {
            return MezzeVariant.noPayload(registry, tag);
        }

        Object[] payload = new Object[payloadNodes.length];
        for (int i = 0; i < payloadNodes.length; i++) {
            payload[i] = payloadNodes[i].execute(frame);
        }

        return new MezzeVariant(registry, tag, payload);
    }
}
