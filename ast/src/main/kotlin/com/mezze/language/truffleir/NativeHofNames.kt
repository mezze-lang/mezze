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

    /** All names that mean "the collection source position". */
    val SRC_FIELDS: Set<String> = setOf(XS, SRC, IT, S, D, M)

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
    const val SORTED_SET = "sorted_set"
    const val DEQUE = "deque"

    // ── Operation names ─────────────────────────────────────────────────
    // These mirror the function names in stdlib. Used both for building
    // native names and for structural classification in ChainHofClassifier.

    const val OP_FOLD = "fold"
    const val OP_FOLD_LEFT = "fold_left"
    const val OP_MAP = "map"
    const val OP_FILTER = "filter"
    const val OP_FOR_EACH = "for_each"
    const val OP_SUM = "sum"
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

    // ── Native name builder ─────────────────────────────────────────────

    /** Build native function name: `__native_{collection}_{operation}`. */
    fun native(collection: String, operation: String): String =
        "${MagicParams.NATIVE_PREFIX}${collection}_$operation"

    // ── Convenience: check if a native name is one of the collection variants ──

    /** True if [name] is `__native_seq_{op}`. */
    fun isSeq(name: String, op: String): Boolean = name == native(SEQ, op)

    /** True if [name] is `__native_iter_{op}`. */
    fun isIter(name: String, op: String): Boolean = name == native(ITER, op)

    /** True if [name] is `__native_list_{op}`. */
    fun isList(name: String, op: String): Boolean = name == native(LIST, op)

    /** True if [name] is `__native_set_{op}`. */
    fun isSet(name: String, op: String): Boolean = name == native(SET, op)

    /** True if [name] is `__native_sorted_set_{op}`. */
    fun isSortedSet(name: String, op: String): Boolean = name == native(SORTED_SET, op)

    /** True if [name] is `__native_deque_{op}`. */
    fun isDeque(name: String, op: String): Boolean = name == native(DEQUE, op)

    /** True if [name] is the seq OR iter variant of [op]. */
    fun isSeqOrIter(name: String, op: String): Boolean =
        isSeq(name, op) || isIter(name, op)

    /** True if [name] is the seq, iter, OR list variant of [op]. */
    fun isAnyCollection(name: String, op: String): Boolean =
        isSeq(name, op) || isIter(name, op) || isList(name, op)

    /** True if [name] is ANY element-based collection variant of [op].
     *  Excludes Map/SortedMap (entry-based callbacks). */
    fun isAnyElementCollection(name: String, op: String): Boolean =
        isSeq(name, op) || isIter(name, op) || isList(name, op) ||
        isSet(name, op) || isSortedSet(name, op) || isDeque(name, op)

    /** True if [name] starts with `__native_seq_`. */
    fun isSeqNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${SEQ}_")

    /** True if [name] starts with `__native_iter_`. */
    fun isIterNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${ITER}_")

    /** True if [name] starts with `__native_list_`. */
    fun isListNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${LIST}_")

    /** True if [name] starts with `__native_set_`. */
    fun isSetNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${SET}_")

    /** True if [name] starts with `__native_sorted_set_`. */
    fun isSortedSetNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${SORTED_SET}_")

    /** True if [name] starts with `__native_deque_`. */
    fun isDequeNative(name: String): Boolean = name.startsWith("${MagicParams.NATIVE_PREFIX}${DEQUE}_")

    /** All collection prefixes used for element-based fusion (body inspection). */
    val ELEMENT_COLLECTION_PREFIXES: List<String> = listOf(SEQ, ITER, LIST, SET, SORTED_SET, DEQUE)

    /**
     * Resolve the source field name for a native call. Seq/List uses [XS],
     * iter uses [IT], Set/SortedSet uses [S], Deque uses [D], Map uses [M].
     */
    fun srcFieldFor(nativeName: String): String = when {
        isIterNative(nativeName) -> IT
        isSetNative(nativeName) || isSortedSetNative(nativeName) -> S
        isDequeNative(nativeName) -> D
        else -> XS // seq, list
    }
}
