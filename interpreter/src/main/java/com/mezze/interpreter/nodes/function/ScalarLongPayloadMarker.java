package com.mezze.interpreter.nodes.function;

/**
 * Marker placed at args[closureCount] by callers using the typed-long
 * scalar calling convention.
 *
 *   layout: [closure..., MARKER, long[] longArgs, abilityOps...]
 *
 * Receiver reads long[] from args[closureCount + 1] and writes each
 * primitive directly into the corresponding LONG-typed param slot —
 * no boxing, no instanceof.
 *
 * Used by CallDirectScalarNode for non-tail self-recursive calls where
 * the typechecker proved every callee param is LONG-kind.
 */
public final class ScalarLongPayloadMarker {
    public static final ScalarLongPayloadMarker INSTANCE = new ScalarLongPayloadMarker();
    private ScalarLongPayloadMarker() {}
}
