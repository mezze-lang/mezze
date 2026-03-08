package com.mezze.interpreter.v2.nodes.allocation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.mezze.interpreter.v2.values.MezzeVariant.TagRegistry;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * ALLOCATION SINKING node for variants.
 *
 * Variants are simpler than records: just a tag and optional payload.
 * This node enables the JIT to:
 * 1. Inline the tag check in match expressions
 * 2. Avoid allocating the variant if only the tag is needed
 * 3. Keep the payload in registers if the variant doesn't escape
 *
 * Example optimization:
 * ```
 * match Some(x) with
 * | Some(y) -> y + 1
 * | None -> 0
 * ```
 *
 * The JIT can eliminate the Some allocation entirely:
 * - Tag check: constant folding (Some == Some)
 * - Payload access: direct value access without boxing
 */
@NodeChild(value = "payloadNode", type = MezzeNode.class)
@NodeInfo(shortName = "virtual-variant")
public abstract class VirtualVariantNode extends MezzeNode {

    @CompilationFinal
    protected final TagRegistry registry;

    @CompilationFinal
    protected final int tag;

    protected VirtualVariantNode(TagRegistry registry, int tag) {
        this.registry = registry;
        this.tag = tag;
    }

    /**
     * FAST PATH: Long payload (Int type).
     * No boxing needed - store primitive directly.
     */
    @Specialization
    protected MezzeVariant createWithLong(long payload) {
        // The JIT may virtualize this if the variant doesn't escape
        return MezzeVariant.singlePayload(registry, tag, payload);
    }

    /**
     * FAST PATH: Double payload (Float type).
     * No boxing needed - store primitive directly.
     */
    @Specialization
    protected MezzeVariant createWithDouble(double payload) {
        return MezzeVariant.singlePayload(registry, tag, payload);
    }

    /**
     * FAST PATH: Boolean payload (Bool type).
     * No boxing needed - store primitive directly.
     */
    @Specialization
    protected MezzeVariant createWithBoolean(boolean payload) {
        return MezzeVariant.singlePayload(registry, tag, payload);
    }

    /**
     * General case: Object payload.
     */
    @Specialization
    protected MezzeVariant createWithObject(Object payload) {
        return MezzeVariant.singlePayload(registry, tag, payload);
    }

    /**
     * Factory method.
     */
    public static VirtualVariantNode create(TagRegistry registry, int tag, MezzeNode payloadNode) {
        return VirtualVariantNodeGen.create(registry, tag, payloadNode);
    }
}

/**
 * Variant with no payload (like None, Empty, etc.)
 * These are often singletons and can be fully eliminated.
 */
@NodeInfo(shortName = "empty-variant")
final class EmptyVariantNode extends MezzeNode {

    @CompilationFinal
    private final TagRegistry registry;

    @CompilationFinal
    private final int tag;

    @CompilationFinal
    private final MezzeVariant singleton;

    EmptyVariantNode(TagRegistry registry, int tag) {
        this.registry = registry;
        this.tag = tag;
        // Pre-create singleton - no allocation at runtime!
        this.singleton = MezzeVariant.noPayload(registry, tag);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Return pre-allocated singleton
        // The JIT can inline this as a constant
        return singleton;
    }

    /**
     * Get the pre-allocated singleton (for constant folding).
     */
    public MezzeVariant getSingleton() {
        return singleton;
    }

    /**
     * Factory method.
     */
    public static EmptyVariantNode create(TagRegistry registry, int tag) {
        return new EmptyVariantNode(registry, tag);
    }
}
