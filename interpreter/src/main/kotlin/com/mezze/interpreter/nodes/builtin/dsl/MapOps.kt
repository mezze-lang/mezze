package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.map.MezzeMap
import com.mezze.interpreter.nodes.builtin.collections.MapNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Map natives in DSL form — the first collection module on the scalar ABI.
 *
 * WHY THIS FILE EXISTS, MEASURED. On the legacy record path `TypedASTCompiler`
 * emits `CallNativeIR(arg=MakeRecordIR(...), args=[])`, so a `MezzeRecord` is
 * built per call and the handler looks each argument up by field name
 * (`getMap(r, "m")`). Declaring an op here makes it positional and typed.
 *
 * Measured per op in `bench/cases/allocsurface`, on ONE kernel before and after
 * its declaration — no cross-kernel inference:
 *
 *   map::contains_key   13 359 ps / 21 GC  ->  4 079 / 14   3.3x
 *   map::size            7 586     / 19    ->  3 465 / 12   2.2x
 *   seq::get            15 432     / 30    ->  7 231 / 23   2.1x
 *   map::get            15 889     / 31    ->  8 078 / 23   2.0x
 *
 * with the unported ops and the two loop controls flat beside them.
 *
 * NOT 13x. An earlier header here claimed that, from comparing `map::size`
 * against `str::size` across two kernels. Two probes disproved it: with a
 * handler that touches nothing at all, `map::size` still allocates 13
 * collections where `str::size` allocates 3, and the two loops' lowered IR is
 * the same shape. The residual is real but is not the ABI — see PERF-LOG.
 *
 * At the time of writing the signature table held 128 ops — float, int, string,
 * nat, char, bool — and `NativeDispatch` held 1298 legacy handlers, so about
 * 91% of the stdlib's natives were on the record path.
 *
 * WHAT IS SAFE TO DECLARE HERE. Only the PLAIN forms. The compiler emits the
 * `*_with_evidence` variants instead whenever the key type has a hand-written
 * `Hash`/`Eq` instance, and those keep their own record path; declaring a plain
 * form does not change which one is chosen. Ops whose handler needs the calling
 * node, or which take a guest function, stay where they are — see
 * [NativeOpWithNode].
 */
object MapOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    /**
     * A map argument, coerced exactly as the legacy `getMap(r, name)` does.
     * `name` is the declared field so a mismatch on `merge` names `m1` / `m2`.
     */
    private fun map(a: NativeArgs, i: Int, name: String = "m"): MezzeMap<*, *> =
        NativeHelpers.asMap(a.obj(i), name)

    // ==================== Map -> Int / Bool ====================
    private val SIZE = NativeOp("__native_map_size",
        listOf(Param("m", SlotKind.OBJECT)), SlotKind.LONG, pure = true,
    ) { args -> MapNatives.size(map(args, 0)) }

    private val IS_EMPTY = NativeOp("__native_map_is_empty",
        listOf(Param("m", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { args -> MapNatives.isEmpty(map(args, 0)) }

    // ==================== Map, k -> Bool / Option v ====================
    private val CONTAINS_KEY = NativeOp("__native_map_contains_key",
        listOf(Param("m", SlotKind.OBJECT), Param("k", SlotKind.OBJECT)),
        SlotKind.BOOLEAN, pure = true,
    ) { args -> MapNatives.containsKey(map(args, 0), args.obj(1)) }

    private val GET = NativeOp("__native_map_get",
        listOf(Param("m", SlotKind.OBJECT), Param("k", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { args -> MapNatives.get(map(args, 0), args.obj(1)) }

    // ==================== Persistent update ====================
    //
    // `empty` is absent because `map::empty {}` is called once to build a map,
    // never per iteration, so there is nothing to win.
    //
    // NOT for the fusion reason an earlier version of this comment gave. The
    // fuser's `isAnyElementCollection` covers seq, iter, list, set, sorted_set
    // and deque — NOT map — and `ChainCanonicalizer` contains no reference to
    // `__native_map_` at all, so declaring a map op can never cost a fusion and
    // `SeqOpsFusionGuardTest` does not examine map names. Declaring
    // `__native_map_empty` would have been safe; it is simply pointless.
    private val INSERT = NativeOp("__native_map_insert",
        listOf(Param("m", SlotKind.OBJECT), Param("k", SlotKind.OBJECT), Param("v", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> MapNatives.insert(map(a, 0), a.obj(1), a.obj(2)) }

    private val REMOVE = NativeOp("__native_map_remove",
        listOf(Param("m", SlotKind.OBJECT), Param("k", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> MapNatives.remove(map(a, 0), a.obj(1)) }

    private val MERGE = NativeOp("__native_map_merge",
        listOf(Param("m1", SlotKind.OBJECT), Param("m2", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> MapNatives.merge(map(a, 0, "m1"), map(a, 1, "m2")) }

    // ==================== Views ====================
    private val KEYS = NativeOp("__native_map_keys",
        listOf(Param("m", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> MapNatives.keys(map(a, 0)) }

    private val VALUES = NativeOp("__native_map_values",
        listOf(Param("m", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> MapNatives.values(map(a, 0)) }

    private val ENTRIES = NativeOp("__native_map_entries",
        listOf(Param("m", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> MapNatives.entries(map(a, 0)) }
}
