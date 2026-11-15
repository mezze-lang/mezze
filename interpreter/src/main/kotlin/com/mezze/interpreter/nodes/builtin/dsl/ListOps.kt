package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.list.MezzeList
import com.mezze.interpreter.nodes.builtin.collections.ListNatives
import com.mezze.language.truffleir.SlotKind

/**
 * List natives in DSL form.
 *
 * See [MapOps] for the measurement: the record ABI costs 2-3.3x the scalar ABI
 * for the same op, measured on one kernel before and after the port, because
 * `TypedASTCompiler` hands it a `MezzeRecord` built per call which the handler
 * then reads by field name.
 *
 * ── THE COERCION HERE IS THE WIDEST OF ANY COLLECTION ──────────────────────
 *
 * `NativeDispatch.getList(r, name)` accepts THREE shapes, not one: a
 * [MezzeList], a raw `Object[]`, and a Cons/Nil `MezzeVariant` which it walks
 * into a list. A port that writes `args.obj(0) as MezzeList<*>` therefore
 * changes behaviour — the equivalent mistake on `seq` cost twenty codec tests,
 * surfacing deep inside the JSON decoder rather than at the declaration. So the
 * receiver goes through [NativeHelpers.asList], a faithful copy including the
 * linked-list walk.
 *
 * ── WHAT IS NOT DECLARED ───────────────────────────────────────────────────
 *
 * `size`, `last`, `take`, `drop`, `empty` and `concat` are absent because their
 * bare names are in `NativeHofNames` — they are fusion sinks or stages, and
 * declaring one makes `ChainCanonicalizer` refuse the call and silently cost the
 * fusion. `SeqOpsFusionGuardTest` enforces that. The HOFs need an
 * `IndirectCallNode` ([NativeOpWithNode]) and are separate work.
 *
 * NOTE the field names: `get` takes `index`, while `nth` takes `i`. They are not
 * interchangeable, and a positional port that assumed one name for both would
 * fail the all-params-present check and fall back to the record path silently.
 */
object ListOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    private fun list(a: NativeArgs, i: Int, name: String = "xs"): MezzeList<*> =
        NativeHelpers.asList(a.obj(i), name)

    private fun idx(a: NativeArgs, i: Int, name: String): Long =
        NativeHelpers.asLong(a.obj(i), name)

    // ==================== List -> Bool ====================
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
     * `__native_list_size` to `InlineMapSize` on the RECORD ABI too, so the
     * generic native dispatch is gone without the ABI changing. `bytes::size`,
     * which the canonicalizer never mentions, keeps both halves and is 13.8x.
     */

    private val IS_EMPTY = NativeOp("__native_list_is_empty",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { a -> ListNatives.isEmpty(list(a, 0)) }

    // ==================== Ends ====================
    // `last` is a fusion sink — see the header.
    private val HEAD = NativeOp("__native_list_head",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.head(list(a, 0)) }

    private val TAIL = NativeOp("__native_list_tail",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.tail(list(a, 0)) }

    private val INIT = NativeOp("__native_list_init",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.init(list(a, 0)) }

    private val UNCONS = NativeOp("__native_list_uncons",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.uncons(list(a, 0)) }

    // ==================== Indexing ====================
    private val GET = NativeOp("__native_list_get",
        listOf(Param("xs", SlotKind.OBJECT), Param("index", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.get(list(a, 0), idx(a, 1, "index")) }

    private val NTH = NativeOp("__native_list_nth",
        listOf(Param("xs", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.nth(list(a, 0), idx(a, 1, "i")) }

    // ==================== Growing ====================
    // `cons` declares (head, tail) in that order: the tail is the LIST.
    private val CONS = NativeOp("__native_list_cons",
        listOf(Param("head", SlotKind.OBJECT), Param("tail", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.cons(a.obj(0), list(a, 1, "tail")) }

    private val APPEND = NativeOp("__native_list_append",
        listOf(Param("xs", SlotKind.OBJECT), Param("ys", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.concat2(list(a, 0), list(a, 1, "ys")) }

    private val SINGLETON = NativeOp("__native_list_singleton",
        listOf(Param("x", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.singleton(a.obj(0)) }

    // ==================== Reshaping ====================
    private val REVERSE = NativeOp("__native_list_reverse",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.reverse(list(a, 0)) }

    private val SORT = NativeOp("__native_list_sort",
        listOf(Param("xs", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> ListNatives.sort(list(a, 0)) }
}
