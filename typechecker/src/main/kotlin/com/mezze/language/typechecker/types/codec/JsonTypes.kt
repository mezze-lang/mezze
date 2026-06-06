package com.mezze.language.typechecker.types.codec

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

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
    private val decodeErrorType = rec(
        "path" to seq(str),
        "message" to str,
        "expected" to str,
        "found" to option(str)
    )

    // EncodeError type: {path: [String], message: String}
    private val encodeErrorType = rec(
        "path" to seq(str),
        "message" to str
    )

    // Json variant type - nominal type reference
    private val jsonType = WellKnownTypeApp.of("Json", emptyList())

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // __native_json_parse: {s: String} -> Result Json DecodeError
        fn("__native_json_parse") {
            param("s" to str)
            returns(result(jsonType, decodeErrorType))
        }

        // __native_json_serialize: {json: Json, pretty: Bool} -> Result String EncodeError
        fn("__native_json_serialize") {
            param("json" to jsonType, "pretty" to bool)
            returns(result(str, encodeErrorType))
        }

        // __native_json_serialize_bytes: {json: Json, pretty: Bool} -> Result Bytes EncodeError
        fn("__native_json_serialize_bytes") {
            param("json" to jsonType, "pretty" to bool)
            returns(result(bytes, encodeErrorType))
        }

        // __native_json_parse_bytes: {bytes: Bytes} -> Result Json DecodeError
        fn("__native_json_parse_bytes") {
            param("bytes" to bytes)
            returns(result(jsonType, decodeErrorType))
        }

        // __native_json_escape_string: {s: String} -> String
        fn("__native_json_escape_string") { param("s" to str); returns(str) }

        // __native_frac_to_float: {n: Frac} -> Float
        fn("__native_frac_to_float") { param("n" to frac); returns(float) }

        // __native_float_to_frac: {f: Float} -> Frac
        fn("__native_float_to_frac") { param("f" to float); returns(frac) }

        // __native_int_to_frac: {n: Int} -> Frac
        fn("__native_int_to_frac") { param("n" to int); returns(frac) }

        // __native_int_to_float: {n: Int} -> Float
        fn("__native_int_to_float") { param("n" to int); returns(float) }

        // =====================================================================
        // STREAMING DECODE NATIVE FUNCTIONS
        // =====================================================================

        // Common parameter type for streaming decode functions
        val streamDecodeParams = rec("bytes" to bytes, "offset" to int, "path" to seq(str))

        // stream_decode_int: -> Result {value: Int, offset: Int} DecodeError
        fn("__native_json_stream_decode_int") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("value" to int, "offset" to int), decodeErrorType))
        }

        // stream_decode_float: -> Result {value: Float, offset: Int} DecodeError
        fn("__native_json_stream_decode_float") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("value" to float, "offset" to int), decodeErrorType))
        }

        // stream_decode_string: -> Result {value: String, offset: Int} DecodeError
        fn("__native_json_stream_decode_string") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("value" to str, "offset" to int), decodeErrorType))
        }

        // stream_decode_bool: -> Result {value: Bool, offset: Int} DecodeError
        fn("__native_json_stream_decode_bool") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("value" to bool, "offset" to int), decodeErrorType))
        }

        // stream_decode_frac: -> Result {value: Frac, offset: Int} DecodeError
        fn("__native_json_stream_decode_frac") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("value" to frac, "offset" to int), decodeErrorType))
        }

        // stream_decode_bytes: -> Result {value: Bytes, offset: Int} DecodeError
        fn("__native_json_stream_decode_bytes") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("value" to bytes, "offset" to int), decodeErrorType))
        }

        // stream_decode_record_start: -> Result {offset: Int} DecodeError
        fn("__native_json_stream_decode_record_start") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("offset" to int), decodeErrorType))
        }

        // stream_decode_record_field: {bytes, offset, key, path} -> Result {value: Bytes, offset: Int} DecodeError
        fn("__native_json_stream_decode_record_field") {
            param("bytes" to bytes, "offset" to int, "key" to str, "path" to seq(str))
            returns(result(rec("value" to bytes, "offset" to int), decodeErrorType))
        }

        // stream_decode_variant: -> Result {tag: String, payload: Option Bytes, offset: Int} DecodeError
        fn("__native_json_stream_decode_variant") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(
                rec("tag" to str, "payload" to option(bytes), "offset" to int),
                decodeErrorType
            ))
        }

        // stream_decode_list_start: -> Result {offset: Int} DecodeError
        fn("__native_json_stream_decode_list_start") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("offset" to int), decodeErrorType))
        }

        // stream_decode_list_next: -> Result {value: Option Bytes, offset: Int} DecodeError
        fn("__native_json_stream_decode_list_next") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(
                rec("value" to option(bytes), "offset" to int),
                decodeErrorType
            ))
        }

        // stream_decode_map_start: -> Result {offset: Int} DecodeError
        fn("__native_json_stream_decode_map_start") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(rec("offset" to int), decodeErrorType))
        }

        // stream_decode_map_next: -> Result {entry: Option {key: Bytes, value: Bytes}, offset: Int} DecodeError
        fn("__native_json_stream_decode_map_next") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(
                rec("entry" to option(rec("key" to bytes, "value" to bytes)), "offset" to int),
                decodeErrorType
            ))
        }

        // stream_decode_option: -> Result {value: Option Bytes, offset: Int} DecodeError
        fn("__native_json_stream_decode_option") {
            param("bytes" to bytes, "offset" to int, "path" to seq(str))
            returns(result(
                rec("value" to option(bytes), "offset" to int),
                decodeErrorType
            ))
        }
    }
}
