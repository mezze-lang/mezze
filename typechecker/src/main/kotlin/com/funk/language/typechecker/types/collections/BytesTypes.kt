package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Helper function to create Result type (as TypeApplication)
 */
private fun resultType(okType: FunkType, errType: FunkType): FunkType {
    return FunkType.TypeApplication("Result", listOf(okType, errType))
}

/**
 * Type signatures for Bytes (binary data) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/bytes.funk
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object BytesTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerBytesNatives(context)
    }

    private fun registerBytesNatives(context: MutableMap<String, FunkType>) {
        // __native_bytes_empty: {} -> Bytes
        context["__native_bytes_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.BytesType
        )

        // __native_bytes_from_string: {s: String} -> Bytes
        context["__native_bytes_from_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.BytesType
        )

        // __native_bytes_from_array: {arr: [Int]} -> Bytes
        context["__native_bytes_from_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("arr" to FunkType.ArrayType(FunkType.IntType))),
            FunkType.BytesType
        )

        // __native_bytes_from_hex: {s: String} -> Option Bytes
        context["__native_bytes_from_hex"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.BytesType)
        )

        // __native_bytes_from_base64: {s: String} -> Option Bytes
        context["__native_bytes_from_base64"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            optionType(FunkType.BytesType)
        )

        // __native_bytes_repeat: {b: Bytes, n: Int} -> Bytes
        context["__native_bytes_repeat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "n" to FunkType.IntType)),
            FunkType.BytesType
        )

        // __native_bytes_length: {b: Bytes} -> Int
        context["__native_bytes_length"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            FunkType.IntType
        )

        // __native_bytes_get: {b: Bytes, i: Int} -> Option Int
        context["__native_bytes_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "i" to FunkType.IntType)),
            optionType(FunkType.IntType)
        )

        // __native_bytes_is_empty: {b: Bytes} -> Bool
        context["__native_bytes_is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            FunkType.BooleanType
        )

        // __native_bytes_slice: {b: Bytes, start: Int, end: Int} -> Bytes
        context["__native_bytes_slice"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "b" to FunkType.BytesType,
                "start" to FunkType.IntType,
                "end" to FunkType.IntType
            )),
            FunkType.BytesType
        )

        // __native_bytes_take: {b: Bytes, n: Int} -> Bytes
        context["__native_bytes_take"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "n" to FunkType.IntType)),
            FunkType.BytesType
        )

        // __native_bytes_drop: {b: Bytes, n: Int} -> Bytes
        context["__native_bytes_drop"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "n" to FunkType.IntType)),
            FunkType.BytesType
        )

        // __native_bytes_concat: {b1: Bytes, b2: Bytes} -> Bytes
        context["__native_bytes_concat"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b1" to FunkType.BytesType, "b2" to FunkType.BytesType)),
            FunkType.BytesType
        )

        // __native_bytes_concat_all: {bs: [Bytes]} -> Bytes
        context["__native_bytes_concat_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("bs" to FunkType.ArrayType(FunkType.BytesType))),
            FunkType.BytesType
        )

        // __native_bytes_index_of: {b: Bytes, pattern: Bytes} -> Option Int
        context["__native_bytes_index_of"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "pattern" to FunkType.BytesType)),
            optionType(FunkType.IntType)
        )

        // __native_bytes_contains: {b: Bytes, pattern: Bytes} -> Bool
        context["__native_bytes_contains"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "pattern" to FunkType.BytesType)),
            FunkType.BooleanType
        )

        // __native_bytes_starts_with: {b: Bytes, prefix: Bytes} -> Bool
        context["__native_bytes_starts_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "prefix" to FunkType.BytesType)),
            FunkType.BooleanType
        )

        // __native_bytes_ends_with: {b: Bytes, suffix: Bytes} -> Bool
        context["__native_bytes_ends_with"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType, "suffix" to FunkType.BytesType)),
            FunkType.BooleanType
        )

        // __native_bytes_equals: {b1: Bytes, b2: Bytes} -> Bool
        context["__native_bytes_equals"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b1" to FunkType.BytesType, "b2" to FunkType.BytesType)),
            FunkType.BooleanType
        )

        // __native_bytes_to_string: {b: Bytes} -> Result String String
        context["__native_bytes_to_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            resultType(FunkType.StringType, FunkType.StringType)
        )

        // __native_bytes_to_array: {b: Bytes} -> [Int]
        context["__native_bytes_to_array"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            FunkType.ArrayType(FunkType.IntType)
        )

        // __native_bytes_to_hex: {b: Bytes} -> String
        context["__native_bytes_to_hex"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            FunkType.StringType
        )

        // __native_bytes_to_base64: {b: Bytes} -> String
        context["__native_bytes_to_base64"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            FunkType.StringType
        )

        // __native_bytes_reverse: {b: Bytes} -> Bytes
        context["__native_bytes_reverse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("b" to FunkType.BytesType)),
            FunkType.BytesType
        )

        // __native_bytes_map: {b: Bytes, f: {x: Int} -> Int} -> Bytes
        context["__native_bytes_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "b" to FunkType.BytesType,
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.IntType)),
                    FunkType.IntType
                )
            )),
            FunkType.BytesType
        )

        // __native_bytes_fold: {b: Bytes, init: 'a, f: {acc: 'a, byte: Int} -> 'a} -> 'a
        run {
            val accType = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
            context["__native_bytes_fold"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "b" to FunkType.BytesType,
                    "init" to accType,
                    "f" to FunkType.FunctionType(
                        FunkType.RecordType(mapOf("acc" to accType, "byte" to FunkType.IntType)),
                        accType
                    )
                )),
                accType
            )
        }
    }
}
