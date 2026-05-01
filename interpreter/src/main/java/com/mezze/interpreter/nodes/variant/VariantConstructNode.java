package com.mezze.interpreter.nodes.variant;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeVariant;
import com.mezze.interpreter.values.MezzeVariant.TagRegistry;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Variant constructor node - OPTIMIZED.
 *
 * Key optimizations:
 * 1. Pre-computed payload count for @ExplodeLoop
 * 2. Shared empty payload nodes array
 * 3. Single-payload fast path with direct array creation
 *
 * Creates a variant with an integer tag instead of a string constructor name.
 * The tag is determined at compile time from the variant type.
 */
@NodeInfo(shortName = "variant")
public final class VariantConstructNode extends MezzeNode {

    // Shared empty array to avoid allocation
    private static final MezzeNode[] EMPTY_PAYLOAD_NODES = new MezzeNode[0];

    @CompilationFinal
    private final TagRegistry registry;

    @CompilationFinal
    private final int tag;

    @Children
    private final MezzeNode[] payloadNodes;

    // Pre-computed for @ExplodeLoop
    @CompilationFinal
    private final int payloadCount;

    public VariantConstructNode(TagRegistry registry, int tag, MezzeNode[] payloadNodes) {
        this.registry = registry;
        this.tag = tag;
        this.payloadNodes = payloadNodes != null && payloadNodes.length > 0 ? payloadNodes : EMPTY_PAYLOAD_NODES;
        this.payloadCount = this.payloadNodes.length;
    }

    /**
     * Create a variant with no payload (e.g., None).
     */
    public static VariantConstructNode noPayload(TagRegistry registry, int tag) {
        return new VariantConstructNode(registry, tag, EMPTY_PAYLOAD_NODES);
    }

    /**
     * Create a variant with a single payload (e.g., Some(x)).
     */
    public static VariantConstructNode singlePayload(TagRegistry registry, int tag, MezzeNode payloadNode) {
        return new VariantConstructNode(registry, tag, new MezzeNode[] { payloadNode });
    }

    /**
     * Simple factory method for use from converter.
     * Uses a global tag registry.
     */
    public static VariantConstructNode create(int tag, String constructorName, MezzeNode payloadNode) {
        TagRegistry registry = TagRegistry.getGlobal();
        // Ensure tag is registered
        registry.getOrCreateTag(constructorName);
        if (payloadNode == null) {
            return noPayload(registry, tag);
        } else {
            return singlePayload(registry, tag, payloadNode);
        }
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        AllocationReporter reporter = MezzeContext.get(this).getAllocationReporter();

        // Fast path: no payload (e.g., None, True, False).
        if (payloadCount == 0) {
            reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
            MezzeVariant result = MezzeVariant.noPayload(registry, tag);
            reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
            return result;
        }

        // Fast path: single payload (e.g., Some(x)).
        if (payloadCount == 1) {
            Object value = payloadNodes[0].execute(frame);
            reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
            MezzeVariant result = MezzeVariant.singlePayload(registry, tag, value);
            reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
            return result;
        }

        // General path: multiple payload values.
        Object[] payload = new Object[payloadCount];
        for (int i = 0; i < payloadCount; i++) {
            payload[i] = payloadNodes[i].execute(frame);
        }
        reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
        MezzeVariant result = new MezzeVariant(registry, tag, payload);
        reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
        return result;
    }
}
