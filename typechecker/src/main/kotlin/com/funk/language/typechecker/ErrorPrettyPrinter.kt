package com.funk.language.typechecker

/**
 * Enhanced error pretty printer for spread syntax and other errors
 */
object ErrorPrettyPrinter {
    fun prettyPrint(error: Exception): String {
        return when (error) {
            is SpreadSyntaxError -> prettyPrintSpreadError(error)
            is ModuleError -> error.message ?: "Module error"
            else -> error.message ?: "Unknown error"
        }
    }
    
    private fun prettyPrintSpreadError(error: SpreadSyntaxError): String {
        val sb = StringBuilder()
        
        sb.appendLine("❌ Spread Syntax Error")
        sb.appendLine("━".repeat(50))
        
        when (error) {
            is SpreadSyntaxError.FieldAdditionNotAllowed -> {
                sb.appendLine("Cannot add new field '${error.fieldName}' to record type.")
                sb.appendLine("Only existing fields can be overridden.")
                sb.appendLine()
                sb.appendLine("Available fields: ${error.availableFields.joinToString(", ")}")
                sb.appendLine()
                sb.appendLine("💡 Suggestion:")
                sb.appendLine("   Use only existing field names, or create a new record literal instead.")
            }
            
            is SpreadSyntaxError.NonRecordSpread -> {
                sb.appendLine("Cannot spread non-record value of type ${error.actualType}.")
                sb.appendLine()
                sb.appendLine("💡 Suggestion:")
                sb.appendLine("   Spread syntax (...) can only be used with record values.")
            }
            
            is SpreadSyntaxError.NestedFieldNotFound -> {
                sb.appendLine("Cannot update nested field '${error.outerField}.${error.innerField}'.")
                sb.appendLine("Field '${error.outerField}' does not exist in record type.")
                sb.appendLine()
                sb.appendLine("Available fields: ${error.availableFields.joinToString(", ")}")
            }
            
            is SpreadSyntaxError.NestedFieldInNonRecord -> {
                sb.appendLine("Cannot update nested field '${error.outerField}.${error.innerField}'.")
                sb.appendLine("Field '${error.innerField}' does not exist in nested record type.")
                sb.appendLine()
                sb.appendLine("Available fields: ${error.availableFields.joinToString(", ")}")
            }
        }
        
        return sb.toString().trim()
    }
}