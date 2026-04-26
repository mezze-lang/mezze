package com.mezze.interpreter.nodes.profile;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * TYPE PROFILING node for profile-guided optimization.
 *
 * Records observed types at runtime and enables type-specialized dispatch:
 * 1. Monomorphic: Single type seen - fully specialized
 * 2. Polymorphic: 2-3 types seen - type check chain
 * 3. Megamorphic: Many types seen - generic path
 *
 * The JIT uses this information to generate optimal code:
 * - Eliminate type checks when always same type
 * - Inline method calls for known types
 * - Deoptimize on unexpected types
 */
@NodeChild(value = "valueNode", type = MezzeNode.class)
@NodeInfo(shortName = "type-profile")
@SuppressWarnings("truffle-splitting")
public abstract class TypeProfileNode extends MezzeNode {

    /**
     * Long value - fully specialized for Int type.
     * NO boxing, direct primitive handling.
     */
    @Specialization
    protected long profileLong(long value) {
        return value;
    }

    /**
     * Double value - fully specialized for Float type.
     */
    @Specialization
    protected double profileDouble(double value) {
        return value;
    }

    /**
     * Boolean value - fully specialized for Bool type.
     */
    @Specialization
    protected boolean profileBoolean(boolean value) {
        return value;
    }

    /**
     * String value - common case for text processing.
     */
    @Specialization
    protected String profileString(String value) {
        return value;
    }

    /**
     * Generic object - tracks observed class for potential splitting.
     * Uses @Cached to specialize on first observed type.
     */
    @Specialization(
        guards = "value.getClass() == cachedClass",
        limit = "3"
    )
    protected Object profileCachedClass(
            Object value,
            @Cached("value.getClass()") Class<?> cachedClass
    ) {
        return value;
    }

    /**
     * Megamorphic fallback - too many types observed.
     */
    @Specialization(replaces = "profileCachedClass")
    @ReportPolymorphism.Megamorphic
    protected Object profileGeneric(Object value) {
        return value;
    }

    /**
     * Factory method.
     */
    public static TypeProfileNode create(MezzeNode valueNode) {
        return TypeProfileNodeGen.create(valueNode);
    }
}
