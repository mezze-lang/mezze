package com.mezze.language.typechecker.types.codec

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Result type (as TypeApplication)
 * Note: Result takes value type first, then error type
 */
private fun resultType(valueType: MezzeType, errorType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(valueType, errorType))
}

/**
 * Helper function to create Seq type
 */
private fun seqType(elementType: MezzeType): MezzeType {
    return MezzeType.SeqType(elementType)
}

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(elementType))
}

/**
 * Type signatures for JSON codec native functions.
 *
 * These functions provide the native JSON parsing and serialization
 * capabilities used by std/json.mz.
 *
 * Corresponds to:
 * - std/json.mz
 * - interpreter/src/main/kotlin/.../codec/JsonImplementations.kt
 */
object JsonTypes {

    // DecodeError type: {path: [String], message: String, expected: String, found: Option String}
    private val decodeErrorType = MezzeType.RecordType(mapOf(
        "path" to seqType(MezzeType.StringType),
        "message" to MezzeType.StringType,
        "expected" to MezzeType.StringType,
        "found" to optionType(MezzeType.StringType)
    ))

    // EncodeError type: {path: [String], message: String}
    private val encodeErrorType = MezzeType.RecordType(mapOf(
        "path" to seqType(MezzeType.StringType),
        "message" to MezzeType.StringType
    ))

    // Json variant type - we use a recursive TypeApplication to represent it
    // Note: The actual variant structure is defined in std/json.mz
    // For type checking, we use a nominal type reference
    private val jsonType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Json", emptyList())

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_json_parse: {s: String} -> Result Json DecodeError
        // Parses a JSON string into a Json variant
        context["__native_json_parse"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            resultType(jsonType, decodeErrorType)
        )

        // __native_json_serialize: {json: Json, pretty: Bool} -> Result String EncodeError
        // Serializes a Json variant to a JSON string
        context["__native_json_serialize"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "json" to jsonType,
                "pretty" to MezzeType.BoolType
            )),
            resultType(MezzeType.StringType, encodeErrorType)
        )

        // __native_json_serialize_bytes: {json: Json, pretty: Bool} -> Result Bytes EncodeError
        // Serializes a Json variant to UTF-8 bytes (DSL-JSON powered)
        context["__native_json_serialize_bytes"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "json" to jsonType,
                "pretty" to MezzeType.BoolType
            )),
            resultType(MezzeType.BytesType, encodeErrorType)
        )

        // __native_json_parse_bytes: {bytes: Bytes} -> Result Json DecodeError
        // Parses UTF-8 bytes as JSON into a Json variant (DSL-JSON powered)
        context["__native_json_parse_bytes"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("bytes" to MezzeType.BytesType)),
            resultType(jsonType, decodeErrorType)
        )

        // __native_json_escape_string: {s: String} -> String
        // Escapes special characters in a JSON string
        context["__native_json_escape_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("s" to MezzeType.StringType)),
            MezzeType.StringType
        )

        // __native_frac_to_float: {n: Frac} -> Float
        // Converts a fraction to a float
        context["__native_frac_to_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.FracType)),
            MezzeType.FloatType
        )

        // __native_float_to_frac: {f: Float} -> Frac
        // Converts a float to a fraction
        context["__native_float_to_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("f" to MezzeType.FloatType)),
            MezzeType.FracType
        )

        // __native_int_to_frac: {n: Int} -> Frac
        // Converts an integer to a fraction
        context["__native_int_to_frac"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FracType
        )

        // __native_int_to_float: {n: Int} -> Float
        // Converts an integer to a float
        context["__native_int_to_float"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("n" to MezzeType.IntType)),
            MezzeType.FloatType
        )

        // =====================================================================
        // STREAMING DECODE NATIVE FUNCTIONS
        // =====================================================================

        // Common parameter type for streaming decode functions
        val streamDecodeParams = MezzeType.RecordType(mapOf(
            "bytes" to MezzeType.BytesType,
            "offset" to MezzeType.IntType,
            "path" to seqType(MezzeType.StringType)
        ))

        // stream_decode_int: -> Result {value: Int, offset: Int} DecodeError
        context["__native_json_stream_decode_int"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("value" to MezzeType.IntType, "offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_float: -> Result {value: Float, offset: Int} DecodeError
        context["__native_json_stream_decode_float"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("value" to MezzeType.FloatType, "offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_string: -> Result {value: String, offset: Int} DecodeError
        context["__native_json_stream_decode_string"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("value" to MezzeType.StringType, "offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_bool: -> Result {value: Bool, offset: Int} DecodeError
        context["__native_json_stream_decode_bool"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf(
                    "value" to MezzeType.BoolType,
                    "offset" to MezzeType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_frac: -> Result {value: Frac, offset: Int} DecodeError
        context["__native_json_stream_decode_frac"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("value" to MezzeType.FracType, "offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_bytes: -> Result {value: Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_bytes"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("value" to MezzeType.BytesType, "offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_record_start: -> Result {offset: Int} DecodeError
        context["__native_json_stream_decode_record_start"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_record_field: {bytes, offset, key, path} -> Result {value: Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_record_field"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "bytes" to MezzeType.BytesType,
                "offset" to MezzeType.IntType,
                "key" to MezzeType.StringType,
                "path" to seqType(MezzeType.StringType)
            )),
            resultType(
                MezzeType.RecordType(mapOf("value" to MezzeType.BytesType, "offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_variant: -> Result {tag: String, payload: Option Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_variant"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf(
                    "tag" to MezzeType.StringType,
                    "payload" to optionType(MezzeType.BytesType),
                    "offset" to MezzeType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_list_start: -> Result {offset: Int} DecodeError
        context["__native_json_stream_decode_list_start"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // stream_decode_list_next: -> Result {value: Option Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_list_next"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf(
                    "value" to optionType(MezzeType.BytesType),
                    "offset" to MezzeType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_map_start: -> Result {offset: Int} DecodeError
        context["__native_json_stream_decode_map_start"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf("offset" to MezzeType.IntType)),
                decodeErrorType
            )
        )

        // Use anonymous record type {key: Bytes, value: Bytes} for structural compatibility
        val entryBytesBytes = MezzeType.RecordType(mapOf(
            "key" to MezzeType.BytesType,
            "value" to MezzeType.BytesType
        ))

        // stream_decode_map_next: -> Result {entry: Option {key: Bytes, value: Bytes}, offset: Int} DecodeError
        context["__native_json_stream_decode_map_next"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf(
                    "entry" to optionType(entryBytesBytes),
                    "offset" to MezzeType.IntType
                )),
                decodeErrorType
            )
        )

        // stream_decode_option: -> Result {value: Option Bytes, offset: Int} DecodeError
        context["__native_json_stream_decode_option"] = MezzeType.FunctionType(
            streamDecodeParams,
            resultType(
                MezzeType.RecordType(mapOf(
                    "value" to optionType(MezzeType.BytesType),
                    "offset" to MezzeType.IntType
                )),
                decodeErrorType
            )
        )
    }
}
