package com.funk.language.typechecker

/**
 * Simplified error pretty printer for the stub typechecker
 */
object ErrorPrettyPrinter {
    
    fun prettyPrint(error: StructuredTypeError): String {
        return "Type error: ${error.kind}"
    }
    
    fun prettyPrintType(type: com.funk.language.ast.FunkType): String {
        return when (type) {
            is com.funk.language.ast.FunkType.IntType -> "Int"
            is com.funk.language.ast.FunkType.FloatType -> "Float"
            is com.funk.language.ast.FunkType.StringType -> "String"
            is com.funk.language.ast.FunkType.BooleanType -> "Boolean"
            is com.funk.language.ast.FunkType.VarType -> type.variable.name
            is com.funk.language.ast.FunkType.RecordType -> {
                val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
                    "$name: ${prettyPrintType(fieldType)}"
                }
                "{ $fields }"
            }
            is com.funk.language.ast.FunkType.VariantType -> {
                val constructors = type.constructors.entries.joinToString(" | ") { (name, dataType) ->
                    if (dataType != null) "$name(${prettyPrintType(dataType)})" else name
                }
                "| $constructors |"
            }
            is com.funk.language.ast.FunkType.FunctionType -> {
                "${prettyPrintType(type.paramType)} -> ${prettyPrintType(type.returnType)}"
            }
            else -> type.toString()
        }
    }
}
