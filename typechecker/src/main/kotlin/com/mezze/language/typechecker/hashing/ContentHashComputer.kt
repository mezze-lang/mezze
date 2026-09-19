package com.mezze.language.typechecker.hashing

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash

object ContentHashComputer {

    private val typeStringCache = object : LinkedHashMap<Int, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > 1000
    }
    fun mezzeTypeToString(type: MezzeType): String {
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> type.variable.name
            is MezzeType.FunctionType -> "Function"
            else -> mezzeTypeToStringCached(type)
        }
    }

        private fun mezzeTypeToStringCached(type: MezzeType): String {
        val key = System.identityHashCode(type)
        typeStringCache[key]?.let { return it }

        val result = when (type) {
            is MezzeType.TypeApplication -> {
                val args = type.args.joinToString("_") { mezzeTypeToString(it) }
                if (args.isEmpty()) type.constructor else "${type.constructor}_$args"
            }
            is MezzeType.RecordType -> {
                val fieldNames = type.fields.keys.sorted().joinToString("_")
                if (fieldNames.isEmpty()) "Record" else "Record_$fieldNames"
            }
            is MezzeType.VariantType -> {
                val constructorNames = type.constructors.keys.sorted().joinToString("_")
                if (constructorNames.isEmpty()) "Variant" else "Variant_$constructorNames"
            }
            is MezzeType.SeqType -> "Array_${mezzeTypeToString(type.elementType)}"
            is MezzeType.ListType -> "List_${mezzeTypeToString(type.elementType)}"
            is MezzeType.SetType -> "Set_${mezzeTypeToString(type.elementType)}"
            is MezzeType.MapType -> "Map_${mezzeTypeToString(type.keyType)}_${mezzeTypeToString(type.valueType)}"
            else -> type.toString()
        }

        typeStringCache[key] = result
        return result
    }

    }
