package com.mezze.language.typechecker.printing

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes

/**
 * Maps raw TypeVar names (fresh-counter generated, e.g. `'l1`, `'o297`)
 * onto stable, error-local names (`'a`, `'b`, ...).
 *
 * One renamer per error-message rendering. Calling it on the same raw
 * name returns the same stable name; first-seen names get the next free
 * letter. Names that don't start with `'` (e.g. concrete type names that
 * accidentally went through this path) pass through unchanged.
 *
 * Purpose: snapshot tests can pin error messages without flaking on the
 * global fresh-typevar counter (which depends on cross-test ordering).
 * For human readers, `'a` / `'b` is also easier to read than `'l1`.
 */
open class TypeVarRenamer {
    private val mapping = mutableMapOf<String, String>()

    open operator fun invoke(rawName: String): String {
        if (!rawName.startsWith("'")) return rawName
        return mapping.getOrPut(rawName) {
            val idx = mapping.size
            // 'a..'z, then 'a1..'z1, 'a2..'z2, ... (matches TypeVar.fresh)
            val letter = 'a' + (idx % 26)
            val suffix = if (idx >= 26) (idx / 26).toString() else ""
            "'${letter}$suffix"
        }
    }

    companion object {
        /** Pass-through renamer — leaves names as-is. Used for the default
         *  one-arg [TypeDiffPrinter.formatType] call site that doesn't
         *  share a renamer across a pair of types. */
        val identity: TypeVarRenamer = object : TypeVarRenamer() {
            override fun invoke(rawName: String): String = rawName
        }
    }
}

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
     * Pretty-print a single type.
     *
     * This is the main type formatting function used everywhere.
     */
    fun formatType(type: MezzeType, indent: Int = 0): String = formatType(type, indent, TypeVarRenamer.identity)

    /**
     * Like [formatType] but with a [TypeVarRenamer] that maps the raw
     * fresh-counter names (e.g. `'l1`, `'o297`) onto stable, error-local
     * names (`'a`, `'b`, ...). Use this when formatting a related pair of
     * types in a single message so cross-process counter drift doesn't
     * leak into user-facing output.
     */
    fun formatType(type: MezzeType, indent: Int, rename: TypeVarRenamer): String {
        // Use centralized registry for primitive types
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> rename(type.variable.name)
            is MezzeType.FunctionType -> formatFunctionType(type, indent, rename)
            is MezzeType.RecordType -> formatRecordType(type, indent, rename)
            is MezzeType.VariantType -> formatVariantType(type, indent, rename)
            is MezzeType.TypeApplication -> formatTypeApplication(type, indent, rename)
            is MezzeType.SeqType -> "Seq ${formatType(type.elementType, indent, rename)}"
            is MezzeType.ListType -> "List ${formatType(type.elementType, indent, rename)}"
            is MezzeType.SetType -> "Set ${formatType(type.elementType, indent, rename)}"
            is MezzeType.SortedMapType -> "SortedMap ${formatType(type.keyType, indent, rename)} ${formatType(type.valueType, indent, rename)}"
            is MezzeType.SortedSetType -> "SortedSet ${formatType(type.elementType, indent, rename)}"
            is MezzeType.MapType -> "Map ${formatType(type.keyType, indent, rename)} ${formatType(type.valueType, indent, rename)}"
            is MezzeType.SuspendedType -> "Suspended<${formatType(type.valueType, indent, rename)}>"
            is MezzeType.EffectRow -> formatEffectRow(type)
            else -> type.toString()
        }
    }

    private fun formatFunctionType(type: MezzeType.FunctionType, indent: Int, rename: TypeVarRenamer): String {
        val params = formatType(type.paramType, indent, rename)
        val result = formatType(type.returnType, indent, rename)

        // Format effect row if present (including performed marker).
        // Mezze surface syntax: `{p} -> Ret <Effect1, Effect2>`. The
        // effect row sits AFTER the return type, not between `->` and
        // the return — using the wrong order in error messages confuses
        // users into thinking the type doesn't match what they wrote.
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
        return "$paramFmt -> $result$effects$abilities"
    }

    private fun formatRecordType(type: MezzeType.RecordType, indent: Int, rename: TypeVarRenamer): String {
        if (type.fields.isEmpty() && type.rowVar == null) {
            return "{}"
        }

        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${formatType(fieldType, indent, rename)}"
        }

        val rowVar = type.rowVar
        val row = if (rowVar != null) {
            if (type.fields.isNotEmpty()) ", ...${rename(rowVar.name)}" else "...${rename(rowVar.name)}"
        } else ""

        return "{$fields$row}"
    }

    private fun formatVariantType(type: MezzeType.VariantType, indent: Int, rename: TypeVarRenamer): String {
        if (type.constructors.isEmpty() && type.rowVar == null) {
            return "[]"
        }

        val constructors = type.constructors.entries.joinToString(", ") { (name, consType) ->
            consType?.let { "$name ${formatType(it, indent, rename)}" } ?: name
        }

        val rowVar = type.rowVar
        val row = if (rowVar != null) {
            if (type.constructors.isNotEmpty()) ", ...${rename(rowVar.name)}" else "...${rename(rowVar.name)}"
        } else ""

        return "[$constructors$row]"
    }

    private fun formatTypeApplication(type: MezzeType.TypeApplication, indent: Int, rename: TypeVarRenamer): String {
        if (type.args.isEmpty()) {
            return type.constructor
        }

        val args = type.args.joinToString(" ") { formatType(it, indent, rename) }
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

}
