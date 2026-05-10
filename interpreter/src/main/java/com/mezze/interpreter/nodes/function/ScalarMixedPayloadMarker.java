package com.mezze.interpreter.nodes.function;

/**
 * Marker placed at args[closureCount] by callers using the typed-mixed
 * scalar calling convention.
 *
 *   layout: [closure...,
 *            MARKER,
 *            paramKinds:byte[],   // KIND_LONG=0, DOUBLE=1, BOOLEAN=2, OBJECT=3
 *            longArgs:long[],     // primitives indexed by per-kind position
 *            doubleArgs:double[],
 *            boolArgs:boolean[],  // (reserved; receiver currently sets via setObject)
 *            objArgs:Object[],
 *            abilityOps...]
 *
 * Receiver walks paramKinds in order and reads each param from the bucket
 * matching its kind, advancing per-bucket indices. No boxing for primitives.
 *
 * Used when the callee has a mix of primitive (LONG/DOUBLE) and OBJECT
 * params — common shape for HOFs that take a function plus numeric state
 * (e.g. {f, acc, x}). Pure all-LONG / all-DOUBLE callees keep their
 * dedicated single-bucket markers (cheaper dispatch).
 */
public final class ScalarMixedPayloadMarker {
    public static final ScalarMixedPayloadMarker INSTANCE = new ScalarMixedPayloadMarker();
    private ScalarMixedPayloadMarker() {}
}
