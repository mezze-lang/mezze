package com.funk.language.ast

/**
 * Pretty printer for Funk types
 */
fun prettyPrintType(type: FunkType): String = when (type) {
    is FunkType.IntType -> "Int"
    is FunkType.FloatType -> "Float"
    is FunkType.StringType -> "String"
    is FunkType.BooleanType -> "Boolean"
    is FunkType.VarType -> type.variable.name
    is FunkType.RecordType -> {
        val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
            "$name: ${prettyPrintType(fieldType)}"
        }
        if (type.isOpen) {
            "{ $fields, ... }"
        } else {
            "{ $fields }"
        }
    }
    is FunkType.VariantType -> {
        val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
            when (dataType) {
                null -> name
                is FunkType.RecordType -> "$name ${prettyPrintType(dataType)}"
                else -> "$name(${prettyPrintType(dataType)})"
            }
        }
        if (type.isOpen) {
            "| $constructors, ... |"
        } else {
            "| $constructors |"
        }
    }
    is FunkType.FunctionType -> {
        "${prettyPrintType(type.paramType)} -> ${prettyPrintType(type.returnType)}"
    }
    else -> type.toString() // Fallback for any other types
}
