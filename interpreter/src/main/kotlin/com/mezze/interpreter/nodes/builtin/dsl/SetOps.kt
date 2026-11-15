package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.set.MezzeSet
import com.mezze.interpreter.nodes.builtin.collections.SetNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Set natives in DSL form.
 *
 * See [MapOps] for the measurement: the record ABI costs 2-3.3x the scalar ABI
 * for the same op, measured on one kernel before and after the port, because
 * `TypedASTCompiler` hands it a `MezzeRecord` built per call which the handler
 * then reads by field name.
 *
 * `size` and `to_list` are NOT declared — `NativeHofNames.OP_SIZE` and
 * `OP_TO_LIST` are fusion sinks, so declaring them would make
 * `ChainCanonicalizer` refuse the call and silently cost the fusion. Neither are
 * the HOFs (`map`, `filter`, `fold`, `any`, `all`, …), which need an
 * `IndirectCallNode`. `SeqOpsFusionGuardTest` enforces the rule.
 *
 * The `*_with_evidence` forms keep their record path: the compiler selects them
 * before the scalar transpose runs, so a plain declaration cannot affect them.
 *
 * Unlike `getSeq`/`getList`, the legacy `getSet` accessor does not coerce — it
 * casts or throws — so [NativeHelpers.asSet] is a faithful copy of exactly that.
 */
object SetOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    private fun set(a: NativeArgs, i: Int, name: String = "s"): MezzeSet<*> =
        NativeHelpers.asSet(a.obj(i), name)

    // ==================== Set -> Bool ====================
    /**
     * AND `size`, WHICH WAS NOT HERE while `is_empty` directly below it was.
     *
     * MEASURED on `bench/cases/allocsurface` at n=1e7 and n=2e7, one kernel per
     * process, converged, with the binary built by the DEV launcher so the
     * interpreter in it is the one under test:
     *
     *     counted loop (control)    226 ps  x 1.0   2 young
     *     map::size   (declared)    726     x 3.2   3
     *     seq::size   (NOT)       8 522     x37.7  19
     *     bytes::size (NOT)       8 893     x39.3  19
     *
     * 12x in time and 6.3x in allocation, and the line between them is exactly
     * this table: an undeclared native is reached through
     * `CallNativeIR(arg=MakeRecordIR(...))`, so a `MezzeRecord` is built per
     * call and its one field looked up by name. `map::size` sits on
     * `str::size` at the floor.
     */
    /**
     * `size` IS NOT DECLARED HERE, and must not be.
     *
     * It is part of `ChainCanonicalizer`'s vocabulary, every one of its match
     * sites is guarded `&& !node.isScalarABI`, and `seq::map` + `size` is 1503x
     * on the fused path — so declaring it does not merely fail to help, it makes
     * the fuser REFUSE the chain with no diagnostic. `SeqOpsFusionGuardTest`
     * fails the moment such a declaration lands, which is how this was caught.
     *
     * What `size` DOES get is the dedicated operation: the emitter maps
     * `__native_set_size` to `InlineMapSize` on the RECORD ABI too, so the
     * generic native dispatch is gone without the ABI changing. `bytes::size`,
     * which the canonicalizer never mentions, keeps both halves and is 13.8x.
     */

    private val IS_EMPTY = NativeOp("__native_set_is_empty",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { a -> SetNatives.isEmpty(set(a, 0)) }

    private val CONTAINS = NativeOp("__native_set_contains",
        listOf(Param("s", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.BOOLEAN, pure = true,
    ) { a -> SetNatives.contains(set(a, 0), a.obj(1)) }

    // ==================== Persistent update ====================
    private val INSERT = NativeOp("__native_set_insert",
        listOf(Param("s", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SetNatives.insert(set(a, 0), a.obj(1)) }

    private val REMOVE = NativeOp("__native_set_remove",
        listOf(Param("s", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SetNatives.remove(set(a, 0), a.obj(1)) }

    // ==================== Algebra ====================
    private val UNION = NativeOp("__native_set_union",
        listOf(Param("s1", SlotKind.OBJECT), Param("s2", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SetNatives.union(set(a, 0, "s1"), set(a, 1, "s2")) }

    private val INTERSECTION = NativeOp("__native_set_intersection",
        listOf(Param("s1", SlotKind.OBJECT), Param("s2", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SetNatives.intersection(set(a, 0, "s1"), set(a, 1, "s2")) }

    private val DIFFERENCE = NativeOp("__native_set_difference",
        listOf(Param("s1", SlotKind.OBJECT), Param("s2", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SetNatives.difference(set(a, 0, "s1"), set(a, 1, "s2")) }
}
