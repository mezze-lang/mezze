package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.set.MezzeSet
import com.mezze.interpreter.nodes.builtin.collections.SetNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Set natives in DSL form.
 *
 * See [MapOps] for the measurement: a native on the scalar ABI is
 * indistinguishable from the loop around it, the same call on the legacy record
 * path costs 13x, and the whole difference is that `TypedASTCompiler` hands it a
 * `MezzeRecord` built per call which the handler then reads by field name.
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
