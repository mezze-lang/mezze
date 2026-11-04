package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.map.MezzeMap
import com.mezze.interpreter.nodes.builtin.collections.MapNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Map natives in DSL form — the first collection module on the scalar ABI.
 *
 * WHY THIS FILE EXISTS, MEASURED. `bench/cases/allocsurface` compares two
 * kernels that do the same semantic work: a native call returning an `Int` that
 * allocates nothing, against a structure built once outside the loop.
 *
 *   kernel 12  `str::size`  scalar ABI    577 ps/iter    3 young GCs
 *   kernel  9  `map::size`  record ABI  7 586 ps/iter   19 young GCs
 *
 * and the off-tier control those sit against is 611 ps / 3 GCs. So a native on
 * the scalar ABI is free — indistinguishable from the loop around it — and the
 * same call on the legacy record ABI costs **13x** that, because
 * `TypedASTCompiler` emits `CallNativeIR(arg=MakeRecordIR(...), args=[])` and
 * the handler then looks each argument up by field name (`getMap(r, "m")`).
 *
 * At the time of writing the signature table held 128 ops — float, int, string,
 * nat, char, bool — and `NativeDispatch` held 1298 legacy handlers. Every map,
 * seq, list and set operation was on the slow side of that 13x.
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
    // `empty` is deliberately absent: `NativeHofNames.OP_EMPTY` is part of the
    // fuser's vocabulary, so declaring `__native_map_empty` would make
    // ChainCanonicalizer refuse it. SeqOpsFusionGuardTest enforces that, and
    // caught this one while the list was being written.
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
