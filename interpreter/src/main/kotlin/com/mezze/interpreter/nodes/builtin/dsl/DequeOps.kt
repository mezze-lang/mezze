package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.deque.MezzeDeque
import com.mezze.interpreter.nodes.builtin.collections.DequeNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Deque natives in DSL form.
 *
 * See [MapOps] for the measurement: the record ABI costs 2-3.3x the scalar ABI
 * for the same op, because `TypedASTCompiler` hands the handler a `MezzeRecord`
 * built per call which it then reads back by field name.
 *
 * WHAT IS DELIBERATELY ABSENT, and each for its own reason:
 *
 *  - **Fusion sinks and stages.** `size`, `first`, `last`, `to_seq`, `to_list`,
 *    `empty`, `take`, `drop`, `from_seq`, `from_list`, `sum`, `product`, `count`
 *    and the rest are names `ChainCanonicalizer` matches on. Declaring one makes
 *    the canonicalizer refuse the call and silently costs the fusion, which is
 *    worth far more than the ABI. `SeqOpsFusionGuardTest` enforces this.
 *  - **Every HOF** — `map`, `filter`, `fold`, `any`, `all`, `find`, `for_each`,
 *    `reduce`, `scan`, `span`, `partition`, `flat_map`, `min_by`, `max_by`,
 *    `take_while`, `drop_while`, `zip` — needs an `IndirectCallNode` to call
 *    back into guest code, which the scalar path does not hand the handler.
 *  - **`equals`, `compare`, `dedup`, `sort`** for the same reason: each takes a
 *    guest comparator and the call node alongside it.
 *
 * The legacy `getDeque` accessor casts or throws rather than coercing, so
 * [NativeHelpers.asDeque] is a faithful copy of exactly that — the trap here is
 * that `getSeq`/`getList` DO coerce, and a copy that quietly tightened one of
 * those broke 20 codec tests earlier in this effort.
 */
object DequeOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    /**
     * `DequeNatives` declares its parameters as `MezzeDeque<Object>` rather than
     * the wildcard `MezzeSet`-style raw type, so the element type is named here
     * and the cast is unchecked for the same reason the legacy accessor's was:
     * the deque is heterogeneous at the Java level and the typechecker is what
     * guarantees the elements.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deque(a: NativeArgs, i: Int, name: String = "d"): MezzeDeque<Any> =
        NativeHelpers.asDeque(a.obj(i), name) as MezzeDeque<Any>

    // ==================== Deque -> Bool ====================
    private val IS_EMPTY = NativeOp("__native_deque_is_empty",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { a -> DequeNatives.isEmpty(deque(a, 0)) }

    // ==================== Construction ====================
    private val SINGLETON = NativeOp("__native_deque_singleton",
        listOf(Param("x", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.singleton(a.obj(0)) }

    // ==================== Ends ====================
    private val PUSH_FRONT = NativeOp("__native_deque_push_front",
        listOf(Param("d", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.pushFront(deque(a, 0), a.obj(1)) }

    private val PUSH_BACK = NativeOp("__native_deque_push_back",
        listOf(Param("d", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.pushBack(deque(a, 0), a.obj(1)) }

    private val POP_FRONT = NativeOp("__native_deque_pop_front",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.popFront(deque(a, 0)) }

    private val POP_BACK = NativeOp("__native_deque_pop_back",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.popBack(deque(a, 0)) }

    private val PEEK_FRONT = NativeOp("__native_deque_peek_front",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.peekFront(deque(a, 0)) }

    private val PEEK_BACK = NativeOp("__native_deque_peek_back",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.peekBack(deque(a, 0)) }

    // ==================== Indexed access ====================
    private val GET = NativeOp("__native_deque_get",
        listOf(Param("d", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.get(deque(a, 0), a.long(1)) }

    private val SLICE = NativeOp("__native_deque_slice",
        listOf(Param("d", SlotKind.OBJECT), Param("start", SlotKind.LONG),
               Param("end", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.slice(deque(a, 0), a.long(1), a.long(2)) }

    private val SPLIT_AT = NativeOp("__native_deque_split_at",
        listOf(Param("d", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.splitAt(deque(a, 0), a.long(1)) }

    // ==================== Rearrangement ====================
    private val REVERSE = NativeOp("__native_deque_reverse",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.reverse(deque(a, 0)) }

    private val ROTATE_LEFT = NativeOp("__native_deque_rotate_left",
        listOf(Param("d", SlotKind.OBJECT), Param("n", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.rotateLeft(deque(a, 0), a.long(1)) }

    private val ROTATE_RIGHT = NativeOp("__native_deque_rotate_right",
        listOf(Param("d", SlotKind.OBJECT), Param("n", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.rotateRight(deque(a, 0), a.long(1)) }

    private val CONCAT = NativeOp("__native_deque_concat",
        listOf(Param("d1", SlotKind.OBJECT), Param("d2", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.concat(deque(a, 0, "d1"), deque(a, 1, "d2")) }

    private val INTERSPERSE = NativeOp("__native_deque_intersperse",
        listOf(Param("d", SlotKind.OBJECT), Param("sep", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.intersperse(deque(a, 0), a.obj(1)) }

    private val UNIQUE = NativeOp("__native_deque_unique",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.unique(deque(a, 0)) }

    private val ZIP_WITH_INDEX = NativeOp("__native_deque_zip_with_index",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.zipWithIndex(deque(a, 0)) }

    // ==================== Conversion to other collections ====================
    private val TO_SET = NativeOp("__native_deque_to_set",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.toSet(deque(a, 0)) }

    private val TO_MAP = NativeOp("__native_deque_to_map",
        listOf(Param("d", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.toMap(deque(a, 0)) }

    private val FROM_SET = NativeOp("__native_deque_from_set",
        listOf(Param("s", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.fromSet(NativeHelpers.asSet(a.obj(0), "s")) }

    private val FROM_MAP = NativeOp("__native_deque_from_map",
        listOf(Param("m", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> DequeNatives.fromMap(NativeHelpers.asMap(a.obj(0), "m")) }
}
