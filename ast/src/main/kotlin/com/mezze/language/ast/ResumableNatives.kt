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
        // iter: the consumers pull through resumable cursors when any callback in the
        // chain or the consumer's own may suspend; the stage constructors below only
        // build the descriptor, so a parking callback handed to them is fine.
        "__native_iter_fold", "__native_iter_for_each", "__native_iter_count", "__native_iter_any",
        "__native_iter_all", "__native_iter_find", "__native_iter_reduce", "__native_iter_to_seq",
        "__native_iter_to_list", "__native_iter_first", "__native_iter_last", "__native_iter_sum",
        "__native_iter_count_all",
        "__native_iter_map", "__native_iter_filter", "__native_iter_take_while", "__native_iter_drop_while",
        "__native_iter_flat_map", "__native_iter_unfold",
    )
}
