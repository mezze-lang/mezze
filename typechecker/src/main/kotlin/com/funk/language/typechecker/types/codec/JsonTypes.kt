package com.funk.language.typechecker.types.codec

import com.funk.language.ast.FunkType

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: FunkType, errorType: FunkType): FunkType {
    return FunkType.TypeApplication("Result", listOf(valueType, errorType))
}

/**
 * Helper function to create List type (as TypeApplication)
 */
private fun listType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("List", listOf(elementType))
}

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for JSON codec native functions.
 *
 * These functions provide the native JSON parsing and serialization
 * capabilities used by std/json.funk.
 *
 * Corresponds to:
 * - std/json.funk
 * - interpreter/src/main/kotlin/.../codec/JsonImplementations.kt
 */
object JsonTypes {

    // DecodeError type: {path: List String, message: String, expected: String, found: Option String}
    private val decodeErrorType = FunkType.RecordType(mapOf(
        "path" to listType(FunkType.StringType),
        "message" to FunkType.StringType,
        "expected" to FunkType.StringType,
        "found" to optionType(FunkType.StringType)
    ))

    // EncodeError type: {path: List String, message: String}
    private val encodeErrorType = FunkType.RecordType(mapOf(
        "path" to listType(FunkType.StringType),
        "message" to FunkType.StringType
    ))

    // Json variant type - we use a recursive TypeApplication to represent it
    // Note: The actual variant structure is defined in std/json.funk
    // For type checking, we use a nominal type reference
    private val jsonType = FunkType.TypeApplication("Json", emptyList())

    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_json_parse: {s: String} -> Result Json DecodeError
        // Parses a JSON string into a Json variant
        context["__native_json_parse"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            resultType(jsonType, decodeErrorType)
        )

        // __native_json_serialize: {json: Json, pretty: Bool} -> Result String EncodeError
        // Serializes a Json variant to a JSON string
        context["__native_json_serialize"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "json" to jsonType,
                "pretty" to FunkType.BooleanType
            )),
            resultType(FunkType.StringType, encodeErrorType)
        )

        // __native_json_to_bytes: {json: Json} -> Result Bytes EncodeError
        // Serializes a Json variant to UTF-8 bytes
        context["__native_json_to_bytes"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("json" to jsonType)),
            resultType(FunkType.BytesType, encodeErrorType)
        )

        // __native_json_from_bytes: {bytes: Bytes} -> Result Json DecodeError
        // Parses UTF-8 bytes as JSON into a Json variant
        context["__native_json_from_bytes"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("bytes" to FunkType.BytesType)),
            resultType(jsonType, decodeErrorType)
        )

        // __native_json_escape_string: {s: String} -> String
        // Escapes special characters in a JSON string
        context["__native_json_escape_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("s" to FunkType.StringType)),
            FunkType.StringType
        )

        // __native_frac_to_float: {n: Frac} -> Float
        // Converts a fraction to a float
        context["__native_frac_to_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.FracType)),
            FunkType.FloatType
        )

        // __native_float_to_frac: {f: Float} -> Frac
        // Converts a float to a fraction
        context["__native_float_to_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("f" to FunkType.FloatType)),
            FunkType.FracType
        )

        // __native_int_to_frac: {n: Int} -> Frac
        // Converts an integer to a fraction
        context["__native_int_to_frac"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FracType
        )

        // __native_int_to_float: {n: Int} -> Float
        // Converts an integer to a float
        context["__native_int_to_float"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("n" to FunkType.IntType)),
            FunkType.FloatType
        )

        // =====================================================================
        // STREAMING DECODE NATIVE FUNCTIONS
        // =====================================================================

        // Common parameter type for streaming decode functions
        val streamDecodeParams = FunkType.RecordType(mapOf(
            "bytes" to FunkType.BytesType,
            "offset" to FunkType.IntType,
            "path" to listType(FunkType.StringType)
        ))

        // stream_decode_int: -> Result {value: Int, offset: Int} DecodeError
        context["__native_json_stream_decode_int"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("value" to FunkType.IntType, "offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_float: -> Result {value: Float, offset: Int} DecodeError
        context["__native_json_stream_decode_float"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("value" to FunkType.FloatType, "offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_string: -> Result {value: String, offset: Int} DecodeError
        context["__native_json_stream_decode_string"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("value" to FunkType.StringType, "offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_bool: -> Result {value: Bool, offset: Int} DecodeError
        context["__native_json_stream_decode_bool"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf(
                    "value" to FunkType.VariantType(mapOf("True" to null, "False" to null), null),
                    "offset" to FunkType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_frac: -> Result {value: Frac, offset: Int} DecodeError
        context["__native_json_stream_decode_frac"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("value" to FunkType.FracType, "offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_bytes: -> Result {value: Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_bytes"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("value" to FunkType.BytesType, "offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_record_start: -> Result {offset: Int} DecodeError
        context["__native_json_stream_decode_record_start"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_record_field: {bytes, offset, key, path} -> Result {value: Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_record_field"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "bytes" to FunkType.BytesType,
                "offset" to FunkType.IntType,
                "key" to FunkType.StringType,
                "path" to listType(FunkType.StringType)
            )),
            resultType(
                FunkType.RecordType(mapOf("value" to FunkType.BytesType, "offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_variant: -> Result {tag: String, payload: Option Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_variant"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf(
                    "tag" to FunkType.StringType,
                    "payload" to optionType(FunkType.BytesType),
                    "offset" to FunkType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_list_start: -> Result {offset: Int} DecodeError
        context["__native_json_stream_decode_list_start"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_list_next: -> Result {value: Option Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_list_next"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf(
                    "value" to optionType(FunkType.BytesType),
                    "offset" to FunkType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_map_start: -> Result {offset: Int} DecodeError
        context["__native_json_stream_decode_map_start"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf("offset" to FunkType.IntType)),
                decodeErrorType
            )
        )

        // Use anonymous record type {key: Bytes, value: Bytes} for structural compatibility
        val entryBytesBytes = FunkType.RecordType(mapOf(
            "key" to FunkType.BytesType,
            "value" to FunkType.BytesType
        ))

        // stream_decode_map_next: -> Result {entry: Option {key: Bytes, value: Bytes}, offset: Int} DecodeError
        context["__native_json_stream_decode_map_next"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf(
                    "entry" to optionType(entryBytesBytes),
                    "offset" to FunkType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_option: -> Result {value: Option Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_option"] = FunkType.FunctionType(
            streamDecodeParams,
            resultType(
                FunkType.RecordType(mapOf(
                    "value" to optionType(FunkType.BytesType),
                    "offset" to FunkType.IntType
                )),
                decodeErrorType
            )
        )
    }
}
