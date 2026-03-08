package com.mezze.interpreter.v2.nodes.profile;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * VALUE PROFILING node for constant propagation.
 *
 * Records observed VALUES (not just types) to enable:
 * 1. Constant folding when value is always the same
 * 2. Range-based optimization for integers
 * 3. Length-based optimization for strings/arrays
 *
 * Example optimization:
 * - If `config.debug` is always `false`, the JIT can eliminate debug branches
 * - If array.length is always 3, bounds checks can be optimized away
 */
@NodeChild(value = "valueNode", type = MezzeNode.class)
@NodeInfo(shortName = "value-profile")
public abstract class ValueProfileNode extends MezzeNode {

    /**
     * Profile constant long values.
     * If the same value is seen repeatedly, it becomes a compile-time constant.
     */
    @Specialization(
        guards = "value == cachedValue",
        limit = "1"
    )
    protected long profileConstantLong(
            long value,
            @Cached("value") long cachedValue
    ) {
        return cachedValue; // Compile-time constant!
    }

    /**
     * Profile variable long values.
     */
    @Specialization(replaces = "profileConstantLong")
    protected long profileVariableLong(long value) {
        return value;
    }

    /**
     * Profile constant double values.
     */
    @Specialization(
        guards = "isExactDouble(value, cachedValue)",
        limit = "1"
    )
    protected double profileConstantDouble(
            double value,
            @Cached("value") double cachedValue
    ) {
        return cachedValue;
    }

    /**
     * Helper to compare doubles exactly (including NaN handling).
     */
    protected static boolean isExactDouble(double a, double b) {
        return java.lang.Double.doubleToRawLongBits(a) == java.lang.Double.doubleToRawLongBits(b);
    }

    /**
     * Profile variable double values.
     */
    @Specialization(replaces = "profileConstantDouble")
    protected double profileVariableDouble(double value) {
        return value;
    }

    /**
     * Profile constant boolean values.
     * VERY useful for feature flags and config options.
     */
    @Specialization(
        guards = "value == cachedValue",
        limit = "1"
    )
    protected boolean profileConstantBoolean(
            boolean value,
            @Cached("value") boolean cachedValue
    ) {
        return cachedValue;
    }

    /**
     * Profile variable boolean values.
     */
    @Specialization(replaces = "profileConstantBoolean")
    protected boolean profileVariableBoolean(boolean value) {
        return value;
    }

    /**
     * Profile constant strings (interned).
     * Common for field names, method names, etc.
     */
    @Specialization(
        guards = "value == cachedValue",
        limit = "1"
    )
    protected String profileConstantString(
            String value,
            @Cached("value") String cachedValue
    ) {
        return cachedValue;
    }

    /**
     * Profile variable strings.
     */
    @Specialization(replaces = "profileConstantString")
    protected String profileVariableString(String value) {
        return value;
    }

    /**
     * Profile constant object identity.
     * Useful for singleton patterns (Unit, Null, etc.)
     */
    @Specialization(
        guards = "value == cachedValue",
        limit = "1"
    )
    protected Object profileConstantObject(
            Object value,
            @Cached("value") Object cachedValue
    ) {
        return cachedValue;
    }

    /**
     * Profile variable objects by class.
     * Falls back to type profiling when values vary.
     */
    @Specialization(
        guards = "value.getClass() == cachedClass",
        replaces = "profileConstantObject",
        limit = "3"
    )
    protected Object profileTypedObject(
            Object value,
            @Cached("value.getClass()") Class<?> cachedClass
    ) {
        return value;
    }

    /**
     * Megamorphic fallback.
     */
    @Specialization(replaces = "profileTypedObject")
    protected Object profileGenericObject(Object value) {
        return value;
    }

    /**
     * Factory method.
     */
    public static ValueProfileNode create(MezzeNode valueNode) {
        return ValueProfileNodeGen.create(valueNode);
    }
}
