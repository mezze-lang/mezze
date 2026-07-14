package com.mezze.language.typechecker.printing

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes

open class TypeVarRenamer {
    private val mapping = mutableMapOf<String, String>()

    open operator fun invoke(rawName: String): String {
        if (!rawName.startsWith("'")) return rawName
        return mapping.getOrPut(rawName) {
            val idx = mapping.size
            val letter = 'a' + (idx % 26)
            val suffix = if (idx >= 26) (idx / 26).toString() else ""
            "'${letter}$suffix"
        }
    }

    companion object {
        val identity: TypeVarRenamer = object : TypeVarRenamer() {
            override fun invoke(rawName: String): String = rawName
        }
    }
}

object TypeDiffPrinter {
        fun formatType(type: MezzeType, indent: Int = 0): String = formatType(type, indent, TypeVarRenamer.identity)

        fun formatType(type: MezzeType, indent: Int, rename: TypeVarRenamer): String {
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

        val effectRow = type.effects
        val effects = if (effectRow != null && (effectRow.effects.isNotEmpty() || effectRow.rowVar != null || effectRow.performed)) {
            " ${formatEffectRow(effectRow)}"
        } else ""

        val abilities = if (type.abilityConstraints.isNotEmpty()) {
            " [${type.abilityConstraints.joinToString { it.abilityName }}]"
        } else ""

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
