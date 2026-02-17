package com.mezze.language.interpreter.stdlib.codec

import com.mezze.language.interpreter.*
import kotlinx.serialization.json.*

/**
 * Native implementations for JSON serialization/deserialization.
 *
 * These functions convert between:
 * - JSON strings (Mezze String type)
 * - Json variant type (JNull, JBool, JInt, JFloat, JString, JArray, JObject)
 *
 * The Json variant type is defined in std/json.mz:
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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        // Parse JSON string to Json variant type
        // Returns: Result Json DecodeError
        context["__native_json_parse"] = NativeMezzeFunction("__native_json_parse") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_parse", "expects MezzeRecord")
            val jsonString = record.fields["s"] as? MezzeString
                ?: throw NativeFunctionException("__native_json_parse", "expects 's' as String")

            try {
                val jsonElement = Json.parseToJsonElement(jsonString.value)
                val jsonValue = kotlinxToMezzeJson(jsonElement)
                // Return Ok {val = jsonValue}
                MezzeVariant("Ok", MezzeRecord(mapOf("val" to jsonValue)))
            } catch (e: Exception) {
                // Return Err {error = {path = [], message = "...", expected = "JSON", found = None}}
                val error = MezzeRecord(mapOf(
                    "path" to MezzeList.empty(),
                    "message" to MezzeString("JSON parse error: ${e.message}"),
                    "expected" to MezzeString("JSON"),
                    "found" to MezzeVariant("None", null)
                ))
                MezzeVariant("Err", MezzeRecord(mapOf("error" to error)))
            }
        }

        // Serialize Json variant type to JSON string
        // Returns: Result String EncodeError
        context["__native_json_serialize"] = NativeMezzeFunction("__native_json_serialize") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_serialize", "expects MezzeRecord")
            val jsonValue = record.fields["json"]
                ?: throw NativeFunctionException("__native_json_serialize", "expects 'json' field")
            val pretty = (record.fields["pretty"] as? MezzeBool)?.value ?: false

            try {
                val jsonElement = mezzeJsonToKotlinx(jsonValue)
                val jsonString = if (pretty) {
                    jsonPretty.encodeToString(JsonElement.serializer(), jsonElement)
                } else {
                    json.encodeToString(JsonElement.serializer(), jsonElement)
                }
                // Return Ok {val = jsonString}
                MezzeVariant("Ok", MezzeRecord(mapOf("val" to MezzeString(jsonString))))
            } catch (e: Exception) {
                // Return Err {error = {path = [], message = "..."}}
                val error = MezzeRecord(mapOf(
                    "path" to MezzeList.empty(),
                    "message" to MezzeString("JSON serialize error: ${e.message}")
                ))
                MezzeVariant("Err", MezzeRecord(mapOf("error" to error)))
            }
        }

        // Serialize Json variant type to Bytes
        // Returns: Result Bytes EncodeError
        context["__native_json_to_bytes"] = NativeMezzeFunction("__native_json_to_bytes") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_to_bytes", "expects MezzeRecord")
            val jsonValue = record.fields["json"]
                ?: throw NativeFunctionException("__native_json_to_bytes", "expects 'json' field")

            try {
                val jsonElement = mezzeJsonToKotlinx(jsonValue)
                val jsonString = json.encodeToString(JsonElement.serializer(), jsonElement)
                val bytes = jsonString.toByteArray(Charsets.UTF_8)
                // Return Ok {val = bytes}
                MezzeVariant("Ok", MezzeRecord(mapOf("val" to MezzeBytes(bytes))))
            } catch (e: Exception) {
                val error = MezzeRecord(mapOf(
                    "path" to MezzeList.empty(),
                    "message" to MezzeString("JSON to bytes error: ${e.message}")
                ))
                MezzeVariant("Err", MezzeRecord(mapOf("error" to error)))
            }
        }

        // Parse Bytes to Json variant type
        // Returns: Result Json DecodeError
        context["__native_json_from_bytes"] = NativeMezzeFunction("__native_json_from_bytes") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_from_bytes", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_from_bytes", "expects 'bytes' as Bytes")

            try {
                val jsonString = String(bytes.bytes, Charsets.UTF_8)
                val jsonElement = Json.parseToJsonElement(jsonString)
                val jsonValue = kotlinxToMezzeJson(jsonElement)
                MezzeVariant("Ok", MezzeRecord(mapOf("val" to jsonValue)))
            } catch (e: Exception) {
                val error = MezzeRecord(mapOf(
                    "path" to MezzeList.empty(),
                    "message" to MezzeString("JSON from bytes error: ${e.message}"),
                    "expected" to MezzeString("JSON"),
                    "found" to MezzeVariant("None", null)
                ))
                MezzeVariant("Err", MezzeRecord(mapOf("error" to error)))
            }
        }

        // Escape special characters in JSON string
        context["__native_json_escape_string"] = NativeMezzeFunction("__native_json_escape_string") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_escape_string", "expects MezzeRecord")
            val s = record.fields["s"] as? MezzeString
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
            MezzeString(escaped)
        }

        // ========================================================================
        // STREAMING JSON DECODE FUNCTIONS
        // ========================================================================

        registerStreamingDecodeFunctions(context)
    }

    /**
     * Register streaming JSON decode native functions.
     */
    private fun registerStreamingDecodeFunctions(context: MutableMap<String, MezzeValue>) {
        // Stream decode Int
        context["__native_json_stream_decode_int"] = NativeMezzeFunction("__native_json_stream_decode_int") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_int", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_int", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeInt(bytes.bytes, offset, path)
        }

        // Stream decode Float
        context["__native_json_stream_decode_float"] = NativeMezzeFunction("__native_json_stream_decode_float") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_float", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_float", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeFloat(bytes.bytes, offset, path)
        }

        // Stream decode String
        context["__native_json_stream_decode_string"] = NativeMezzeFunction("__native_json_stream_decode_string") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_string", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_string", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeString(bytes.bytes, offset, path)
        }

        // Stream decode Bool
        context["__native_json_stream_decode_bool"] = NativeMezzeFunction("__native_json_stream_decode_bool") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_bool", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_bool", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeBool(bytes.bytes, offset, path)
        }

        // Stream decode Frac (as JSON number)
        context["__native_json_stream_decode_frac"] = NativeMezzeFunction("__native_json_stream_decode_frac") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_frac", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_frac", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeFrac(bytes.bytes, offset, path)
        }

        // Stream decode Bytes (base64 encoded string)
        context["__native_json_stream_decode_bytes"] = NativeMezzeFunction("__native_json_stream_decode_bytes") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_bytes", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_bytes", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeBytes(bytes.bytes, offset, path)
        }

        // Stream decode record start (expects '{')
        context["__native_json_stream_decode_record_start"] = NativeMezzeFunction("__native_json_stream_decode_record_start") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_record_start", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_record_start", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeRecordStart(bytes.bytes, offset, path)
        }

        // Stream decode record field
        context["__native_json_stream_decode_record_field"] = NativeMezzeFunction("__native_json_stream_decode_record_field") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_record_field", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_record_field", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val key = (record.fields["key"] as? MezzeString)?.value ?: ""
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeRecordField(bytes.bytes, offset, key, path)
        }

        // Stream decode variant
        context["__native_json_stream_decode_variant"] = NativeMezzeFunction("__native_json_stream_decode_variant") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_variant", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_variant", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeVariant(bytes.bytes, offset, path)
        }

        // Stream decode list start
        context["__native_json_stream_decode_list_start"] = NativeMezzeFunction("__native_json_stream_decode_list_start") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_list_start", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_list_start", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeListStart(bytes.bytes, offset, path)
        }

        // Stream decode list next element
        context["__native_json_stream_decode_list_next"] = NativeMezzeFunction("__native_json_stream_decode_list_next") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_list_next", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_list_next", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeListNext(bytes.bytes, offset, path)
        }

        // Stream decode map start
        context["__native_json_stream_decode_map_start"] = NativeMezzeFunction("__native_json_stream_decode_map_start") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_map_start", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_map_start", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeMapStart(bytes.bytes, offset, path)
        }

        // Stream decode map next entry
        context["__native_json_stream_decode_map_next"] = NativeMezzeFunction("__native_json_stream_decode_map_next") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_map_next", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_map_next", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

            streamDecodeMapNext(bytes.bytes, offset, path)
        }

        // Stream decode option (null or value)
        context["__native_json_stream_decode_option"] = NativeMezzeFunction("__native_json_stream_decode_option") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_json_stream_decode_option", "expects MezzeRecord")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_json_stream_decode_option", "expects 'bytes'")
            val offset = (record.fields["offset"] as? MezzeInt)?.value?.toInt() ?: 0
            val path = record.fields["path"] ?: MezzeList.empty()

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

    private fun makeDecodeError(path: MezzeValue, message: String, expected: String): MezzeValue {
        val error = MezzeRecord(mapOf(
            "path" to path,
            "message" to MezzeString(message),
            "expected" to MezzeString(expected),
            "found" to MezzeVariant("None", null)
        ))
        return MezzeVariant("Err", MezzeRecord(mapOf("error" to error)))
    }

    private fun streamDecodeInt(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
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
            MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeInt(value),
                    "offset" to MezzeInt(pos.toLong())
                ))
            )))
        } catch (e: NumberFormatException) {
            makeDecodeError(path, "Invalid integer: $numStr", "Int")
        }
    }

    private fun streamDecodeFloat(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
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
            MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeFloat(value),
                    "offset" to MezzeInt(pos.toLong())
                ))
            )))
        } catch (e: NumberFormatException) {
            makeDecodeError(path, "Invalid float: $numStr", "Float")
        }
    }

    private fun streamDecodeString(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
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
                    return MezzeVariant("Ok", MezzeRecord(mapOf(
                        "val" to MezzeRecord(mapOf(
                            "value" to MezzeString(sb.toString()),
                            "offset" to MezzeInt(pos.toLong())
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

    private fun streamDecodeBool(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size) {
            return makeDecodeError(path, "Unexpected end of input", "Bool")
        }

        val remaining = bytes.size - pos
        if (remaining >= 4 && String(bytes, pos, 4, Charsets.UTF_8) == "true") {
            return MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeBool(true),
                    "offset" to MezzeInt((pos + 4).toLong())
                ))
            )))
        }
        if (remaining >= 5 && String(bytes, pos, 5, Charsets.UTF_8) == "false") {
            return MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeBool(false),
                    "offset" to MezzeInt((pos + 5).toLong())
                ))
            )))
        }
        return makeDecodeError(path, "Expected 'true' or 'false'", "Bool")
    }

    private fun streamDecodeFrac(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        // Frac is encoded as a JSON number, parse it as a string and convert
        // For now, just parse as a double and create a Frac
        val floatResult = streamDecodeFloat(bytes, offset, path)
        if (floatResult is MezzeVariant && floatResult.constructor == "Ok") {
            val innerRecord = floatResult.value as MezzeRecord
            val valRecord = innerRecord.fields["val"] as MezzeRecord
            val floatValue = (valRecord.fields["value"] as MezzeFloat).value
            val newOffset = valRecord.fields["offset"] as MezzeInt

            // Convert to Frac - approximate as numerator/1000000
            val scale = 1000000L
            val numerator = (floatValue * scale).toLong()
            return MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeFrac(numerator, scale),
                    "offset" to newOffset
                ))
            )))
        }
        return floatResult
    }

    private fun streamDecodeBytes(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        // Bytes are encoded as base64 strings
        val stringResult = streamDecodeString(bytes, offset, path)
        if (stringResult is MezzeVariant && stringResult.constructor == "Ok") {
            val innerRecord = stringResult.value as MezzeRecord
            val valRecord = innerRecord.fields["val"] as MezzeRecord
            val base64String = (valRecord.fields["value"] as MezzeString).value
            val newOffset = valRecord.fields["offset"] as MezzeInt

            return try {
                val decoded = java.util.Base64.getDecoder().decode(base64String)
                MezzeVariant("Ok", MezzeRecord(mapOf(
                    "val" to MezzeRecord(mapOf(
                        "value" to MezzeBytes(decoded),
                        "offset" to newOffset
                    ))
                )))
            } catch (e: Exception) {
                makeDecodeError(path, "Invalid base64: ${e.message}", "Bytes")
            }
        }
        return stringResult
    }

    private fun streamDecodeRecordStart(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '{') {
            return makeDecodeError(path, "Expected '{'", "Record")
        }
        pos++ // skip '{'
        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf("offset" to MezzeInt(pos.toLong())))
        )))
    }

    private fun streamDecodeRecordField(bytes: ByteArray, offset: Int, key: String, path: MezzeValue): MezzeValue {
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
        if (keyResult is MezzeVariant && keyResult.constructor == "Err") {
            return keyResult
        }

        val keyRecord = ((keyResult as MezzeVariant).value as MezzeRecord).fields["val"] as MezzeRecord
        val parsedKey = (keyRecord.fields["value"] as MezzeString).value
        pos = (keyRecord.fields["offset"] as MezzeInt).value.toInt()

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

        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf(
                "value" to MezzeBytes(valueBytes),
                "offset" to MezzeInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeVariant(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        // Variant format: {"tag": "Name", "value": ...} or {"tag": "Name"}
        var pos = skipWhitespace(bytes, offset)

        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '{') {
            return makeDecodeError(path, "Expected '{'", "Variant")
        }
        pos++
        pos = skipWhitespace(bytes, pos)

        // Parse "tag" key
        val tagKeyResult = streamDecodeString(bytes, pos, path)
        if (tagKeyResult is MezzeVariant && tagKeyResult.constructor == "Err") {
            return tagKeyResult
        }
        val tagKeyRecord = ((tagKeyResult as MezzeVariant).value as MezzeRecord).fields["val"] as MezzeRecord
        val tagKey = (tagKeyRecord.fields["value"] as MezzeString).value
        if (tagKey != "tag") {
            return makeDecodeError(path, "Expected 'tag' key, got '$tagKey'", "Variant")
        }
        pos = (tagKeyRecord.fields["offset"] as MezzeInt).value.toInt()

        // Skip colon
        pos = skipWhitespace(bytes, pos)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != ':') {
            return makeDecodeError(path, "Expected ':'", "Variant")
        }
        pos++

        // Parse tag value
        val tagValueResult = streamDecodeString(bytes, pos, path)
        if (tagValueResult is MezzeVariant && tagValueResult.constructor == "Err") {
            return tagValueResult
        }
        val tagValueRecord = ((tagValueResult as MezzeVariant).value as MezzeRecord).fields["val"] as MezzeRecord
        val tag = (tagValueRecord.fields["value"] as MezzeString).value
        pos = (tagValueRecord.fields["offset"] as MezzeInt).value.toInt()

        pos = skipWhitespace(bytes, pos)

        // Check for value field or end
        var payload: MezzeValue = MezzeVariant("None", null)

        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)

            // Parse "value" key
            val valueKeyResult = streamDecodeString(bytes, pos, path)
            if (valueKeyResult is MezzeVariant && valueKeyResult.constructor == "Ok") {
                val valueKeyRecord = ((valueKeyResult).value as MezzeRecord).fields["val"] as MezzeRecord
                val valueKey = (valueKeyRecord.fields["value"] as MezzeString).value
                if (valueKey == "value") {
                    pos = (valueKeyRecord.fields["offset"] as MezzeInt).value.toInt()

                    // Skip colon
                    pos = skipWhitespace(bytes, pos)
                    if (pos < bytes.size && bytes[pos].toInt().toChar() == ':') {
                        pos++
                        pos = skipWhitespace(bytes, pos)

                        // Get value bytes
                        val valueStart = pos
                        pos = skipJsonValue(bytes, pos)
                        val valueBytes = bytes.sliceArray(valueStart until pos)
                        payload = MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeBytes(valueBytes))))
                    }
                }
            }
        }

        // Skip to end of object
        pos = skipWhitespace(bytes, pos)
        if (pos < bytes.size && bytes[pos].toInt().toChar() == '}') {
            pos++
        }

        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf(
                "tag" to MezzeString(tag),
                "payload" to payload,
                "offset" to MezzeInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeListStart(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '[') {
            return makeDecodeError(path, "Expected '['", "List")
        }
        pos++ // skip '['
        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf("offset" to MezzeInt(pos.toLong())))
        )))
    }

    private fun streamDecodeListNext(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)

        // Skip comma if present
        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)
        }

        // Check for end of array
        if (pos >= bytes.size || bytes[pos].toInt().toChar() == ']') {
            val finalPos = if (pos < bytes.size) pos + 1 else pos
            return MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeVariant("None", null),
                    "offset" to MezzeInt(finalPos.toLong())
                ))
            )))
        }

        // Get element bytes
        val valueStart = pos
        pos = skipJsonValue(bytes, pos)
        val valueBytes = bytes.sliceArray(valueStart until pos)

        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf(
                "value" to MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeBytes(valueBytes)))),
                "offset" to MezzeInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeMapStart(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)
        if (pos >= bytes.size || bytes[pos].toInt().toChar() != '{') {
            return makeDecodeError(path, "Expected '{'", "Map")
        }
        pos++ // skip '{'
        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf("offset" to MezzeInt(pos.toLong())))
        )))
    }

    private fun streamDecodeMapNext(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)

        // Skip comma if present
        if (pos < bytes.size && bytes[pos].toInt().toChar() == ',') {
            pos++
            pos = skipWhitespace(bytes, pos)
        }

        // Check for end of object
        if (pos >= bytes.size || bytes[pos].toInt().toChar() == '}') {
            val finalPos = if (pos < bytes.size) pos + 1 else pos
            return MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "entry" to MezzeVariant("None", null),
                    "offset" to MezzeInt(finalPos.toLong())
                ))
            )))
        }

        // Parse key
        val keyStart = pos
        val keyResult = streamDecodeString(bytes, pos, path)
        if (keyResult is MezzeVariant && keyResult.constructor == "Err") {
            return keyResult
        }
        val keyRecord = ((keyResult as MezzeVariant).value as MezzeRecord).fields["val"] as MezzeRecord
        pos = (keyRecord.fields["offset"] as MezzeInt).value.toInt()
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
        val entry = MezzeRecord(mapOf(
            "key" to MezzeBytes(keyBytes),
            "value" to MezzeBytes(valueBytes)
        ))

        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf(
                "entry" to MezzeVariant("Some", MezzeRecord(mapOf("val" to entry))),
                "offset" to MezzeInt(pos.toLong())
            ))
        )))
    }

    private fun streamDecodeOption(bytes: ByteArray, offset: Int, path: MezzeValue): MezzeValue {
        var pos = skipWhitespace(bytes, offset)

        // Check for null
        val remaining = bytes.size - pos
        if (remaining >= 4 && String(bytes, pos, 4, Charsets.UTF_8) == "null") {
            return MezzeVariant("Ok", MezzeRecord(mapOf(
                "val" to MezzeRecord(mapOf(
                    "value" to MezzeVariant("None", null),
                    "offset" to MezzeInt((pos + 4).toLong())
                ))
            )))
        }

        // Otherwise, get the value bytes
        val valueStart = pos
        pos = skipJsonValue(bytes, pos)
        val valueBytes = bytes.sliceArray(valueStart until pos)

        return MezzeVariant("Ok", MezzeRecord(mapOf(
            "val" to MezzeRecord(mapOf(
                "value" to MezzeVariant("Some", MezzeRecord(mapOf("val" to MezzeBytes(valueBytes)))),
                "offset" to MezzeInt(pos.toLong())
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
     * Convert a kotlinx.serialization JsonElement to Mezze's Json variant type.
     */
    private fun kotlinxToMezzeJson(element: JsonElement): MezzeValue {
        return when (element) {
            is JsonNull -> MezzeVariant("JNull", null)

            is JsonPrimitive -> {
                when {
                    element.isString -> MezzeVariant("JString", MezzeRecord(mapOf("val" to MezzeString(element.content))))
                    element.content == "true" || element.content == "false" -> {
                        MezzeVariant("JBool", MezzeRecord(mapOf("val" to MezzeBool(element.boolean))))
                    }
                    else -> {
                        // Try to parse as Long first, then Double
                        val longVal = element.longOrNull
                        if (longVal != null) {
                            MezzeVariant("JInt", MezzeRecord(mapOf("val" to MezzeInt(longVal))))
                        } else {
                            val doubleVal = element.doubleOrNull
                            if (doubleVal != null) {
                                MezzeVariant("JFloat", MezzeRecord(mapOf("val" to MezzeFloat(doubleVal))))
                            } else {
                                // Fallback to string
                                MezzeVariant("JString", MezzeRecord(mapOf("val" to MezzeString(element.content))))
                            }
                        }
                    }
                }
            }

            is JsonArray -> {
                val elements = element.map { kotlinxToMezzeJson(it) }
                // JArray.items is now Array, not List
                val mezzeArray = MezzeArrayList(elements)
                MezzeVariant("JArray", MezzeRecord(mapOf("items" to mezzeArray)))
            }

            is JsonObject -> {
                val entries = element.entries.map { (key, value) ->
                    // Entry type: {key: String, value: Json}
                    MezzeRecord(mapOf(
                        "key" to MezzeString(key),
                        "value" to kotlinxToMezzeJson(value)
                    ))
                }
                // JObject.fields is now Array, not List
                val mezzeArray = MezzeArrayList(entries)
                MezzeVariant("JObject", MezzeRecord(mapOf("fields" to mezzeArray)))
            }
        }
    }

    /**
     * Convert Mezze's Json variant type to a kotlinx.serialization JsonElement.
     */
    private fun mezzeJsonToKotlinx(value: MezzeValue): JsonElement {
        return when (value) {
            is MezzeVariant -> {
                when (value.constructor) {
                    "JNull" -> JsonNull

                    "JBool" -> {
                        val record = value.value as? MezzeRecord
                            ?: throw IllegalArgumentException("JBool expected record payload")
                        val boolVal = record.fields["val"] as? MezzeBool
                            ?: throw IllegalArgumentException("JBool expected 'val' field")
                        JsonPrimitive(boolVal.value)
                    }

                    "JInt" -> {
                        val record = value.value as? MezzeRecord
                            ?: throw IllegalArgumentException("JInt expected record payload")
                        val intVal = record.fields["val"] as? MezzeInt
                            ?: throw IllegalArgumentException("JInt expected 'val' field")
                        JsonPrimitive(intVal.value)
                    }

                    "JFloat" -> {
                        val record = value.value as? MezzeRecord
                            ?: throw IllegalArgumentException("JFloat expected record payload")
                        val floatVal = record.fields["val"] as? MezzeFloat
                            ?: throw IllegalArgumentException("JFloat expected 'val' field")
                        JsonPrimitive(floatVal.value)
                    }

                    "JString" -> {
                        val record = value.value as? MezzeRecord
                            ?: throw IllegalArgumentException("JString expected record payload")
                        val strVal = record.fields["val"] as? MezzeString
                            ?: throw IllegalArgumentException("JString expected 'val' field")
                        JsonPrimitive(strVal.value)
                    }

                    "JArray" -> {
                        val record = value.value as? MezzeRecord
                            ?: throw IllegalArgumentException("JArray expected record payload")
                        val elements = record.fields["items"]
                            ?: throw IllegalArgumentException("JArray expected 'items' field")

                        val jsonElements = mezzeListToKotlinList(elements).map { mezzeJsonToKotlinx(it) }
                        JsonArray(jsonElements)
                    }

                    "JObject" -> {
                        val record = value.value as? MezzeRecord
                            ?: throw IllegalArgumentException("JObject expected record payload")
                        val fields = record.fields["fields"]
                            ?: throw IllegalArgumentException("JObject expected 'fields' field")

                        val jsonObject = buildJsonObject {
                            mezzeListToKotlinList(fields).forEach { entry ->
                                val entryRecord = entry as? MezzeRecord
                                    ?: throw IllegalArgumentException("JObject entry expected record")
                                val key = (entryRecord.fields["key"] as? MezzeString)?.value
                                    ?: throw IllegalArgumentException("JObject entry expected 'key' field")
                                val entryValue = entryRecord.fields["value"]
                                    ?: throw IllegalArgumentException("JObject entry expected 'value' field")
                                put(key, mezzeJsonToKotlinx(entryValue))
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
     * Convert a MezzeList to a Kotlin List.
     */
    private fun mezzeListToKotlinList(list: MezzeValue): List<MezzeValue> {
        return when (list) {
            is MezzeList -> list.toKotlinList()
            is MezzeArrayList -> list.toList()  // Handle arrays (JArray.items and JObject.fields are now Arrays)
            is MezzeVariant -> {
                // Handle Nil and Cons representation
                if (list.constructor == "Nil") {
                    emptyList()
                } else if (list.constructor == "Cons") {
                    val record = list.value as? MezzeRecord
                        ?: throw IllegalArgumentException("Cons expected record payload")
                    val head = record.fields["head"]
                        ?: throw IllegalArgumentException("Cons expected 'head' field")
                    val tail = record.fields["tail"]
                        ?: throw IllegalArgumentException("Cons expected 'tail' field")
                    listOf(head) + mezzeListToKotlinList(tail)
                } else {
                    throw IllegalArgumentException("Expected list variant, got: ${list.constructor}")
                }
            }
            else -> throw IllegalArgumentException("Expected MezzeList, got: ${list::class.simpleName}")
        }
    }
}
