package com.mezze.language.ast

/**
 * C4 of continuations-and-swappable-async: the natives whose loops RESUME after a
 * callback suspends — the loop returns a `NativeSuspension` carrying its heap state,
 * the task machine drives the callback, then re-enters the loop at the next element.
 *
 * One list, two readers, kept consistent by `ResumableNativesRegistryTest`: the
 * interpreter (each name's handler is written in the resumable shape) and the
 * typechecker (V0513 does not refuse a parking callback handed to one of these).
 * Registration-time policy by the native's identity — the same key the handler
 * table uses — not a name match on user code.
 */
object ResumableNatives {
    val NAMES: Set<String> = setOf(
        "__native_seq_fold",
        "__native_seq_map",
        "__native_seq_for_each",
        "__native_seq_filter",
        "__native_seq_any",
        "__native_seq_all",
        "__native_seq_find",
    )
}
