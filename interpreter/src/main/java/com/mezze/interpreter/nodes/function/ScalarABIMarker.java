package com.mezze.interpreter.nodes.function;

/**
 * Sentinel placed at args[closureCount] by the scalar calling convention.
 *
 * Layout per ABI (passed as the Object[] to a CallTarget):
 *   record ABI:  [closure..., MezzeRecord,        abilityOps...]
 *   scalar ABI:  [closure..., SCALAR_ABI_MARKER, scalar0, scalar1, ..., scalarN-1, abilityOps...]
 *
 * The receiver dispatches on identity: if args[closureCount] == INSTANCE,
 * params come positionally from args[closureCount + 1 + i]; otherwise it
 * falls back to record-unpack-by-field-name.
 */
public final class ScalarABIMarker {
    public static final ScalarABIMarker INSTANCE = new ScalarABIMarker();
    private ScalarABIMarker() {}
}
