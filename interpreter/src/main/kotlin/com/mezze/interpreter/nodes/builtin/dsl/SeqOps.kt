package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.nodes.builtin.collections.SeqNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Seq natives in DSL form — the accessors and the persistent-update ops.
 *
 * See [MapOps] for the measurement that motivates every collection module
 * moving to the scalar ABI: the record path costs 2-3.3x the scalar path for the
 * same op, measured on one kernel before and after the port, because
 * `TypedASTCompiler` emits `CallNativeIR(arg=MakeRecordIR(...))` and the handler
 * then looks each argument up by field name. `seq::get` is one of those
 * measurements: 15 432 ps / 30 GC on the record path, 7 231 / 23 on the scalar
 * one.
 *
 * ── WHAT MUST NOT BE DECLARED HERE, AND WHY ────────────────────────────────
 *
 * `ChainCanonicalizer` matches a fusable chain on `CallNativeIR` and every one
 * of those sites is guarded `&& !node.isScalarABI`, because it reads the chain's
 * source and lambda out of the argument record by field name. So declaring an
 * op that is ALSO a fusion sink does not merely fail to help — it makes the
 * fuser refuse the call and silently costs the fusion. `seq::map` + `size` is
 * 1503x on that path.
 *
 * The rule, which [SeqOpsFusionGuardTest] enforces so it cannot rot: **an op may
 * be declared here only if its bare name is absent from `NativeHofNames`.**
 * That excludes, deliberately:
 *
 *   size, take, drop, first, last   — fusion sinks or stages
 *   sum, count, any, all, find, reduce, fold, for_each, map, filter,
 *   to_seq, to_list, enumerate, …   — HOFs; they also need an IndirectCallNode,
 *                                     which is [NativeOpWithNode]'s job
 *
 * Also left alone: `equal_range`, which has a known signature mismatch, and the
 * `*_with_evidence` forms, which the compiler selects before the scalar
 * transpose runs so a plain declaration cannot affect them.
 *
 * `pure = true` throughout: every op here either reads the seq or returns a new
 * persistent one, and none performs I/O, mutation or suspension.
 */
object SeqOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    /**
     * A seq argument, coerced exactly as the legacy `getSeq(r, name)` does — it
     * accepts a raw `Object[]` as well as a [MezzeSeq]. A plain cast here cost
     * twenty codec and decode tests; see [NativeHelpers.asSeq].
     *
     * `name` is the declared field, so a mismatch on `concat`'s second argument
     * names `ys` rather than `xs`.
     */
    private fun seq(a: NativeArgs, i: Int, name: String = "xs"): MezzeSeq<*> =
        NativeHelpers.asSeq(a.obj(i), name)

    /** An index or count argument, coerced as the legacy `getLong(r, name)` does. */
    private fun idx(a: NativeArgs, i: Int, name: String): Long =
        NativeHelpers.asLong(a.obj(i), name)

    // ==================== Seq -> Bool ====================
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
     * `__native_seq_size` to `InlineMapSize` on the RECORD ABI too, so the
     * generic native dispatch is gone without the ABI changing. `bytes::size`,
     * which the canonicalizer never mentions, keeps both halves and is 13.8x.
     */

    private val IS_EMPTY = NativeOp("__native_seq_is_empty",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { a -> SeqNatives.isEmpty(seq(a, 0)) }

    // ==================== Indexing ====================
    private val GET = NativeOp("__native_seq_get",
        listOf(Param("xs", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.get(seq(a, 0), idx(a, 1, "i")) }

    private val GET_UNCHECKED = NativeOp("__native_seq_get_unchecked",
        listOf(Param("xs", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.getUnchecked(seq(a, 0), idx(a, 1, "i")) }

    private val SET = NativeOp("__native_seq_set",
        listOf(Param("xs", SlotKind.OBJECT), Param("i", SlotKind.LONG), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.set(seq(a, 0), idx(a, 1, "i"), a.obj(2)) }

    private val REMOVE = NativeOp("__native_seq_remove",
        listOf(Param("xs", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.remove(seq(a, 0), idx(a, 1, "i")) }

    // ==================== Ends ====================
    // `first` and `last` are fusion sinks — see the header.
    private val HEAD = NativeOp("__native_seq_head",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.head(seq(a, 0)) }

    private val TAIL = NativeOp("__native_seq_tail",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.tail(seq(a, 0)) }

    private val INIT = NativeOp("__native_seq_init",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.init(seq(a, 0)) }

    // ==================== Growing ====================
    private val ADD = NativeOp("__native_seq_add",
        listOf(Param("xs", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.add(seq(a, 0), a.obj(1)) }

    private val PUSH = NativeOp("__native_seq_push",
        listOf(Param("xs", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.push(seq(a, 0), a.obj(1)) }

    private val PUSH_FRONT = NativeOp("__native_seq_push_front",
        listOf(Param("xs", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.pushFront(seq(a, 0), a.obj(1)) }

    private val CONCAT = NativeOp("__native_seq_concat",
        listOf(Param("xs", SlotKind.OBJECT), Param("ys", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.concat(seq(a, 0), seq(a, 1, "ys")) }

    // ==================== Reshaping ====================
    // `take` and `drop` are fusion stages — see the header.
    private val REVERSE = NativeOp("__native_seq_reverse",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.reverse(seq(a, 0)) }

    private val SORT = NativeOp("__native_seq_sort",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.sort(seq(a, 0)) }

    private val SLICE = NativeOp("__native_seq_slice",
        listOf(Param("xs", SlotKind.OBJECT), Param("start", SlotKind.LONG), Param("end", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.slice(seq(a, 0), idx(a, 1, "start"), idx(a, 2, "end")) }

    // ==================== Searching ====================
    private val CONTAINS = NativeOp("__native_seq_contains",
        listOf(Param("xs", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.BOOLEAN, pure = true,
    ) { a -> SeqNatives.contains(seq(a, 0), a.obj(1)) }

    private val INDEX_OF = NativeOp("__native_seq_index_of",
        listOf(Param("xs", SlotKind.OBJECT), Param("x", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> SeqNatives.indexOf(seq(a, 0), a.obj(1)) }
}
