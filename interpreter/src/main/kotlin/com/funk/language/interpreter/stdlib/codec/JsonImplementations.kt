package com.funk.language.interpreter.stdlib.codec

import com.funk.language.interpreter.*
import kotlinx.serialization.json.*

/**
 * Native implementations for JSON serialization/deserialization.
 *
 * These functions convert between:
 * - JSON strings (Funk String type)
 * - Json variant type (JNull, JBool, JInt, JFloat, JString, JArray, JObject)
 *
 * The Json variant type is defined in std/json.funk:
 * ```
 * type Json =
 *     | JNull
 *     | JBool {value: Bool}
 *     | JInt {value: Int}
 *     | JFloat {value: Float}
 *     | JString {value: String}
 *     | JArray {elements: List Json}
 *     | JObject {fields: List (codec::Entry String Json)}
 * ```
 */
object JsonImplementations {

    private val json = Json { prettyPrint = false }
    private val jsonPretty = Json { prettyPrint = true }

    /**
     * Register all JSON runtime implementations
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Parse JSON string to Json variant type
        // Returns: Result Json DecodeError
        context["__native_json_parse"] = NativeFunkFunction("__native_json_parse") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_parse", "expects FunkRecord")
            val jsonString = record.fields["s"] as? FunkString
                ?: throw NativeFunctionException("__native_json_parse", "expects 's' as String")

            try {
                val jsonElement = Json.parseToJsonElement(jsonString.value)
                val jsonValue = kotlinxToFunkJson(jsonElement)
                // Return Ok {val = jsonValue}
                FunkVariant("Ok", FunkRecord(mapOf("val" to jsonValue)))
            } catch (e: Exception) {
                // Return Err {error = {path = [], message = "...", expected = "JSON", found = None}}
                val error = FunkRecord(mapOf(
                    "path" to FunkList.empty(),
                    "message" to FunkString("JSON parse error: ${e.message}"),
                    "expected" to FunkString("JSON"),
                    "found" to FunkVariant("None", null)
                ))
                FunkVariant("Err", FunkRecord(mapOf("error" to error)))
            }
        }

        // Serialize Json variant type to JSON string
        // Returns: Result String EncodeError
        context["__native_json_serialize"] = NativeFunkFunction("__native_json_serialize") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_serialize", "expects FunkRecord")
            val jsonValue = record.fields["json"]
                ?: throw NativeFunctionException("__native_json_serialize", "expects 'json' field")
            val pretty = (record.fields["pretty"] as? FunkBool)?.value ?: false

            try {
                val jsonElement = funkJsonToKotlinx(jsonValue)
                val jsonString = if (pretty) {
                    jsonPretty.encodeToString(JsonElement.serializer(), jsonElement)
                } else {
                    json.encodeToString(JsonElement.serializer(), jsonElement)
                }
                // Return Ok {val = jsonString}
                FunkVariant("Ok", FunkRecord(mapOf("val" to FunkString(jsonString))))
            } catch (e: Exception) {
                // Return Err {error = {path = [], message = "..."}}
                val error = FunkRecord(mapOf(
                    "path" to FunkList.empty(),
                    "message" to FunkString("JSON serialize error: ${e.message}")
                ))
                FunkVariant("Err", FunkRecord(mapOf("error" to error)))
            }
        }

        // Serialize Json variant type to Bytes
        // Returns: Result Bytes EncodeError
        context["__native_json_to_bytes"] = NativeFunkFunction("__native_json_to_bytes") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_to_bytes", "expects FunkRecord")
            val jsonValue = record.fields["json"]
                ?: throw NativeFunctionException("__native_json_to_bytes", "expects 'json' field")

            try {
                val jsonElement = funkJsonToKotlinx(jsonValue)
                val jsonString = json.encodeToString(JsonElement.serializer(), jsonElement)
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                // Return Ok {val = bytes}
                FunkVariant("Ok", FunkRecord(mapOf("val" to FunkBytes(bytes))))
            } catch (e: Exception) {
                val error = FunkRecord(mapOf(
                    "path" to FunkList.empty(),
                    "message" to FunkString("JSON to bytes error: ${e.message}")
                ))
                FunkVariant("Err", FunkRecord(mapOf("error" to error)))
            }
        }

        // Parse Bytes to Json variant type
        // Returns: Result Json DecodeError
        context["__native_json_from_bytes"] = NativeFunkFunction("__native_json_from_bytes") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_from_bytes", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_from_bytes", "expects 'bytes' as Bytes")

            try {
                val jsonString = String(bytes.bytes, Charsets.UTF_8)
                val jsonElement = Json.parseToJsonElement(jsonString)
                val jsonValue = kotlinxToFunkJson(jsonElement)
                FunkVariant("Ok", FunkRecord(mapOf("val" to jsonValue)))
            } catch (e: Exception) {
                val error = FunkRecord(mapOf(
                    "path" to FunkList.empty(),
                    "message" to FunkString("JSON from bytes error: ${e.message}"),
                    "expected" to FunkString("JSON"),
                    "found" to FunkVariant("None", null)
                ))
                FunkVariant("Err", FunkRecord(mapOf("error" to error)))
            }
        }

        // Escape special characters in JSON string
        context["__native_json_escape_string"] = NativeFunkFunction("__native_json_escape_string") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_escape_string", "expects FunkRecord")
            val s = record.fields["s"] as? FunkString
                ?: throw NativeFunctionException("__native_json_escape_string", "expects 's' as String")

            val escaped = buildString {
                for (c in s.value) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c.code < 32) {
                            append("\\u${c.code.toString(16).padStart(4, '0')}")
                        } else {
                            append(c)
                        }
                    }
                }
            }
            FunkString(escaped)
        }

        // ========================================================================
        // STREAMING JSON DECODE FUNCTIONS
        // ========================================================================

        registerStreamingDecodeFunctions(context)
    }

    /**
     * Register streaming JSON decode native functions.
     */
    private fun registerStreamingDecodeFunctions(context: MutableMap<String, FunkValue>) {
        // Stream decode Int
        context["__native_json_stream_decode_int"] = NativeFunkFunction("__native_json_stream_decode_int") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_int", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_int", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeInt(bytes.bytes, offset, path)
        }

        // Stream decode Float
        context["__native_json_stream_decode_float"] = NativeFunkFunction("__native_json_stream_decode_float") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_float", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_float", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeFloat(bytes.bytes, offset, path)
        }

        // Stream decode String
        context["__native_json_stream_decode_string"] = NativeFunkFunction("__native_json_stream_decode_string") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_string", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_string", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeString(bytes.bytes, offset, path)
        }

        // Stream decode Bool
        context["__native_json_stream_decode_bool"] = NativeFunkFunction("__native_json_stream_decode_bool") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_bool", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_bool", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeBool(bytes.bytes, offset, path)
        }

        // Stream decode Frac (as JSON number)
        context["__native_json_stream_decode_frac"] = NativeFunkFunction("__native_json_stream_decode_frac") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_frac", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_frac", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeFrac(bytes.bytes, offset, path)
        }

        // Stream decode Bytes (base64 encoded string)
        context["__native_json_stream_decode_bytes"] = NativeFunkFunction("__native_json_stream_decode_bytes") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_bytes", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_bytes", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeBytes(bytes.bytes, offset, path)
        }

        // Stream decode record start (expects '{')
        context["__native_json_stream_decode_record_start"] = NativeFunkFunction("__native_json_stream_decode_record_start") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_record_start", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_record_start", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeRecordStart(bytes.bytes, offset, path)
        }

        // Stream decode record field
        context["__native_json_stream_decode_record_field"] = NativeFunkFunction("__native_json_stream_decode_record_field") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_record_field", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_record_field", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val key = (record.fields["key"] as? FunkString)?.value ?: ""
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeRecordField(bytes.bytes, offset, key, path)
        }

        // Stream decode variant
        context["__native_json_stream_decode_variant"] = NativeFunkFunction("__native_json_stream_decode_variant") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_variant", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_variant", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeVariant(bytes.bytes, offset, path)
        }

        // Stream decode list start
        context["__native_json_stream_decode_list_start"] = NativeFunkFunction("__native_json_stream_decode_list_start") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_list_start", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_list_start", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeListStart(bytes.bytes, offset, path)
        }

        // Stream decode list next element
        context["__native_json_stream_decode_list_next"] = NativeFunkFunction("__native_json_stream_decode_list_next") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_list_next", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_list_next", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeListNext(bytes.bytes, offset, path)
        }

        // Stream decode map start
        context["__native_json_stream_decode_map_start"] = NativeFunkFunction("__native_json_stream_decode_map_start") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_map_start", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_map_start", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeMapStart(bytes.bytes, offset, path)
        }

        // Stream decode map next entry
        context["__native_json_stream_decode_map_next"] = NativeFunkFunction("__native_json_stream_decode_map_next") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_map_next", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_map_next", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeMapNext(bytes.bytes, offset, path)
        }

        // Stream decode option (null or value)
        context["__native_json_stream_decode_option"] = NativeFunkFunction("__native_json_stream_decode_option") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_option", "expects FunkRecord")
            val bytes = record.fields["bytes"] as? FunkBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_option", "expects 'bytes'")
            val offset = (record.fields["offset"] as? FunkInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: FunkList.empty()

            streamDecodeOption(bytes.bytes, offset, path)
        }
    }

    // ========================================================================
    // STREAMING DECODE HELPERS
    // ========================================================================

    private fun skipWhitespace(bytes: ByteArray, offset: Int): Int {
        var pos = offset
        while (pos < bytes.size && bytes[pos].toInt().toChar().isWhitespace()) {
            pos++
        }
        return pos
    }

    private fun makeDecodeError(path: FunkValue, message: String, expected: String): FunkValue {
        val error = FunkRecord(mapOf(
            "path" to path,
            "message" to FunkString(message),
            "expected" to FunkString(expected),
            "found" to FunkVariant("None", null)
        ))
        return FunkVariant("Err", FunkRecord(mapOf("error" to error)))
    }

    private fun streamDecodeInt(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size) {
            return makeDecodeError(path, "Unexpected end of input", "Int")
        }

        val start = pos
        if (bytes[pos].toInt().toChar() == '-') pos++

        while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
            pos++
        }

        if (pos == start || (pos == start + 1 && bytes[start].toInt().toChar() == '-')) {
            return makeDecodeError(path, "Expected integer", "Int")
        }

        val numStr = String(bytes, start, pos - start, Charsets.UTF_8)
        return try {
            val value = numStr.toLong()
            FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkInt(value),
                    "offset" to FunkInt(pos.toLong())
                ))
            )))
        } catch (e: NumberFormatException) {
            makeDecodeError(path, "Invalid integer: $numStr", "Int")
        }
    }

    private fun streamDecodeFloat(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size) {
            return makeDecodeError(path, "Unexpected end of input", "Float")
        }

        val start = pos
        if (bytes[pos].toInt().toChar() == '-') pos++

        while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
            pos++
        }

        if (pos < bytes.size && bytes[pos].toInt().toChar() == '.') {
            pos++
            while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
                pos++
            }
        }

        // Handle scientific notation
        if (pos < bytes.size && (bytes[pos].toInt().toChar() == 'e' || bytes[pos].toInt().toChar() == 'E')) {
            pos++
            if (pos < bytes.size && (bytes[pos].toInt().toChar() == '+' || bytes[pos].toInt().toChar() == '-')) {
                pos++
            }
            while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) {
                pos++
            }
        }

        if (pos == start) {
            return makeDecodeError(path, "Expected float", "Float")
        }

        val numStr = String(bytes, start, pos - start, Charsets.UTF_8)
        return try {
            val value = numStr.toDouble()
            FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkFloat(value),
                    "offset" to FunkInt(pos.toLong())
                ))
            )))
        } catch (e: NumberFormatException) {
            makeDecodeError(path, "Invalid float: $numStr", "Float")
        }
    }

    private fun streamDecodeString(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '"') {
            return makeDecodeError(path, "Expected '\"'", "String")
        }
        pos++ // skip opening quote

        val sb = StringBuilder()
        while (pos < bytes.size) {
            val c = bytes[pos].toInt().toChar()
            when {
                c == '"' -> {
                    pos++ // skip closing quote
                    return FunkVariant("Ok", FunkRecord(mapOf(
                        "val" to FunkRecord(mapOf(
                            "value" to FunkString(sb.toString()),
                            "offset" to FunkInt(pos.toLong())
                        ))
                    )))
                }
                c == '\\' -> {
                    pos++
                    if (pos >= bytes.size) {
                        return makeDecodeError(path, "Unexpected end of string escape", "String")
                    }
                    val escaped = bytes[pos].toInt().toChar()
                    when (escaped) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= bytes.size) {
                                return makeDecodeError(path, "Invalid unicode escape", "String")
                            }
                            val hex = String(bytes, pos + 1, 4, Charsets.UTF_8)
                            try {
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            } catch (e: NumberFormatException) {
                                return makeDecodeError(path, "Invalid unicode escape: \\u$hex", "String")
                            }
                        }
                        else -> sb.append(escaped)
                    }
                    pos++
                }
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
        return makeDecodeError(path, "Unterminated string", "String")
    }

    private fun streamDecodeBool(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size) {
            return makeDecodeError(path, "Unexpected end of input", "Bool")
        }

        val remaining = bytes.size - pos
        if (remaining >= 4 && String(bytes, pos, 4, Charsets.UTF_8) == "true") {
            return FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkBool(true),
                    "offset" to FunkInt((pos + 4).toLong())
                ))
            )))
        }
        if (remaining >= 5 && String(bytes, pos, 5, Charsets.UTF_8) == "false") {
            return FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkBool(false),
                    "offset" to FunkInt((pos + 5).toLong())
                ))
            )))
        }
        return makeDecodeError(path, "Expected 'true' or 'false'", "Bool")
    }

    private fun streamDecodeFrac(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        // Frac is encoded as a JSON number, parse it as a string and convert
        // For now, just parse as a double and create a Frac
        val floatResult = streamDecodeFloat(bytes, offset, path)
        if (floatResult is FunkVariant && floatResult.constructor == "Ok") {
            val innerRecord = floatResult.value as FunkRecord
            val valRecord = innerRecord.fields["val"] as FunkRecord
            val floatValue = (valRecord.fields["value"] as FunkFloat).value
            val newOffset = valRecord.fields["offset"] as FunkInt

            // Convert to Frac - approximate as numerator/1000000
            val scale = 1000000L
            val numerator = (floatValue * scale).toLong()
            return FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkFrac(numerator, scale),
                    "offset" to newOffset
                ))
            )))
        }
        return floatResult
    }

    private fun streamDecodeBytes(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        // Bytes are encoded as base64 strings
        val stringResult = streamDecodeString(bytes, offset, path)
        if (stringResult is FunkVariant && stringResult.constructor == "Ok") {
            val innerRecord = stringResult.value as FunkRecord
            val valRecord = innerRecord.fields["val"] as FunkRecord
            val base64String = (valRecord.fields["value"] as FunkString).value
            val newOffset = valRecord.fields["offset"] as FunkInt

            return try {
                val decoded = java.util.Base64.getDecoder().decode(base64String)
                FunkVariant("Ok", FunkRecord(mapOf(
                    "val" to FunkRecord(mapOf(
                        "value" to FunkBytes(decoded),
                        "offset" to newOffset
                    ))
                )))
            } catch (e: Exception) {
                makeDecodeError(path, "Invalid base64: ${e.message}", "Bytes")
            }
        }
        return stringResult
    }

    private fun streamDecodeRecordStart(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '{') {
            return makeDecodeError(path, "Expected '{'", "Record")
        }
        pos++ // skip '{'
        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf("offset" to FunkInt(pos.toLong())))
        )))
    }

    private fun streamDecodeRecordField(bytes: ByteArray, offset: Int, key: String, path: FunkValue): FunkValue {
        // This is a simplified implementation - in practice you'd need to search for the key
        // For now, just skip to the value and return it as bytes
        var pos = skipWhitespace(bytes, offset)

        // Skip comma if present
        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)
        }

        // Check for end of object
        if (pos < bytes.size && bytes[pos].toInt().toChar() == '}') {
            return makeDecodeError(path, "Field '$key' not found", "Record field")
        }

        // Parse key
        val keyResult = streamDecodeString(bytes, pos, path)
        if (keyResult is FunkVariant && keyResult.constructor == "Err") {
            return keyResult
        }

        val keyRecord = ((keyResult as FunkVariant).value as FunkRecord).fields["val"] as FunkRecord
        val parsedKey = (keyRecord.fields["value"] as FunkString).value
        pos = (keyRecord.fields["offset"] as FunkInt).value.toInt()

        // Skip colon
        pos = skipWhitespace(bytes, pos)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != ':') {
            return makeDecodeError(path, "Expected ':'", "Record field")
        }
        pos++

        // Find the end of the value (need to handle nested structures)
        pos = skipWhitespace(bytes, pos)
        val valueStart = pos
        pos = skipJsonValue(bytes, pos)

        val valueBytes = bytes.sliceArray(valueStart until pos)

        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf(
                "value" to FunkBytes(valueBytes),
                "offset" to FunkInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeVariant(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        // Variant format: {"tag": "Name", "value": ...} or {"tag": "Name"}
        var pos = skipWhitespace(bytes, offset)

        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '{') {
            return makeDecodeError(path, "Expected '{'", "Variant")
        }
        pos++
        pos = skipWhitespace(bytes, pos)

        // Parse "tag" key
        val tagKeyResult = streamDecodeString(bytes, pos, path)
        if (tagKeyResult is FunkVariant && tagKeyResult.constructor == "Err") {
            return tagKeyResult
        }
        val tagKeyRecord = ((tagKeyResult as FunkVariant).value as FunkRecord).fields["val"] as FunkRecord
        val tagKey = (tagKeyRecord.fields["value"] as FunkString).value
        if (tagKey != "tag") {
            return makeDecodeError(path, "Expected 'tag' key, got '$tagKey'", "Variant")
        }
        pos = (tagKeyRecord.fields["offset"] as FunkInt).value.toInt()

        // Skip colon
        pos = skipWhitespace(bytes, pos)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != ':') {
            return makeDecodeError(path, "Expected ':'", "Variant")
        }
        pos++

        // Parse tag value
        val tagValueResult = streamDecodeString(bytes, pos, path)
        if (tagValueResult is FunkVariant && tagValueResult.constructor == "Err") {
            return tagValueResult
        }
        val tagValueRecord = ((tagValueResult as FunkVariant).value as FunkRecord).fields["val"] as FunkRecord
        val tag = (tagValueRecord.fields["value"] as FunkString).value
        pos = (tagValueRecord.fields["offset"] as FunkInt).value.toInt()

        pos = skipWhitespace(bytes, pos)

        // Check for value field or end
        var payload: FunkValue = FunkVariant("None", null)

        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)

            // Parse "value" key
            val valueKeyResult = streamDecodeString(bytes, pos, path)
            if (valueKeyResult is FunkVariant && valueKeyResult.constructor == "Ok") {
                val valueKeyRecord = ((valueKeyResult).value as FunkRecord).fields["val"] as FunkRecord
                val valueKey = (valueKeyRecord.fields["value"] as FunkString).value
                if (valueKey == "value") {
                    pos = (valueKeyRecord.fields["offset"] as FunkInt).value.toInt()

                    // Skip colon
                    pos = skipWhitespace(bytes, pos)
                    if (pos < bytes.size && bytes[pos].toInt().toChar() == ':') {
                        pos++
                        pos = skipWhitespace(bytes, pos)

                        // Get value bytes
                        val valueStart = pos
                        pos = skipJsonValue(bytes, pos)
                        val valueBytes = bytes.sliceArray(valueStart until pos)
                        payload = FunkVariant("Some", FunkRecord(mapOf("val" to FunkBytes(valueBytes))))
                    }
                }
            }
        }

        // Skip to end of object
        pos = skipWhitespace(bytes, pos)
        if (pos < bytes.size && bytes[pos].toInt().toChar() == '}') {
            pos++
        }

        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf(
                "tag" to FunkString(tag),
                "payload" to payload,
                "offset" to FunkInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeListStart(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '[') {
            return makeDecodeError(path, "Expected '['", "List")
        }
        pos++ // skip '['
        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf("offset" to FunkInt(pos.toLong())))
        )))
    }

    private fun streamDecodeListNext(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)

        // Skip comma if present
        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)
        }

        // Check for end of array
        if (pos >= bytes.size || bytes[pos].toInt().toChar() == ']') {
            val finalPos = if (pos < bytes.size) pos + 1 else pos
            return FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkVariant("None", null),
                    "offset" to FunkInt(finalPos.toLong())
                ))
            )))
        }

        // Get element bytes
        val valueStart = pos
        pos = skipJsonValue(bytes, pos)
        val valueBytes = bytes.sliceArray(valueStart until pos)

        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf(
                "value" to FunkVariant("Some", FunkRecord(mapOf("val" to FunkBytes(valueBytes)))),
                "offset" to FunkInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeMapStart(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '{') {
            return makeDecodeError(path, "Expected '{'", "Map")
        }
        pos++ // skip '{'
        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf("offset" to FunkInt(pos.toLong())))
        )))
    }

    private fun streamDecodeMapNext(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)

        // Skip comma if present
        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)
        }

        // Check for end of object
        if (pos >= bytes.size || bytes[pos].toInt().toChar() == '}') {
            val finalPos = if (pos < bytes.size) pos + 1 else pos
            return FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "entry" to FunkVariant("None", null),
                    "offset" to FunkInt(finalPos.toLong())
                ))
            )))
        }

        // Parse key
        val keyStart = pos
        val keyResult = streamDecodeString(bytes, pos, path)
        if (keyResult is FunkVariant && keyResult.constructor == "Err") {
            return keyResult
        }
        val keyRecord = ((keyResult as FunkVariant).value as FunkRecord).fields["val"] as FunkRecord
        pos = (keyRecord.fields["offset"] as FunkInt).value.toInt()
        val keyBytes = bytes.sliceArray(keyStart until pos)

        // Skip colon
        pos = skipWhitespace(bytes, pos)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != ':') {
            return makeDecodeError(path, "Expected ':'", "Map entry")
        }
        pos++

        // Get value bytes
        pos = skipWhitespace(bytes, pos)
        val valueStart = pos
        pos = skipJsonValue(bytes, pos)
        val valueBytes = bytes.sliceArray(valueStart until pos)

        // Return Entry record
        val entry = FunkRecord(mapOf(
            "key" to FunkBytes(keyBytes),
            "value" to FunkBytes(valueBytes)
        ))

        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf(
                "entry" to FunkVariant("Some", FunkRecord(mapOf("val" to entry))),
                "offset" to FunkInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeOption(bytes: ByteArray, offset: Int, path: FunkValue): FunkValue {
        var pos = skipWhitespace(bytes, offset)

        // Check for null
        val remaining = bytes.size - pos
        if (remaining >= 4 && String(bytes, pos, 4, Charsets.UTF_8) == "null") {
            return FunkVariant("Ok", FunkRecord(mapOf(
                "val" to FunkRecord(mapOf(
                    "value" to FunkVariant("None", null),
                    "offset" to FunkInt((pos + 4).toLong())
                ))
            )))
        }

        // Otherwise, get the value bytes
        val valueStart = pos
        pos = skipJsonValue(bytes, pos)
        val valueBytes = bytes.sliceArray(valueStart until pos)

        return FunkVariant("Ok", FunkRecord(mapOf(
            "val" to FunkRecord(mapOf(
                "value" to FunkVariant("Some", FunkRecord(mapOf("val" to FunkBytes(valueBytes)))),
                "offset" to FunkInt(pos.toLong())
            ))
        )))
    }

    /**
     * Skip over a complete JSON value, returning the new offset.
     */
    private fun skipJsonValue(bytes: ByteArray, offset: Int): Int {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size) return pos

        when (bytes[pos].toInt().toChar()) {
            '"' -> {
                // String - skip to closing quote
                pos++
                while (pos < bytes.size) {
                    val c = bytes[pos].toInt().toChar()
                    if (c == '"') {
                        return pos + 1
                    }
                    if (c == '\\') {
                        pos++ // skip escaped char
                    }
                    pos++
                }
                return pos
            }
            '{' -> {
                // Object - skip matching braces
                pos++
                var depth = 1
                while (pos < bytes.size && depth > 0) {
                    when (bytes[pos].toInt().toChar()) {
                        '{' -> depth++
                        '}' -> depth--
                        '"' -> {
                            pos++
                            while (pos < bytes.size && bytes[pos].toInt().toChar() != '"') {
                                if (bytes[pos].toInt().toChar() == '\\') pos++
                                pos++
                            }
                        }
                    }
                    pos++
                }
                return pos
            }
            '[' -> {
                // Array - skip matching brackets
                pos++
                var depth = 1
                while (pos < bytes.size && depth > 0) {
                    when (bytes[pos].toInt().toChar()) {
                        '[' -> depth++
                        ']' -> depth--
                        '"' -> {
                            pos++
                            while (pos < bytes.size && bytes[pos].toInt().toChar() != '"') {
                                if (bytes[pos].toInt().toChar() == '\\') pos++
                                pos++
                            }
                        }
                    }
                    pos++
                }
                return pos
            }
            't' -> return pos + 4 // true
            'f' -> return pos + 5 // false
            'n' -> return pos + 4 // null
            else -> {
                // Number - skip digits, dot, e, +, -
                while (pos < bytes.size) {
                    val c = bytes[pos].toInt().toChar()
                    if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                        pos++
                    } else {
                        break
                    }
                }
                return pos
            }
        }
    }

    /**
     * Convert a kotlinx.serialization JsonElement to Funk's Json variant type.
     */
    private fun kotlinxToFunkJson(element: JsonElement): FunkValue {
        return when (element) {
            is JsonNull -> FunkVariant("JNull", null)

            is JsonPrimitive -> {
                when {
                    element.isString -> FunkVariant("JString", FunkRecord(mapOf("value" to FunkString(element.content))))
                    element.content == "true" || element.content == "false" -> {
                        FunkVariant("JBool", FunkRecord(mapOf("value" to FunkBool(element.boolean))))
                    }
                    else -> {
                        // Try to parse as Long first, then Double
                        val longVal = element.longOrNull
                        if (longVal != null) {
                            FunkVariant("JInt", FunkRecord(mapOf("value" to FunkInt(longVal))))
                        } else {
                            val doubleVal = element.doubleOrNull
                            if (doubleVal != null) {
                                FunkVariant("JFloat", FunkRecord(mapOf("value" to FunkFloat(doubleVal))))
                            } else {
                                // Fallback to string
                                FunkVariant("JString", FunkRecord(mapOf("value" to FunkString(element.content))))
                            }
                        }
                    }
                }
            }

            is JsonArray -> {
                val elements = element.map { kotlinxToFunkJson(it) }
                val funkList = FunkList.fromKotlinList(elements)
                FunkVariant("JArray", FunkRecord(mapOf("elements" to funkList)))
            }

            is JsonObject -> {
                val entries = element.entries.map { (key, value) ->
                    // Entry type: {key: String, value: Json}
                    FunkRecord(mapOf(
                        "key" to FunkString(key),
                        "value" to kotlinxToFunkJson(value)
                    ))
                }
                val funkList = FunkList.fromKotlinList(entries)
                FunkVariant("JObject", FunkRecord(mapOf("fields" to funkList)))
            }
        }
    }

    /**
     * Convert Funk's Json variant type to a kotlinx.serialization JsonElement.
     */
    private fun funkJsonToKotlinx(value: FunkValue): JsonElement {
        return when (value) {
            is FunkVariant -> {
                when (value.constructor) {
                    "JNull" -> JsonNull

                    "JBool" -> {
                        val record = value.value as? FunkRecord
                            ?: throw IllegalArgumentException("JBool expected record payload")
                        val boolVal = record.fields["value"] as? FunkBool
                            ?: throw IllegalArgumentException("JBool expected 'value' field")
                        JsonPrimitive(boolVal.value)
                    }

                    "JInt" -> {
                        val record = value.value as? FunkRecord
                            ?: throw IllegalArgumentException("JInt expected record payload")
                        val intVal = record.fields["value"] as? FunkInt
                            ?: throw IllegalArgumentException("JInt expected 'value' field")
                        JsonPrimitive(intVal.value)
                    }

                    "JFloat" -> {
                        val record = value.value as? FunkRecord
                            ?: throw IllegalArgumentException("JFloat expected record payload")
                        val floatVal = record.fields["value"] as? FunkFloat
                            ?: throw IllegalArgumentException("JFloat expected 'value' field")
                        JsonPrimitive(floatVal.value)
                    }

                    "JString" -> {
                        val record = value.value as? FunkRecord
                            ?: throw IllegalArgumentException("JString expected record payload")
                        val strVal = record.fields["value"] as? FunkString
                            ?: throw IllegalArgumentException("JString expected 'value' field")
                        JsonPrimitive(strVal.value)
                    }

                    "JArray" -> {
                        val record = value.value as? FunkRecord
                            ?: throw IllegalArgumentException("JArray expected record payload")
                        val elements = record.fields["elements"]
                            ?: throw IllegalArgumentException("JArray expected 'elements' field")

                        val jsonElements = funkListToKotlinList(elements).map { funkJsonToKotlinx(it) }
                        JsonArray(jsonElements)
                    }

                    "JObject" -> {
                        val record = value.value as? FunkRecord
                            ?: throw IllegalArgumentException("JObject expected record payload")
                        val fields = record.fields["fields"]
                            ?: throw IllegalArgumentException("JObject expected 'fields' field")

                        val jsonObject = buildJsonObject {
                            funkListToKotlinList(fields).forEach { entry ->
                                val entryRecord = entry as? FunkRecord
                                    ?: throw IllegalArgumentException("JObject entry expected record")
                                val key = (entryRecord.fields["key"] as? FunkString)?.value
                                    ?: throw IllegalArgumentException("JObject entry expected 'key' field")
                                val entryValue = entryRecord.fields["value"]
                                    ?: throw IllegalArgumentException("JObject entry expected 'value' field")
                                put(key, funkJsonToKotlinx(entryValue))
                            }
                        }
                        jsonObject
                    }

                    else -> throw IllegalArgumentException("Unknown Json variant: ${value.constructor}")
                }
            }
            else -> throw IllegalArgumentException("Expected Json variant, got: ${value::class.simpleName}")
        }
    }

    /**
     * Convert a FunkList to a Kotlin List.
     */
    private fun funkListToKotlinList(list: FunkValue): List<FunkValue> {
        return when (list) {
            is FunkList -> list.toKotlinList()
            is FunkVariant -> {
                // Handle Nil and Cons representation
                if (list.constructor == "Nil") {
                    emptyList()
                } else if (list.constructor == "Cons") {
                    val record = list.value as? FunkRecord
                        ?: throw IllegalArgumentException("Cons expected record payload")
                    val head = record.fields["head"]
                        ?: throw IllegalArgumentException("Cons expected 'head' field")
                    val tail = record.fields["tail"]
                        ?: throw IllegalArgumentException("Cons expected 'tail' field")
                    listOf(head) + funkListToKotlinList(tail)
                } else {
                    throw IllegalArgumentException("Expected list variant, got: ${list.constructor}")
                }
            }
            else -> throw IllegalArgumentException("Expected FunkList, got: ${list::class.simpleName}")
        }
    }
}
