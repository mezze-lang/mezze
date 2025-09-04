package com.funk.language.typechecker

/**
 * Pretty printer for enhanced type errors with rich context
 */
object ErrorPrettyPrinter {
    
    fun prettyPrintError(error: StructuredTypeError): String {
        val errorInfo = error.errorInfo
        return buildString {
            // Header with error type
            appendLine("❌ Type Error")
            
            // Location information
            errorInfo.location?.let { loc ->
                appendLine("📍 Location: ${loc.file}:${loc.line}:${loc.column}")
            }
            
            // Context information
            errorInfo.context?.let { ctx ->
                appendLine("🔍 Context:")
                ctx.file?.let { appendLine("   File: $it") }
                ctx.module?.let { appendLine("   Module: $it") }
                ctx.function?.let { appendLine("   Function: $it") }
                ctx.expressionType?.let { appendLine("   Expression: $it") }
                ctx.variableName?.let { appendLine("   Variable: $it") }
                ctx.expression?.let { appendLine("   Code: $it") }
                
                if (ctx.additionalInfo.isNotEmpty()) {
                    appendLine("   Additional:")
                    ctx.additionalInfo.forEach { (key, value) ->
                        appendLine("     $key: $value")
                    }
                }
            }
            
            // Main error message
            appendLine()
            appendLine("💥 Error: ${errorInfo.message}")
            
            // Detailed error information based on kind
            when (val kind = errorInfo.kind) {
                is TypeErrorKind.UnificationError -> {
                    appendLine()
                    appendLine("Expected: ${prettyPrintSerializableType(kind.expected)}")
                    appendLine("Actual:   ${prettyPrintSerializableType(kind.actual)}")
                }
                
                is TypeErrorKind.UnboundVariable -> {
                    if (kind.availableNames.isNotEmpty()) {
                        appendLine()
                        appendLine("Available variables: ${kind.availableNames.joinToString(", ")}")
                    }
                }
                
                is TypeErrorKind.FieldNotFound -> {
                    appendLine()
                    appendLine("Record type: ${prettyPrintSerializableType(kind.recordType)}")
                    if (kind.availableFields.isNotEmpty()) {
                        appendLine("Available fields: ${kind.availableFields.joinToString(", ")}")
                    }
                }
                
                is TypeErrorKind.ExhaustivenessError -> {
                    appendLine()
                    appendLine("Variant type: ${prettyPrintSerializableType(kind.variantType)}")
                    appendLine("Missing cases: ${kind.missingConstructors.joinToString(", ")}")
                    if (kind.matchedConstructors.isNotEmpty()) {
                        appendLine("Matched cases: ${kind.matchedConstructors.joinToString(", ")}")
                    }
                }
                
                is TypeErrorKind.UnreachablePatternError -> {
                    appendLine()
                    appendLine("Pattern: ${kind.pattern}")
                    appendLine("Reason: ${kind.reason}")
                }
                
                is TypeErrorKind.PartialApplicationError -> {
                    appendLine()
                    appendLine("Function type: ${prettyPrintSerializableType(kind.functionType)}")
                    appendLine("Provided args: ${prettyPrintSerializableType(kind.providedArgs)}")
                    if (kind.missingFields.isNotEmpty()) {
                        appendLine("Missing fields: ${kind.missingFields.joinToString(", ")}")
                    }
                    if (kind.extraFields.isNotEmpty()) {
                        appendLine("Extra fields: ${kind.extraFields.joinToString(", ")}")
                    }
                }
                
                else -> {
                    // Handle other error types generically
                }
            }
            
            // Hint
            errorInfo.hint?.let { hint ->
                appendLine()
                appendLine("💡 Hint: $hint")
            }
            
            // Related locations
            if (errorInfo.relatedLocations.isNotEmpty()) {
                appendLine()
                appendLine("🔗 Related:")
                errorInfo.relatedLocations.forEach { (loc, desc) ->
                    appendLine("   ${loc.file}:${loc.line}:${loc.column} - $desc")
                }
            }
        }
    }
    
    private fun prettyPrintSerializableType(type: SerializableType): String = when (type) {
        is SerializableType.IntType -> "Int"
        is SerializableType.FloatType -> "Float"
        is SerializableType.StringType -> "String"
        is SerializableType.BooleanType -> "Boolean"
        is SerializableType.VarType -> type.name
        is SerializableType.RecordType -> {
            val fields = type.fields.entries.joinToString(", ") { (name, fieldType) ->
                "$name: ${prettyPrintSerializableType(fieldType)}"
            }
            val openMarker = if (type.isOpen) ", ..." else ""
            "{ $fields$openMarker }"
        }
        is SerializableType.VariantType -> {
            val constructors = type.constructors.entries.joinToString(", ") { (name, dataType) ->
                if (dataType != null) "$name(${prettyPrintSerializableType(dataType)})" else name
            }
            val openMarker = if (type.isOpen) ", ..." else ""
            "| $constructors$openMarker |"
        }
        is SerializableType.FunctionType -> {
            val effects = if (type.effects.isNotEmpty()) " [${type.effects.joinToString()}]" else ""
            "${prettyPrintSerializableType(type.paramType)} -> ${prettyPrintSerializableType(type.returnType)}$effects"
        }
        is SerializableType.TypeAlias -> type.name
        is SerializableType.TypeConstructor -> {
            if (type.args.isEmpty()) type.name
            else "${type.name}<${type.args.joinToString(", ") { prettyPrintSerializableType(it) }}>"
        }
    }
    
    /**
     * Simple error message for CLI output
     */
    fun prettyPrintErrorSimple(error: StructuredTypeError): String {
        val errorInfo = error.errorInfo
        return buildString {
            append("❌ ")
            
            // Add context prefix if available
            errorInfo.context?.let { ctx ->
                when {
                    ctx.function != null && ctx.expressionType != null -> 
                        append("[${ctx.function}:${ctx.expressionType}] ")
                    ctx.function != null -> 
                        append("[${ctx.function}] ")
                    ctx.expressionType != null -> 
                        append("[${ctx.expressionType}] ")
                }
            }
            
            append(errorInfo.message)
            
            // Add hint on same line if short
            errorInfo.hint?.let { hint ->
                if (hint.length < 50) {
                    append(" (Hint: $hint)")
                }
            }
        }
    }
}
