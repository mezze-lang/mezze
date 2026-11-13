package com.mezze.language.truffleir

import com.mezze.language.ast.MagicParams

/**
 * Single source of truth for native HOF function names and field-name
 * conventions used by the fusion pipeline (ChainCanonicalizer →
 * ChainHofClassifier → BytecodeCompiler).
 *
 * **Field names** are the parameter/record-field conventions the stdlib
 * uses for HOFs. When adding a new fusable operation, reference these
 * constants instead of sprinkling string literals.
 *
 * **Native names** are built via [native] from a collection prefix and
 * operation name. The stdlib `.mz` files define the actual functions;
 * this object mirrors the naming convention so the compiler can match
 * them structurally.
 *
 * Adding a new fusable HOF:
 * 1. Add the operation constant here (OP_*)
 * 2. Add it to [ChainHofClassifier.HofKind]
 * 3. Add recognition in [ChainCanonicalizer]
 * 4. Add emission in BytecodeCompiler
 */
object NativeHofNames {

    // ── Field name conventions ──────────────────────────────────────────
    // These are the record-field / param names the stdlib HOFs use.
    // ChainCanonicalizer extracts args from MakeRecordIR by matching
    // these names.

    /** Collection source — seq uses "xs", iter uses "it". */
    const val XS = "xs"
    const val IT = "it"
    const val SRC = "src"
    /** Set / SortedSet source field. */
    const val S = "s"
    /** Deque source field. */
    const val D = "d"
    /** Map / SortedMap source field. */
    const val M = "m"
    /** `bytes::fold { b, init, f }` — the receiver field for bytes. */
    const val B = "b"

    /** All names that mean "the collection source position". */
    val SRC_FIELDS: Set<String> = setOf(XS, SRC, IT, S, D, M)

    // ── Fields of the records collections PRODUCE ───────────────────────
    //
    // The names above are what natives CONSUME; these are what they hand
    // back. They are spelled here and nowhere else because the FUSED LOOP
    // MATCHES ON THEM — `.fst`, `.snd`, `.val` and `.i` are resolved to loop
    // locals instead of a record being built — so a rename that misses a site
    // yields a record that resolves in some positions and not others, and an
    // unresolved field declines the fusion silently.
    //
    // NOT the same as `BuiltinTypes.VAL_FIELD`, which is `Option.Some`'s
    // field and merely shares the spelling. Renaming one must not rename the
    // other, so they are separate constants on purpose.

    /** `zip`'s pair, for `iter` and `seq` alike: `{ fst, snd }`. */
    const val PAIR_FST = "fst"
    const val PAIR_SND = "snd"
    /** `seq::zip3` and `seq::zip4` continue the same series. */
    const val PAIR_THD = "thd"
    const val PAIR_FTH = "fth"

    /** `iter::enumerate` — `{ i, val }`. */
    const val ITER_ENUM_INDEX = "i"
    const val ITER_ENUM_VAL = "val"

    /**
     * `seq::enumerate` and `seq::zip_with_index` — `{ value, index }`.
     *
     * THE SAME CONCEPT AS [ITER_ENUM_INDEX]/[ITER_ENUM_VAL] UNDER DIFFERENT
     * NAMES, which writing them side by side is what makes visible. Unifying
     * the two spellings changes the stdlib's public API, so it is a decision
     * for its owner rather than something to quietly normalise here.
     */
    const val SEQ_ENUM_VALUE = "value"
    const val SEQ_ENUM_INDEX = "index"

    /** Function / transform callback. */
    const val F = "f"
    /** Predicate callback. */
    const val P = "p"
    /** Accumulator init value (fold, scan). */
    const val INIT = "init"
    /** Count/limit (take, drop). */
    const val N = "n"

    // ── Collection type prefixes ────────────────────────────────────────

    const val SEQ = "seq"
    const val ITER = "iter"
    const val LIST = "list"
    const val SET = "set"
    const val BYTES = "bytes"
    /** `nonemptylist`'s natives are spelled `__native_nel_*`. */
    const val NEL = "nel"
    const val SORTED_SET = "sorted_set"
    const val DEQUE = "deque"
    /** Map and sorted_map are ENTRY-based: their callbacks take a key and a
     *  value, so they are absent from every element-based predicate here. */
    const val MAP = "map"
    const val SORTED_MAP = "sorted_map"

    // ── Operation names ─────────────────────────────────────────────────
    // These mirror the function names in stdlib. Used both for building
    // native names and for structural classification in ChainHofClassifier.

    const val OP_FOLD = "fold"
    const val OP_FOLD_LEFT = "fold_left"
    const val OP_MAP = "map"
    const val OP_FILTER = "filter"
    const val OP_FOR_EACH = "for_each"
    const val OP_SUM = "sum"
    /** A map has two things to sum, so its value sum says which. */
    const val OP_SUM_VALUES = "sum_values"
    const val OP_COUNT = "count"
    const val OP_ANY = "any"
    const val OP_ALL = "all"
    const val OP_FIND = "find"
    const val OP_REDUCE = "reduce"
    const val OP_TAKE = "take"
    const val OP_DROP = "drop"
    const val OP_RANGE = "range"
    const val OP_FROM_SEQ = "from_seq"
    const val OP_TO_SEQ = "to_seq"
    const val OP_TO_LIST = "to_list"
    const val OP_TAKE_WHILE = "take_while"
    const val OP_DROP_WHILE = "drop_while"
    const val OP_NONE = "none"
    const val OP_FIRST = "first"
    const val OP_LAST = "last"
    const val OP_MIN = "min"
    const val OP_MAX = "max"
    const val OP_MIN_BY = "min_by"
    const val OP_MAX_BY = "max_by"
    const val OP_PARTITION = "partition"
    const val OP_SCAN = "scan"
    const val OP_SUM_FLOAT = "sum_float"
    const val OP_PRODUCT = "product"
    const val OP_PRODUCT_FLOAT = "product_float"
    const val OP_FIND_INDEX = "find_index"
    const val OP_UNFOLD = "unfold"
    const val OP_FROM_LIST = "from_list"
    const val OP_ONCE = "once"
    const val OP_EMPTY = "empty"
    const val OP_ENUMERATE = "enumerate"
    const val OP_CHAIN = "chain"
    const val OP_ZIP = "zip"
    const val OP_FLAT_MAP = "flat_map"
    const val OP_COUNT_ALL = "count_all"
    const val OP_SIZE = "size"


    // ── Resolving a label, once ─────────────────────────────────────────
    //
    // Everything above builds or compares spellings. Everything that used to
    // ASK those questions downstream — 44 sites across `ChainCanonicalizer` and
    // `ChainLetInliner` — now reads a decision the compiler took here, carried
    // on `CallNativeIR` as `ownerKind` and `opKind`. A `__native_…` name is a
    // label: it is for dispatching once, and after that the IR should say what
    // the node MEANS, not what it was called.

    /** Every collection prefix, longest first so `sorted_set` wins over `set`. */
    private val OWNERS: List<Pair<String, CollectionKind>> = listOf(
        SORTED_MAP to CollectionKind.SORTED_MAP,
        SORTED_SET to CollectionKind.SORTED_SET,
        SEQ to CollectionKind.SEQ,
        ITER to CollectionKind.ITER,
        LIST to CollectionKind.LIST,
        SET to CollectionKind.SET,
        DEQUE to CollectionKind.DEQUE,
        MAP to CollectionKind.MAP,
        BYTES to CollectionKind.BYTES,
    )

    /** `__native_{collection}_{op}` → what it means. Built once. */
    private val BY_NAME: Map<String, Pair<CollectionKind, NativeOp>> by lazy {
        val ops: List<Pair<String, NativeOp>> = listOf(
            OP_FOLD to NativeOp.FOLD,
            OP_FOLD_LEFT to NativeOp.FOLD_LEFT,
            OP_MAP to NativeOp.MAP,
            OP_FILTER to NativeOp.FILTER,
            OP_FOR_EACH to NativeOp.FOR_EACH,
            OP_SUM to NativeOp.SUM,
            OP_SUM_VALUES to NativeOp.SUM_VALUES,
            OP_COUNT to NativeOp.COUNT,
            OP_ANY to NativeOp.ANY,
            OP_ALL to NativeOp.ALL,
            OP_FIND to NativeOp.FIND,
            OP_REDUCE to NativeOp.REDUCE,
            OP_TAKE to NativeOp.TAKE,
            OP_DROP to NativeOp.DROP,
            OP_RANGE to NativeOp.RANGE,
            OP_FROM_SEQ to NativeOp.FROM_SEQ,
            OP_TO_SEQ to NativeOp.TO_SEQ,
            OP_TO_LIST to NativeOp.TO_LIST,
            OP_TAKE_WHILE to NativeOp.TAKE_WHILE,
            OP_DROP_WHILE to NativeOp.DROP_WHILE,
            OP_NONE to NativeOp.NONE_MATCH,
            OP_FIRST to NativeOp.FIRST,
            OP_LAST to NativeOp.LAST,
            OP_MIN to NativeOp.MIN,
            OP_MAX to NativeOp.MAX,
            OP_MIN_BY to NativeOp.MIN_BY,
            OP_MAX_BY to NativeOp.MAX_BY,
            OP_PARTITION to NativeOp.PARTITION,
            OP_SCAN to NativeOp.SCAN,
            OP_SUM_FLOAT to NativeOp.SUM_FLOAT,
            OP_PRODUCT to NativeOp.PRODUCT,
            OP_PRODUCT_FLOAT to NativeOp.PRODUCT_FLOAT,
            OP_FIND_INDEX to NativeOp.FIND_INDEX,
            OP_UNFOLD to NativeOp.UNFOLD,
            OP_FROM_LIST to NativeOp.FROM_LIST,
            OP_ONCE to NativeOp.ONCE,
            OP_EMPTY to NativeOp.EMPTY,
            OP_ENUMERATE to NativeOp.ENUMERATE,
            OP_CHAIN to NativeOp.CHAIN,
            OP_ZIP to NativeOp.ZIP,
            OP_FLAT_MAP to NativeOp.FLAT_MAP,
            OP_COUNT_ALL to NativeOp.COUNT_ALL,
            OP_SIZE to NativeOp.SIZE
        )
        val out = HashMap<String, Pair<CollectionKind, NativeOp>>(OWNERS.size * ops.size)
        for ((prefix, kind) in OWNERS) {
            for ((opName, op) in ops) {
                out[native(prefix, opName)] = kind to op
            }
        }
        out
    }

    /**
     * The collection whose module [name] belongs to, or null for a native that
     * belongs to none. Answers for EVERY native, not only the chain operations
     * — `__native_set_insert` is owned by Set and is not a chain op.
     */
    fun ownerOf(name: String): CollectionKind? {
        BY_NAME[name]?.let { return it.first }
        for ((prefix, kind) in OWNERS) {
            if (name.startsWith("${MagicParams.NATIVE_PREFIX}${prefix}_")) return kind
        }
        return null
    }

    /** Which chain operation [name] is, or null when it is not one. */
    fun opOf(name: String): NativeOp? = BY_NAME[name]?.second

    // ── Native name builder ─────────────────────────────────────────────

    /** Build native function name: `__native_{collection}_{operation}`. */
    fun native(collection: String, operation: String): String =
        "${MagicParams.NATIVE_PREFIX}${collection}_$operation"

    // ── Convenience: check if a native name is one of the collection variants ──

    /** True if [name] is `__native_seq_{op}`. */
    private fun isSeq(name: String, op: String): Boolean = name == native(SEQ, op)

    /** True if [name] is `__native_iter_{op}`. */
    private fun isIter(name: String, op: String): Boolean = name == native(ITER, op)

    /** True if [name] is `__native_list_{op}`. */
    private fun isList(name: String, op: String): Boolean = name == native(LIST, op)

    /** True if [name] is `__native_set_{op}`. */
    private fun isSet(name: String, op: String): Boolean = name == native(SET, op)

    /** True if [name] is `__native_sorted_set_{op}`. */
    private fun isSortedSet(name: String, op: String): Boolean = name == native(SORTED_SET, op)

    /** True if [name] is `__native_deque_{op}`. */
    private fun isDeque(name: String, op: String): Boolean = name == native(DEQUE, op)

    /** True if [name] is the seq OR iter variant of [op]. */
    private fun isSeqOrIter(name: String, op: String): Boolean =
        isSeq(name, op) || isIter(name, op)

    /** True if [name] is the seq, iter, OR list variant of [op]. */
    private fun isAnyCollection(name: String, op: String): Boolean =
        isSeq(name, op) || isIter(name, op) || isList(name, op)

    /** True if [name] is ANY element-based collection variant of [op].
     *  Excludes Map/SortedMap (entry-based callbacks). */
    private fun isAnyElementCollection(name: String, op: String): Boolean =
        isSeq(name, op) || isIter(name, op) || isList(name, op) ||
        isSet(name, op) || isSortedSet(name, op) || isDeque(name, op)

    /** True if [name] starts with `__native_seq_`. */
    private fun isSeqNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${SEQ}_")

    /** True if [name] starts with `__native_iter_`. */
    private fun isIterNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${ITER}_")

    /** True if [name] starts with `__native_list_`. */
    private fun isListNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${LIST}_")

    /** True if [name] starts with `__native_set_`. */
    private fun isSetNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${SET}_")

    /** True if [name] starts with `__native_sorted_set_`. */
    private fun isSortedSetNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${SORTED_SET}_")

    /** True if [name] starts with `__native_deque_`. */
    private fun isDequeNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${DEQUE}_")

    /**
     * True if [name] starts with `__native_bytes_`.
     *
     * BYTES IS DELIBERATELY ABSENT from [isAnyElementCollection] and from
     * [ELEMENT_COLLECTION_PREFIXES], which between them decide what may be
     * peeled as a STAGE. Two bytes operations TRUNCATE to 8 bits —
     * `bytes::map`'s callback result and `bytes::from_seq`'s elements both go
     * through `byteValue()` — so peeling either as an ordinary element stage
     * would drop the truncation and make a fused chain compute a DIFFERENT
     * answer: `bytes::map { f = { x } -> x * 2 }` over a byte of 200 is 144,
     * not 400. Bytes participates only as a SOURCE under a `fold` sink.
     */
    private fun isBytesNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${BYTES}_")

    /** All collection prefixes used for element-based fusion (body inspection). */
    val ELEMENT_COLLECTION_PREFIXES: List<String> = listOf(SEQ, ITER, LIST, SET, SORTED_SET, DEQUE)

    /**
     * Resolve the source field name for a native call. Seq/List uses [XS],
     * iter uses [IT], Set/SortedSet uses [S], Deque uses [D], Map uses [M].
     */
    /**
     * Which record field holds a chain op's SOURCE, by the collection that
     * owns the op — `it` for iter, `s` for a set, `d` for a deque, `b` for
     * bytes, `xs` for seq and list.
     *
     * Takes the resolved [CollectionKind] rather than the native's name: the
     * name is a label, and `ownerKind` is what it meant.
     */
    fun srcFieldFor(owner: CollectionKind?): String = when (owner) {
        CollectionKind.ITER -> IT
        CollectionKind.SET, CollectionKind.SORTED_SET -> S
        CollectionKind.DEQUE -> D
        CollectionKind.BYTES -> B
        else -> XS // seq, list, and anything that does not say
    }
}
