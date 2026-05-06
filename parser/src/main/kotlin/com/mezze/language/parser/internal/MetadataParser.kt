package com.mezze.language.parser.internal

import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedMetadata
import com.mezze.language.parsedast.ParsedMetadataValue

/**
 * Parses the content of a `#{...}` metadata comment token.
 *
 * Input: the full comment text including the `#{` prefix and `}` suffix.
 * Example: `#{deprecated: True, since: "0.5.0"}`
 *
 * The content between `#{` and `}` is record syntax: `key: value, key: value`.
 * Values can be: strings, ints, floats, bools, lists, nested records.
 */
internal object MetadataParser {

    /**
     * Parse a metadata comment token text into a ParsedMetadata.
     * Returns null if the text doesn't start with `#{` or is malformed.
     */
    fun parse(text: String, loc: Loc): ParsedMetadata? {
        if (!text.startsWith("#{")) return null

        // Find the closing }
        val closingBrace = text.lastIndexOf('}')
        if (closingBrace < 2) return null

        val content = text.substring(2, closingBrace).trim()
        if (content.isEmpty()) {
            return ParsedMetadata(emptyMap(), loc)
        }

        val fields = parseFields(content) ?: return null
        return ParsedMetadata(fields, loc)
    }

    /**
     * Check if a comment token text is a metadata block (starts with `#{` and contains `}`).
     */
    fun isMetadata(text: String): Boolean = text.startsWith("#{") && text.contains('}')

    private fun parseFields(content: String): Map<String, ParsedMetadataValue>? {
        val fields = mutableMapOf<String, ParsedMetadataValue>()
        val reader = StringReader(content)

        while (!reader.isAtEnd()) {
            reader.skipWhitespace()
            if (reader.isAtEnd()) break

            // Parse key
            val key = reader.readIdentifier()
            if (key == null) {
                // Can't parse a key — treat remaining content as raw string under "_raw" key
                val remaining = reader.readRemaining().trim()
                if (remaining.isNotEmpty()) {
                    fields["_raw"] = ParsedMetadataValue.StringVal(remaining)
                }
                break
            }
            reader.skipWhitespace()

            // Expect colon — if missing, treat value as raw
            if (!reader.consume(':')) {
                // No colon — treat the key itself as a flag (boolean true)
                fields[key] = ParsedMetadataValue.BoolVal(true)
                reader.skipWhitespace()
                reader.consume(',')
                continue
            }
            reader.skipWhitespace()

            // Parse value — always succeeds due to raw fallback
            val value = parseValue(reader) ?: ParsedMetadataValue.StringVal("")
            fields[key] = value

            reader.skipWhitespace()
            // Optional comma
            reader.consume(',')
        }

        return fields
    }

    private fun parseValue(reader: StringReader): ParsedMetadataValue? {
        reader.skipWhitespace()
        val ch = reader.peek() ?: return null

        return when {
            ch == '"' -> parseStringValue(reader)
            ch == '[' -> parseListValue(reader)
            ch == '{' -> parseRecordValue(reader)
            ch == '-' || ch.isDigit() -> parseNumberValue(reader)
            ch.isLetter() -> parseKeywordValue(reader)
            else -> parseRawValue(reader) // fallback: read until delimiter, treat as string
        }
    }

    private fun parseRawValue(reader: StringReader): ParsedMetadataValue {
        val sb = StringBuilder()
        while (!reader.isAtEnd()) {
            val ch = reader.peek()!!
            if (ch == ',' || ch == '}' || ch == ']') break
            sb.append(ch)
            reader.advance()
        }
        return ParsedMetadataValue.StringVal(sb.toString().trim())
    }

    private fun parseStringValue(reader: StringReader): ParsedMetadataValue? {
        if (!reader.consume('"')) return null
        val sb = StringBuilder()
        while (!reader.isAtEnd()) {
            val ch = reader.peek()!!
            if (ch == '"') {
                reader.advance()
                return ParsedMetadataValue.StringVal(sb.toString())
            }
            if (ch == '\\') {
                reader.advance()
                val escaped = reader.peek() ?: return null
                when (escaped) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> { sb.append('\\'); sb.append(escaped) }
                }
                reader.advance()
            } else {
                sb.append(ch)
                reader.advance()
            }
        }
        return null // unterminated string
    }

    private fun parseListValue(reader: StringReader): ParsedMetadataValue? {
        if (!reader.consume('[')) return null
        val values = mutableListOf<ParsedMetadataValue>()
        reader.skipWhitespace()

        if (reader.peek() == ']') {
            reader.advance()
            return ParsedMetadataValue.ListVal(values)
        }

        while (true) {
            reader.skipWhitespace()
            val value = parseValue(reader) ?: return null
            values.add(value)
            reader.skipWhitespace()
            if (reader.peek() == ']') {
                reader.advance()
                return ParsedMetadataValue.ListVal(values)
            }
            if (!reader.consume(',')) return null
        }
    }

    private fun parseRecordValue(reader: StringReader): ParsedMetadataValue? {
        if (!reader.consume('{')) return null
        reader.skipWhitespace()

        if (reader.peek() == '}') {
            reader.advance()
            return ParsedMetadataValue.RecordVal(emptyMap())
        }

        val fields = mutableMapOf<String, ParsedMetadataValue>()
        while (true) {
            reader.skipWhitespace()
            val key = reader.readIdentifier() ?: return null
            reader.skipWhitespace()
            if (!reader.consume(':')) return null
            reader.skipWhitespace()
            val value = parseValue(reader) ?: return null
            fields[key] = value
            reader.skipWhitespace()
            if (reader.peek() == '}') {
                reader.advance()
                return ParsedMetadataValue.RecordVal(fields)
            }
            if (!reader.consume(',')) return null
        }
    }

    private fun parseNumberValue(reader: StringReader): ParsedMetadataValue? {
        val sb = StringBuilder()
        if (reader.peek() == '-') {
            sb.append('-')
            reader.advance()
        }
        var isFloat = false
        while (!reader.isAtEnd()) {
            val ch = reader.peek()!!
            if (ch.isDigit()) {
                sb.append(ch)
                reader.advance()
            } else if (ch == '.' && !isFloat) {
                isFloat = true
                sb.append(ch)
                reader.advance()
            } else {
                break
            }
        }
        if (sb.isEmpty() || sb.toString() == "-") return null
        return if (isFloat) {
            ParsedMetadataValue.FloatVal(sb.toString().toDoubleOrNull() ?: return null)
        } else {
            ParsedMetadataValue.IntVal(sb.toString().toLongOrNull() ?: return null)
        }
    }

    private fun parseKeywordValue(reader: StringReader): ParsedMetadataValue? {
        val word = reader.readIdentifier() ?: return null
        return when (word) {
            "True" -> ParsedMetadataValue.BoolVal(true)
            "False" -> ParsedMetadataValue.BoolVal(false)
            else -> ParsedMetadataValue.StringVal(word) // treat bare words as strings
        }
    }

    /**
     * Simple string reader for parsing metadata content.
     */
    private class StringReader(private val input: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= input.length
        fun peek(): Char? = if (pos < input.length) input[pos] else null
        fun advance() { pos++ }

        fun consume(expected: Char): Boolean {
            if (pos < input.length && input[pos] == expected) {
                pos++
                return true
            }
            return false
        }

        fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun readIdentifier(): String? {
            val start = pos
            if (pos >= input.length || !(input[pos].isLetter() || input[pos] == '_')) return null
            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) pos++
            return if (pos > start) input.substring(start, pos) else null
        }

        fun readRemaining(): String {
            val start = pos
            pos = input.length
            return input.substring(start)
        }
    }
}
