package com.mezze.language.parsedast

import kotlinx.serialization.Serializable

/**
 * Standard metadata field names.
 *
 * These are the fields recognized by the compiler and tools.
 * Custom fields are allowed — the compiler ignores unknown fields.
 * Add new standard fields here as constants.
 */
object MetadataFields {
    /** Mark a declaration as deprecated. Value: Bool. */
    const val DEPRECATED = "deprecated"

    /** Version when the declaration was added or deprecated. Value: Str. */
    const val SINCE = "since"

    /** Free-text note from the author (deprecation reason, migration guide, etc). Value: Str. */
    const val NOTE = "note"

    /**
     * On an ability: what to tell someone whose type has no impl of it. Replaces
     * the compiler's generic "implement: impl …" advice; the fact line ("`T`
     * does not implement `A`") stays the compiler's. Value: Str.
     */
    const val MISSING = "missing"

    /**
     * On an effect: what to tell someone who performs it with nothing handling
     * it — usually which performer to wrap the code in. Value: Str.
     */
    const val UNHANDLED = "unhandled"
}

/**
 * Structured metadata attached to a declaration via `#{ key: value, ... }` syntax.
 *
 * Placement rules:
 * - Appears after `##` doc comments, before the declaration
 * - Exactly one `#{ }` block per declaration
 * - A blank line or `#` regular comment resets (breaks attachment)
 * - Does NOT affect the declaration's content hash
 */
@Serializable
data class ParsedMetadata(
    val fields: Map<String, ParsedMetadataValue>,
    val loc: Loc
) {
    /** Check if the declaration is marked as deprecated. */
    val isDeprecated: Boolean
        get() = (fields[MetadataFields.DEPRECATED] as? ParsedMetadataValue.BoolVal)?.value == true

    /** Get the 'since' version string, if present. */
    val since: String?
        get() = (fields[MetadataFields.SINCE] as? ParsedMetadataValue.StringVal)?.value

    /** Get the 'note' string, if present. */
    val note: String?
        get() = (fields[MetadataFields.NOTE] as? ParsedMetadataValue.StringVal)?.value

    /** The author's message for a type with no impl of this ability, if written. */
    val missing: String?
        get() = (fields[MetadataFields.MISSING] as? ParsedMetadataValue.StringVal)?.value

    /** The author's message for this effect performed with nothing handling it, if written. */
    val unhandled: String?
        get() = (fields[MetadataFields.UNHANDLED] as? ParsedMetadataValue.StringVal)?.value

    /**
     * The block as JSON text: what the store keeps and `std::codebase` hands a
     * program to decode into its own type. Built with a JSON encoder, so any
     * string the metadata syntax can write (`"\n"`, `"\\"`) stays valid JSON.
     */
    fun toJsonText(): String =
        kotlinx.serialization.json.JsonObject(fields.mapValues { (_, v) -> v.toJson() }).toString()

    companion object {
        /**
         * The inverse of [toJsonText]: the block written at [loc]. A number with a
         * fraction or an exponent is a float, any other number an int — the
         * encoder writes a float's `.0`. Not an object, a `null`, or text that is
         * not JSON → [IllegalArgumentException].
         */
        fun fromJsonText(text: String, loc: Loc): ParsedMetadata {
            val json = try {
                kotlinx.serialization.json.Json.parseToJsonElement(text)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw IllegalArgumentException("metadata is not JSON: ${e.message}", e)
            }
            val obj = json as? kotlinx.serialization.json.JsonObject
                ?: throw IllegalArgumentException("metadata is a JSON object, not `$json`")
            return ParsedMetadata(obj.mapValues { (_, v) -> metadataValue(v) }, loc)
        }
    }
}

private fun metadataValue(j: kotlinx.serialization.json.JsonElement): ParsedMetadataValue = when (j) {
    is kotlinx.serialization.json.JsonNull -> throw IllegalArgumentException("metadata has no `null`")
    is kotlinx.serialization.json.JsonPrimitive -> when {
        j.isString -> ParsedMetadataValue.StringVal(j.content)
        j.content == "true" || j.content == "false" -> ParsedMetadataValue.BoolVal(j.content == "true")
        j.content.any { it == '.' || it == 'e' || it == 'E' } || j.content == "NaN" || j.content.endsWith("Infinity") ->
            ParsedMetadataValue.FloatVal(j.content.toDoubleOrNull() ?: throw IllegalArgumentException("`${j.content}` is not a number"))
        else -> ParsedMetadataValue.IntVal(j.content.toLongOrNull() ?: throw IllegalArgumentException("`${j.content}` is not an Int"))
    }
    is kotlinx.serialization.json.JsonArray -> ParsedMetadataValue.ListVal(j.map(::metadataValue))
    is kotlinx.serialization.json.JsonObject -> ParsedMetadataValue.RecordVal(j.mapValues { (_, v) -> metadataValue(v) })
}

private fun ParsedMetadataValue.toJson(): kotlinx.serialization.json.JsonElement = when (this) {
    is ParsedMetadataValue.StringVal -> kotlinx.serialization.json.JsonPrimitive(value)
    is ParsedMetadataValue.IntVal -> kotlinx.serialization.json.JsonPrimitive(value)
    is ParsedMetadataValue.BoolVal -> kotlinx.serialization.json.JsonPrimitive(value)
    is ParsedMetadataValue.FloatVal -> kotlinx.serialization.json.JsonPrimitive(value)
    is ParsedMetadataValue.ListVal -> kotlinx.serialization.json.JsonArray(values.map { it.toJson() })
    is ParsedMetadataValue.RecordVal -> kotlinx.serialization.json.JsonObject(fields.mapValues { (_, v) -> v.toJson() })
}

/**
 * Values inside a `#{ }` metadata block. Supports JSON-compatible literals:
 * strings, ints, bools, floats, lists, and nested records.
 * Unknown values are treated as raw strings (metadata never errors).
 */
@Serializable
sealed interface ParsedMetadataValue {
    @Serializable
    data class StringVal(val value: String) : ParsedMetadataValue

    @Serializable
    data class IntVal(val value: Long) : ParsedMetadataValue

    @Serializable
    data class BoolVal(val value: Boolean) : ParsedMetadataValue

    @Serializable
    data class FloatVal(val value: Double) : ParsedMetadataValue

    @Serializable
    data class ListVal(val values: List<ParsedMetadataValue>) : ParsedMetadataValue

    @Serializable
    data class RecordVal(val fields: Map<String, ParsedMetadataValue>) : ParsedMetadataValue
}
