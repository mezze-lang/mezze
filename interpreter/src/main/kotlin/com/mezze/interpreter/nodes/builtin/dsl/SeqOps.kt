package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.nodes.builtin.collections.SeqNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Seq natives in DSL form — the accessors and the persistent-update ops.
 *
 * See [MapOps] for the measurement that motivates every collection module
 * moving to the scalar ABI: a native on the scalar path is indistinguishable
 * from the loop around it, while the same call on the legacy record path costs
 * 13x, because `TypedASTCompiler` emits `CallNativeIR(arg=MakeRecordIR(...))`
 * and the handler then looks each argument up by field name.
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
