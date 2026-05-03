package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 */
private fun resultType(okType: MezzeType, errType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Result", listOf(okType, errType))
}

/**
 * Type signatures for Bytes (binary data) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/bytes.mz
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object BytesTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerBytesNatives(context)
    }

    private fun registerBytesNatives(context: MutableMap<String, MezzeType>) {
        // __native_bytes_empty: {} -> Bytes
        context["__native_bytes_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BytesType
        )

        // __native_bytes_from_string: {s: String} -> Bytes
        context["__native_bytes_from_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.BytesType
        )

        // __native_bytes_from_seq: {arr: [Int]} -> Bytes
        context["__native_bytes_from_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("arr" to MezzeType.SeqType(MezzeType.IntType))),
            MezzeType.BytesType
        )

        // __native_bytes_from_hex: {s: String} -> Option Bytes
        context["__native_bytes_from_hex"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.BytesType)
        )

        // __native_bytes_from_base64: {s: String} -> Option Bytes
        context["__native_bytes_from_base64"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            optionType(MezzeType.BytesType)
        )

        // __native_bytes_repeat: {b: Bytes, n: Int} -> Bytes
        context["__native_bytes_repeat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "n" to MezzeType.IntType)),
            MezzeType.BytesType
        )

        // __native_bytes_size: {b: Bytes} -> Int
        context["__native_bytes_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            MezzeType.IntType
        )

        // __native_bytes_get: {b: Bytes, i: Int} -> Option Int
        context["__native_bytes_get"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "i" to MezzeType.IntType)),
            optionType(MezzeType.IntType)
        )

        // __native_bytes_is_empty: {b: Bytes} -> Bool
        context["__native_bytes_is_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            MezzeType.BoolType
        )

        // __native_bytes_slice: {b: Bytes, start: Int, end: Int} -> Bytes
        context["__native_bytes_slice"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "b" to MezzeType.BytesType,
                "start" to MezzeType.IntType,
                "end" to MezzeType.IntType
            )),
            MezzeType.BytesType
        )

        // __native_bytes_take: {b: Bytes, n: Int} -> Bytes
        context["__native_bytes_take"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "n" to MezzeType.IntType)),
            MezzeType.BytesType
        )

        // __native_bytes_drop: {b: Bytes, n: Int} -> Bytes
        context["__native_bytes_drop"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "n" to MezzeType.IntType)),
            MezzeType.BytesType
        )

        // __native_bytes_concat: {b1: Bytes, b2: Bytes} -> Bytes
        context["__native_bytes_concat"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b1" to MezzeType.BytesType, "b2" to MezzeType.BytesType)),
            MezzeType.BytesType
        )

        // __native_bytes_concat_all: {bs: [Bytes]} -> Bytes
        // Efficient bulk concatenation with single allocation
        context["__native_bytes_concat_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("bs" to MezzeType.SeqType(MezzeType.BytesType))),
            MezzeType.BytesType
        )

        // __native_bytes_index_of: {b: Bytes, pattern: Bytes} -> Option Int
        context["__native_bytes_index_of"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "pattern" to MezzeType.BytesType)),
            optionType(MezzeType.IntType)
        )

        // __native_bytes_contains: {b: Bytes, pattern: Bytes} -> Bool
        context["__native_bytes_contains"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "pattern" to MezzeType.BytesType)),
            MezzeType.BoolType
        )

        // __native_bytes_starts_with: {b: Bytes, prefix: Bytes} -> Bool
        context["__native_bytes_starts_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "prefix" to MezzeType.BytesType)),
            MezzeType.BoolType
        )

        // __native_bytes_ends_with: {b: Bytes, suffix: Bytes} -> Bool
        context["__native_bytes_ends_with"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType, "suffix" to MezzeType.BytesType)),
            MezzeType.BoolType
        )

        // __native_bytes_equals: {b1: Bytes, b2: Bytes} -> Bool
        context["__native_bytes_equals"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b1" to MezzeType.BytesType, "b2" to MezzeType.BytesType)),
            MezzeType.BoolType
        )

        // __native_bytes_compare: {b1: Bytes, b2: Bytes} -> Int
        context["__native_bytes_compare"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b1" to MezzeType.BytesType, "b2" to MezzeType.BytesType)),
            MezzeType.IntType
        )

        // __native_bytes_to_str: {b: Bytes} -> Result String String
        context["__native_bytes_to_str"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            resultType(MezzeType.StringType, MezzeType.StringType)
        )

        // __native_bytes_to_seq: {b: Bytes} -> [Int]
        context["__native_bytes_to_seq"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            MezzeType.SeqType(MezzeType.IntType)
        )

        // __native_bytes_to_hex: {b: Bytes} -> String
        context["__native_bytes_to_hex"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            MezzeType.StringType
        )

        // __native_bytes_to_base64: {b: Bytes} -> String
        context["__native_bytes_to_base64"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            MezzeType.StringType
        )

        // __native_bytes_reverse: {b: Bytes} -> Bytes
        context["__native_bytes_reverse"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("b" to MezzeType.BytesType)),
            MezzeType.BytesType
        )

        // __native_bytes_map: {b: Bytes, f: {x: Int} -> Int} -> Bytes
        context["__native_bytes_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "b" to MezzeType.BytesType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
                    MezzeType.IntType
                )
            )),
            MezzeType.BytesType
        )

        // __native_bytes_fold: {b: Bytes, init: 'a, f: {acc: 'a, byte: Int} -> 'a} -> 'a
        run {
            val accType = MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            context["__native_bytes_fold"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "b" to MezzeType.BytesType,
                    "init" to accType,
                    "f" to MezzeType.FunctionType(
                        MezzeType.RecordType(mapOf("acc" to accType, "byte" to MezzeType.IntType)),
                        accType
                    )
                )),
                accType
            )
        }
    }
}
