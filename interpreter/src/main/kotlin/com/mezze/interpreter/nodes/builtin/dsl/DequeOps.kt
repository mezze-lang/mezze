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
     * `__native_deque_size` to `InlineMapSize` on the RECORD ABI too, so the
     * generic native dispatch is gone without the ABI changing. `bytes::size`,
     * which the canonicalizer never mentions, keeps both halves and is 13.8x.
     */

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
