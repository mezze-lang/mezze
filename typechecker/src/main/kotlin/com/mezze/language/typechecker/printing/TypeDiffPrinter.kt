package com.mezze.language.typechecker.printing

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes

/**
 * Pretty printer for type differences.
 *
 * Shows clear visual diffs between expected and actual types,
 * highlighting the specific differences to help users understand
 * type mismatches.
 *
 * Design:
 * - Pretty-print types with proper formatting
 * - Highlight differences between types
 * - Handle all MezzeType variants
 * - Show side-by-side comparison for complex types
 *
 * Usage:
 * ```kotlin
 * val diff = TypeDiffPrinter.formatDiff(expectedType, actualType)
 * println(diff)
 * ```
 */
object TypeDiffPrinter {
    /**
     * Format a type diff showing expected vs actual.
     *
     * Returns a formatted string with both types and highlighting of the difference.
     */
    fun formatDiff(
        expected: MezzeType,
        actual: MezzeType,
        context: String? = null
    ): String {
        return buildString {
            if (context != null) {
                appendLine("In $context:")
                appendLine()
            }

            val expectedStr = formatType(expected)
            val actualStr = formatType(actual)

            // Simple format: show expected vs actual
            appendLine("Expected: $expectedStr")
            appendLine("Actual  : $actualStr")

            // Try to highlight the difference
            val (highlightExpected, highlightActual) = highlightDifference(expected, actual)
            if (highlightExpected != expectedStr || highlightActual != actualStr) {
                appendLine()
                appendLine("Difference: $highlightExpected vs $highlightActual")
            }
        }.trim()
    }

    /**
     * Pretty-print a single type.
     *
     * This is the main type formatting function used everywhere.
     */
    fun formatType(type: MezzeType, indent: Int = 0): String {
        // Use centralized registry for primitive types
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> type.variable.name
            is MezzeType.FunctionType -> formatFunctionType(type, indent)
            is MezzeType.RecordType -> formatRecordType(type, indent)
            is MezzeType.VariantType -> formatVariantType(type, indent)
            is MezzeType.TypeApplication -> formatTypeApplication(type, indent)
            is MezzeType.SeqType -> "Seq ${formatType(type.elementType, indent)}"
            is MezzeType.ListType -> "List ${formatType(type.elementType, indent)}"
            is MezzeType.SetType -> "Set ${formatType(type.elementType, indent)}"
            is MezzeType.SortedMapType -> "SortedMap ${formatType(type.keyType, indent)} ${formatType(type.valueType, indent)}"
            is MezzeType.SortedSetType -> "SortedSet ${formatType(type.elementType, indent)}"
            is MezzeType.MapType -> "Map ${formatType(type.keyType, indent)} ${formatType(type.valueType, indent)}"
            is MezzeType.SuspendedType -> "Suspended<${formatType(type.valueType, indent)}>"
            is MezzeType.EffectRow -> formatEffectRow(type)
            else -> type.toString()
        }
    }

    /**
     * Show side-by-side comparison for complex types.
     *
     * Useful for large record or variant types.
     */
    fun formatSideBySide(
        expected: MezzeType,
        actual: MezzeType
    ): String {
        val expectedLines = formatTypeMultiline(expected)
        val actualLines = formatTypeMultiline(actual)

        return buildString {
            appendLine("Expected:                 | Actual:")
            appendLine("━".repeat(26) + "┼" + "━".repeat(26))

            val maxLines = maxOf(expectedLines.size, actualLines.size)
            for (i in 0 until maxLines) {
                val expectedLine = expectedLines.getOrNull(i) ?: ""
                val actualLine = actualLines.getOrNull(i) ?: ""

                val paddedExpected = expectedLine.padEnd(25)
                append("$paddedExpected | $actualLine")
                appendLine()
            }
        }.trim()
    }

    // ========================================================================
    // TYPE FORMATTING HELPERS
    // ========================================================================

    private fun formatFunctionType(type: MezzeType.FunctionType, indent: Int): String {
        val params = formatType(type.paramType, indent)
        val result = formatType(type.returnType, indent)

        // Format effect row if present (including performed marker)
        val effectRow = type.effects
        val effects = if (effectRow != null && (effectRow.effects.isNotEmpty() || effectRow.rowVar != null || effectRow.performed)) {
            " ${formatEffectRow(effectRow)}"
        } else ""

        // Format abilities if present
        val abilities = if (type.abilityConstraints.isNotEmpty()) {
            " [${type.abilityConstraints.joinToString { it.abilityName }}]"
        } else ""

        // If the param is a record, its own formatter already emitted braces;
        // don't double-wrap (`{{x: Int}}` → `{x: Int}`).
        val paramFmt = if (type.paramType is MezzeType.RecordType) params else "{$params}"
        return "$paramFmt ->$effects$abilities $result"
    }

    private fun formatRecordType(type: MezzeType.RecordType, indent: Int): String {
        if (type.fields.isEmpty() && type.rowVar == null) {
            return "{}"
        }

        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${formatType(fieldType, indent)}"
        }

        val rowVar = type.rowVar
        val row = if (rowVar != null) {
            if (type.fields.isNotEmpty()) ", ...${rowVar.name}" else "...${rowVar.name}"
        } else ""

        return "{$fields$row}"
    }

    private fun formatVariantType(type: MezzeType.VariantType, indent: Int): String {
        if (type.constructors.isEmpty() && type.rowVar == null) {
            return "[]"
        }

        val constructors = type.constructors.entries.joinToString(", ") { (name, consType) ->
            consType?.let { "$name ${formatType(it, indent)}" } ?: name
        }

        val rowVar = type.rowVar
        val row = if (rowVar != null) {
            if (type.constructors.isNotEmpty()) ", ...${rowVar.name}" else "...${rowVar.name}"
        } else ""

        return "[$constructors$row]"
    }

    private fun formatTypeApplication(type: MezzeType.TypeApplication, indent: Int): String {
        if (type.args.isEmpty()) {
            return type.constructor
        }

        val args = type.args.joinToString(" ") { formatType(it, indent) }
        return "${type.constructor} $args"
    }

    private fun formatEffectRow(type: MezzeType.EffectRow): String {
        val rowVar = type.rowVar
        if (type.effects.isEmpty() && rowVar == null && !type.performed) {
            return "<>"
        }

        val parts = mutableListOf<String>()
        parts.addAll(type.effects.sorted())
        if (rowVar != null) {
            parts.add("...${rowVar.name}")
        }
        if (type.performed) {
            parts.add("*")
        }

        return "<${parts.joinToString(", ")}>"
    }

    /**
     * Format type as multiple lines for side-by-side comparison.
     */
    private fun formatTypeMultiline(type: MezzeType): List<String> {
        return when (type) {
            is MezzeType.RecordType -> {
                if (type.fields.isEmpty()) return listOf("{}")

                val lines = mutableListOf<String>()
                lines.add("{")
                type.fields.entries.forEachIndexed { index, (name, fieldType) ->
                    val comma = if (index < type.fields.size - 1) "," else ""
                    lines.add("  $name: ${formatType(fieldType)}$comma")
                }
                val rowVar = type.rowVar
                if (rowVar != null) {
                    lines.add("  ...${rowVar.name}")
                }
                lines.add("}")
                lines
            }

            is MezzeType.VariantType -> {
                if (type.constructors.isEmpty()) return listOf("[]")

                val lines = mutableListOf<String>()
                lines.add("[")
                type.constructors.entries.forEachIndexed { index, (name, consType) ->
                    val comma = if (index < type.constructors.size - 1) "," else ""
                    val formatted = consType?.let { "$name ${formatType(it)}" } ?: name
                    lines.add("  $formatted$comma")
                }
                val rowVar = type.rowVar
                if (rowVar != null) {
                    lines.add("  ...${rowVar.name}")
                }
                lines.add("]")
                lines
            }

            else -> listOf(formatType(type))
        }
    }

    /**
     * Highlight the specific difference in a type mismatch.
     *
     * Returns a pair of (highlighted expected, highlighted actual).
     */
    private fun highlightDifference(
        expected: MezzeType,
        actual: MezzeType
    ): Pair<String, String> {
        // If types have different top-level constructors, highlight the whole type
        if (expected::class != actual::class) {
            return Pair(formatType(expected), formatType(actual))
        }

        // For matching type constructors, find the specific difference
        return when {
            expected is MezzeType.RecordType && actual is MezzeType.RecordType -> {
                findRecordDifference(expected, actual)
            }

            expected is MezzeType.VariantType && actual is MezzeType.VariantType -> {
                findVariantDifference(expected, actual)
            }

            expected is MezzeType.FunctionType && actual is MezzeType.FunctionType -> {
                findFunctionDifference(expected, actual)
            }

            expected is MezzeType.TypeApplication && actual is MezzeType.TypeApplication -> {
                findTypeApplicationDifference(expected, actual)
            }

            else -> Pair(formatType(expected), formatType(actual))
        }
    }

    private fun findRecordDifference(
        expected: MezzeType.RecordType,
        actual: MezzeType.RecordType
    ): Pair<String, String> {
        // Find fields that differ
        val allFields = expected.fields.keys + actual.fields.keys

        for (field in allFields) {
            val expectedType = expected.fields[field]
            val actualType = actual.fields[field]

            when {
                expectedType == null && actualType != null -> return Pair(
                    "missing field '$field'",
                    "has field '$field: ${formatType(actualType)}'"
                )
                actualType == null && expectedType != null -> return Pair(
                    "has field '$field: ${formatType(expectedType)}'",
                    "missing field '$field'"
                )
                expectedType != null && actualType != null && expectedType != actualType -> return Pair(
                    "field '$field' has type ${formatType(expectedType)}",
                    "field '$field' has type ${formatType(actualType)}"
                )
            }
        }

        // Row variable difference
        if (expected.rowVar != actual.rowVar) {
            return Pair(
                "row variable '${expected.rowVar?.name ?: "none"}'",
                "row variable '${actual.rowVar?.name ?: "none"}'"
            )
        }

        return Pair(formatType(expected), formatType(actual))
    }

    private fun findVariantDifference(
        expected: MezzeType.VariantType,
        actual: MezzeType.VariantType
    ): Pair<String, String> {
        // Find constructors that differ
        val allConstructors = expected.constructors.keys + actual.constructors.keys

        for (constructor in allConstructors) {
            val expectedType = expected.constructors[constructor]
            val actualType = actual.constructors[constructor]

            when {
                expectedType == null && actualType != null -> return Pair(
                    "missing constructor '$constructor'",
                    "has constructor '$constructor'"
                )
                expectedType != null && actualType == null -> return Pair(
                    "has constructor '$constructor'",
                    "missing constructor '$constructor'"
                )
                expectedType != actualType -> {
                    val expStr = expectedType?.let { formatType(it) } ?: "unit"
                    val actStr = actualType?.let { formatType(it) } ?: "unit"
                    return Pair(
                        "constructor '$constructor' has type $expStr",
                        "constructor '$constructor' has type $actStr"
                    )
                }
            }
        }

        return Pair(formatType(expected), formatType(actual))
    }

    private fun findFunctionDifference(
        expected: MezzeType.FunctionType,
        actual: MezzeType.FunctionType
    ): Pair<String, String> {
        // Check parameter type
        if (expected.paramType != actual.paramType) {
            return Pair(
                "parameter type ${formatType(expected.paramType)}",
                "parameter type ${formatType(actual.paramType)}"
            )
        }

        // Check return type
        if (expected.returnType != actual.returnType) {
            return Pair(
                "return type ${formatType(expected.returnType)}",
                "return type ${formatType(actual.returnType)}"
            )
        }

        // Check effects
        if (expected.effects != actual.effects) {
            val expectedEffects = expected.effects?.let { formatEffectRow(it) } ?: "<pure>"
            val actualEffects = actual.effects?.let { formatEffectRow(it) } ?: "<pure>"
            return Pair(
                "effects $expectedEffects",
                "effects $actualEffects"
            )
        }

        return Pair(formatType(expected), formatType(actual))
    }

    private fun findTypeApplicationDifference(
        expected: MezzeType.TypeApplication,
        actual: MezzeType.TypeApplication
    ): Pair<String, String> {
        // Check constructor
        if (expected.constructor != actual.constructor) {
            return Pair(
                "type constructor '${expected.constructor}'",
                "type constructor '${actual.constructor}'"
            )
        }

        // Check number of arguments
        if (expected.args.size != actual.args.size) {
            return Pair(
                "${expected.args.size} type arguments",
                "${actual.args.size} type arguments"
            )
        }

        // Check each argument
        for (i in expected.args.indices) {
            if (expected.args[i] != actual.args[i]) {
                return Pair(
                    "type argument ${i + 1} is ${formatType(expected.args[i])}",
                    "type argument ${i + 1} is ${formatType(actual.args[i])}"
                )
            }
        }

        return Pair(formatType(expected), formatType(actual))
    }
}
