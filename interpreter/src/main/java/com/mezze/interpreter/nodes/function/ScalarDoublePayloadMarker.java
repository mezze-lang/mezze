package com.mezze.interpreter.nodes.function;

/**
 * Marker placed at args[closureCount] by callers using the typed-double
 * scalar calling convention.
 *
 *   layout: [closure..., MARKER, double[] doubleArgs, abilityOps...]
 *
 * Receiver reads double[] from args[closureCount + 1] and writes each
 * primitive directly into the corresponding DOUBLE-typed param slot —
 * no boxing, no instanceof.
 *
 * Used by CallDirectScalarNode for non-tail self-recursive calls where
 * the typechecker proved every callee param is DOUBLE-kind. Mirrors
 * ScalarLongPayloadMarker for primitive long params.
 */
public final class ScalarDoublePayloadMarker {
    public static final ScalarDoublePayloadMarker INSTANCE = new ScalarDoublePayloadMarker();
    private ScalarDoublePayloadMarker() {}
}
