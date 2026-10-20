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
