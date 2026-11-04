package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.interpreter.nodes.builtin.io.BytesNatives
import com.mezze.language.truffleir.SlotKind

/**
 * Bytes natives in DSL form.
 *
 * See [MapOps] for the measurement: the record ABI costs 2-3.3x the scalar ABI
 * for the same op, measured on one kernel before and after the port, because
 * `TypedASTCompiler` hands it a `MezzeRecord` built per call which the handler
 * then reads by field name.
 *
 * Bytes is worth porting ahead of the rarer collections because it sits on the
 * codec and IO paths, where a per-element loop is the normal shape.
 *
 * `size`, `take`, `drop`, `empty` and `from_seq` are NOT declared — their bare
 * names are in `NativeHofNames`, so declaring one would make
 * `ChainCanonicalizer` refuse the call and silently cost the fusion.
 * `SeqOpsFusionGuardTest` enforces that.
 *
 * Only ops taking a bytes RECEIVER are here. The constructors from a string
 * (`from_string`, `from_hex`, `from_base64`) read their argument through the
 * legacy `getString`, whose coercion has not been copied into [NativeHelpers]
 * yet; after `getSeq` cost twenty codec tests to a plain cast, an uncopied
 * coercion is left alone rather than guessed at.
 *
 * Unlike `getSeq`/`getList`, the legacy `getBytes` does not coerce — it casts or
 * throws — so [NativeHelpers.asBytes] is a faithful copy of exactly that.
 */
object BytesOps {
    /** Forces class load when referenced; used by NativeBootstrap. */
    @JvmField val MARKER: Unit = Unit

    private fun bytes(a: NativeArgs, i: Int, name: String = "b"): BytesNatives.MezzeBytes =
        NativeHelpers.asBytes(a.obj(i), name)

    private fun idx(a: NativeArgs, i: Int, name: String): Long =
        NativeHelpers.asLong(a.obj(i), name)

    // ==================== Bytes -> Bool ====================
    private val IS_EMPTY = NativeOp("__native_bytes_is_empty",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.BOOLEAN, pure = true,
    ) { a -> BytesNatives.isEmpty(bytes(a, 0)) }

    private val CONTAINS = NativeOp("__native_bytes_contains",
        listOf(Param("b", SlotKind.OBJECT), Param("pattern", SlotKind.OBJECT)),
        SlotKind.BOOLEAN, pure = true,
    ) { a -> BytesNatives.contains(bytes(a, 0), bytes(a, 1, "pattern")) }

    private val STARTS_WITH = NativeOp("__native_bytes_starts_with",
        listOf(Param("b", SlotKind.OBJECT), Param("prefix", SlotKind.OBJECT)),
        SlotKind.BOOLEAN, pure = true,
    ) { a -> BytesNatives.startsWith(bytes(a, 0), bytes(a, 1, "prefix")) }

    private val ENDS_WITH = NativeOp("__native_bytes_ends_with",
        listOf(Param("b", SlotKind.OBJECT), Param("suffix", SlotKind.OBJECT)),
        SlotKind.BOOLEAN, pure = true,
    ) { a -> BytesNatives.endsWith(bytes(a, 0), bytes(a, 1, "suffix")) }

    // ==================== Indexing and searching ====================
    private val GET = NativeOp("__native_bytes_get",
        listOf(Param("b", SlotKind.OBJECT), Param("i", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.get(bytes(a, 0), idx(a, 1, "i")) }

    private val INDEX_OF = NativeOp("__native_bytes_index_of",
        listOf(Param("b", SlotKind.OBJECT), Param("pattern", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.indexOf(bytes(a, 0), bytes(a, 1, "pattern")) }

    // ==================== Reshaping ====================
    private val SLICE = NativeOp("__native_bytes_slice",
        listOf(Param("b", SlotKind.OBJECT), Param("start", SlotKind.LONG), Param("end", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.slice(bytes(a, 0), idx(a, 1, "start"), idx(a, 2, "end")) }

    private val CONCAT = NativeOp("__native_bytes_concat",
        listOf(Param("b1", SlotKind.OBJECT), Param("b2", SlotKind.OBJECT)),
        SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.concat(bytes(a, 0, "b1"), bytes(a, 1, "b2")) }

    private val REVERSE = NativeOp("__native_bytes_reverse",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.reverse(bytes(a, 0)) }

    private val REPEAT = NativeOp("__native_bytes_repeat",
        listOf(Param("b", SlotKind.OBJECT), Param("n", SlotKind.LONG)),
        SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.repeat(bytes(a, 0), idx(a, 1, "n")) }

    // ==================== Decoding ====================
    private val TO_STR = NativeOp("__native_bytes_to_str",
        listOf(Param("b", SlotKind.OBJECT)), SlotKind.OBJECT, pure = true,
    ) { a -> BytesNatives.bytesToString(bytes(a, 0)) }
}
