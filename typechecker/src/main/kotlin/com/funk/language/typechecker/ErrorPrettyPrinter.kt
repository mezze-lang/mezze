package com.funk.language.typechecker

import com.funk.language.ast.*

/**
 * Enhanced error pretty printer with detailed formatting and helpful suggestions
 */
object ErrorPrettyPrinter {
    
    fun prettyPrint(error: Exception): String {
        return when (error) {
            is TypeCheckError -> prettyPrintTypeError(error)
            else -> "❌ Error: ${error.message ?: "Unknown error"}"
        }
    }
    
    private fun prettyPrintTypeError(error: TypeCheckError): String {
        val sb = StringBuilder()
        
        // Header with location
        if (error.sourceLocation != null) {
            val loc = error.sourceLocation!!
            sb.appendLine("❌ Type Error at line ${loc.line}, column ${loc.column}")
        } else {
            sb.appendLine("❌ Type Error")
        }
        
        sb.appendLine("━".repeat(50))
        
        // Specific error formatting based on type
        when (error.errorType) {
            ErrorType.TYPE_MISMATCH -> {
                sb.appendLine("🔍 Type Mismatch")
                val expected = error.context["expected"] as? FunkType
                val actual = error.context["actual"] as? FunkType
                if (expected != null && actual != null) {
                    sb.appendLine("   Expected: ${prettyPrintType(expected)}")
                    sb.appendLine("   Actual:   ${prettyPrintType(actual)}")
                    sb.appendLine()
                    sb.appendLine(generateTypeMismatchSuggestion(expected, actual))
                } else {
                    sb.appendLine("   ${error.message}")
                }
            }
            
            ErrorType.UNDEFINED_VARIABLE -> {
                sb.appendLine("🔍 Undefined Variable")
                val variable = error.context["variable"] as? String
                if (variable != null) {
                    sb.appendLine("   Variable '$variable' is not defined")
                    sb.appendLine()
                    sb.appendLine("💡 Suggestions:")
                    sb.appendLine("   • Check for typos in the variable name")
                    sb.appendLine("   • Make sure the variable is declared before use")
                    sb.appendLine("   • Check if the variable is in the correct scope")
                } else {
                    sb.appendLine("   ${error.message}")
                }
            }
            
            ErrorType.MISSING_FIELD -> {
                sb.appendLine("🔍 Missing Field")
                val field = error.context["field"] as? String
                val type = error.context["type"] as? FunkType
                if (field != null && type != null) {
                    sb.appendLine("   Field '$field' not found in ${prettyPrintType(type)}")
                    sb.appendLine()
                    if (type is FunkType.RecordType) {
                        sb.appendLine("💡 Available fields: ${type.fields.keys.joinToString(", ")}")
                    }
                } else {
                    sb.appendLine("   ${error.message}")
                }
            }
            
            ErrorType.INEXHAUSTIVE_MATCH -> {
                sb.appendLine("🔍 Non-exhaustive Pattern Match")
                @Suppress("UNCHECKED_CAST")
                val missing = error.context["missing"] as? List<String>
                if (missing != null) {
                    sb.appendLine("   Missing patterns: ${missing.joinToString(", ")}")
                    sb.appendLine()
                    sb.appendLine("💡 Add these cases to make the match exhaustive:")
                    missing.forEach { pattern ->
                        sb.appendLine("   $pattern -> ...")
                    }
                } else {
                    sb.appendLine("   ${error.message}")
                }
            }
            
            ErrorType.UNKNOWN_CONSTRUCTOR -> {
                sb.appendLine("🔍 Unknown Constructor")
                val constructor = error.context["constructor"] as? String
                val type = error.context["type"] as? FunkType
                if (constructor != null) {
                    sb.appendLine("   Constructor '$constructor' is not defined")
                    if (type is FunkType.VariantType) {
                        sb.appendLine()
                        sb.appendLine("💡 Available constructors: ${type.constructors.keys.joinToString(", ")}")
                    }
                } else {
                    sb.appendLine("   ${error.message}")
                }
            }
            
            ErrorType.MISSING_HANDLER -> {
                sb.appendLine("🔍 Missing Effect Handler")
                val effect = error.context["effect"] as? String
                if (effect != null) {
                    sb.appendLine("   No handler found for effect '$effect'")
                    sb.appendLine()
                    sb.appendLine("💡 Wrap your code in a 'perform' block with handlers:")
                    sb.appendLine("   perform {")
                    sb.appendLine("     $effect = { /* handler implementation */ }")
                    sb.appendLine("   } do")
                    sb.appendLine("     /* your code here */")
                } else {
                    sb.appendLine("   ${error.message}")
                }
            }
            
            else -> {
                sb.appendLine("🔍 ${error.errorType.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }}")
                sb.appendLine("   ${error.message}")
            }
        }
        
        return sb.toString()
    }
    
    private fun generateTypeMismatchSuggestion(expected: FunkType, actual: FunkType): String {
        return when {
            // Numeric type suggestions
            expected is FunkType.IntType && actual is FunkType.FloatType -> {
                "💡 Suggestion: Use floatToInt {val = ...} to convert Float to Int"
            }
            expected is FunkType.FloatType && actual is FunkType.IntType -> {
                "💡 Suggestion: Use intToFloat {val = ...} to convert Int to Float"
            }
            expected is FunkType.StringType && (actual is FunkType.IntType || actual is FunkType.FloatType) -> {
                val converter = if (actual is FunkType.IntType) "intToString" else "floatToString"
                "💡 Suggestion: Use $converter {val = ...} to convert to String"
            }
            
            // Function type suggestions
            expected is FunkType.FunctionType && actual !is FunkType.FunctionType -> {
                "💡 Suggestion: Expected a function, but got a value. Did you forget to wrap in a lambda?"
            }
            actual is FunkType.FunctionType && expected !is FunkType.FunctionType -> {
                "💡 Suggestion: Expected a value, but got a function. Did you forget to call the function?"
            }
            
            // Record type suggestions
            expected is FunkType.RecordType && actual is FunkType.RecordType -> {
                val missingFields = expected.fields.keys - actual.fields.keys
                val extraFields = actual.fields.keys - expected.fields.keys
                when {
                    missingFields.isNotEmpty() -> "💡 Suggestion: Add missing fields: ${missingFields.joinToString(", ")}"
                    extraFields.isNotEmpty() -> "💡 Suggestion: Remove extra fields: ${extraFields.joinToString(", ")}"
                    else -> "💡 Suggestion: Check field types for mismatches"
                }
            }
            
            // Variant type suggestions
            expected is FunkType.VariantType && actual is FunkType.VariantType -> {
                "💡 Suggestion: These are different variant types. Check constructor names and data types."
            }
            
            else -> "💡 Suggestion: Check the types and ensure they match the expected signature"
        }
    }
    
    fun prettyPrintType(type: FunkType): String {
        return com.funk.language.ast.prettyPrintType(type)
    }
}
