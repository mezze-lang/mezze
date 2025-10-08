package com.funk.language.typechecker

import com.funk.language.ast.BuiltinTypes
import com.funk.language.ast.BuiltinAbilities

/**
 * Enhanced error pretty printer for spread syntax and other errors
 */
object ErrorPrettyPrinter {
    fun prettyPrint(error: Exception): String {
        return when (error) {
            is SpreadSyntaxError -> prettyPrintSpreadError(error)
            is ModuleError -> prettyPrintModuleError(error)
            is TypeCheckError -> prettyPrintTypeCheckError(error)
            else -> error.message ?: "Unknown error"
        }
    }
    
    private fun prettyPrintModuleError(error: ModuleError): String {
        val sb = StringBuilder()
        
        when (error) {
            is ModuleError.ModuleNotFound -> {
                sb.appendLine("❌ Module Not Found")
                sb.appendLine("━".repeat(50))
                sb.appendLine("Cannot find module: ${error.modulePath}")
                sb.appendLine()
                
                // Check if they're trying to import built-in types
                val moduleName = error.modulePath.split("/").last()
                if (isBuiltinModuleName(moduleName)) {
                    sb.appendLine("💡 Built-in Feature Detected:")
                    sb.appendLine("   The '${moduleName}' module is no longer needed!")
                    sb.appendLine("   Types like ${getBuiltinTypesForModule(moduleName).joinToString(", ")} are now built into the language.")
                    sb.appendLine()
                    sb.appendLine("   Simply remove this import and use the types directly:")
                    sb.appendLine("   - Option 'a, Result 'a 'e, List 'a, etc. are always available")
                    sb.appendLine("   - Operators like +, -, ==, < work automatically")
                    sb.appendLine()
                    sb.appendLine("   For utility functions, import from stdlib instead:")
                    sb.appendLine("   import std/core/option  # for map, flatMap, etc.")
                    sb.appendLine("   import std/collections/array  # for array functions")
                } else {
                    sb.appendLine("Searched in: ${error.searchPaths.joinToString(", ")}")
                    sb.appendLine()
                    sb.appendLine("💡 Suggestions:")
                    sb.appendLine("   - Check the module path spelling")
                    sb.appendLine("   - Ensure the module file exists")
                    sb.appendLine("   - For built-in types, no import is needed")
                }
            }
            
            is ModuleError.ExportNotFound -> {
                sb.appendLine("❌ Export Not Found")
                sb.appendLine("━".repeat(50))
                sb.appendLine("Export '${error.exportName}' not found in module '${error.modulePath}'")
                sb.appendLine()
                
                // Check if they're trying to import a built-in type or ability
                if (BuiltinTypes.isBuiltinTypeName(error.exportName)) {
                    sb.appendLine("💡 Built-in Type Detected:")
                    sb.appendLine("   '${error.exportName}' is now a built-in type!")
                    sb.appendLine("   Remove this import - the type is always available.")
                } else if (BuiltinAbilities.isBuiltin(error.exportName)) {
                    sb.appendLine("💡 Built-in Ability Detected:")
                    sb.appendLine("   '${error.exportName}' is now a built-in ability!")
                    sb.appendLine("   Remove this import - the ability is always available.")
                } else {
                    sb.appendLine("💡 Suggestions:")
                    sb.appendLine("   - Check the export name spelling")
                    sb.appendLine("   - Verify the module exports this item")
                    sb.appendLine("   - For built-in features, no import is needed")
                }
            }
            
            is ModuleError.DeprecatedCoreModule -> {
                sb.appendLine("⚠️  Deprecated Core Module")
                sb.appendLine("━".repeat(50))
                sb.appendLine("Module '${error.modulePath}' is deprecated.")
                sb.appendLine()
                sb.appendLine("💡 Migration Guide:")
                sb.appendLine("   ${error.suggestion}")
                sb.appendLine()
                sb.appendLine("   This change improves startup performance and reduces complexity.")
                sb.appendLine("   Built-in types and abilities are always available without imports.")
            }
            
            else -> {
                sb.appendLine("❌ Module Error")
                sb.appendLine("━".repeat(50))
                sb.appendLine(error.message ?: "Unknown module error")
            }
        }
        
        return sb.toString().trim()
    }
    
    private fun prettyPrintTypeCheckError(error: TypeCheckError): String {
        val sb = StringBuilder()
        
        sb.appendLine("❌ Type Error")
        sb.appendLine("━".repeat(50))
        sb.appendLine(error.message ?: "Unknown type error")
        
        // Add helpful suggestions based on error content
        val message = error.message ?: ""
        
        when {
            message.contains("not found") && message.contains("ability") -> {
                sb.appendLine()
                sb.appendLine("💡 Built-in Abilities Available:")
                sb.appendLine("   ${BuiltinAbilities.ALL.joinToString(", ")}")
                sb.appendLine("   These abilities are automatically implemented for built-in types.")
            }
            
            message.contains("Missing ability implementation") -> {
                sb.appendLine()
                sb.appendLine("💡 Ability Implementation Help:")
                sb.appendLine("   - Built-in types automatically implement common abilities")
                sb.appendLine("   - For custom types, you need to provide implementations")
                sb.appendLine("   - Check if you're using the correct type constraints")
            }
            
            message.contains("Type unification failed") -> {
                sb.appendLine()
                sb.appendLine("💡 Type Mismatch Help:")
                sb.appendLine("   - Ensure function arguments match expected types")
                sb.appendLine("   - Built-in types: ${BuiltinTypes.allBuiltinTypeNames.joinToString(", ")}")
                sb.appendLine("   - Use type annotations if inference is unclear")
            }
        }
        
        return sb.toString().trim()
    }
    
    private fun isBuiltinModuleName(moduleName: String): Boolean {
        return when (moduleName) {
            "abilities", "operators", "primitives" -> true
            "option", "result" -> true
            "int", "float", "string", "boolean", "nat" -> true
            else -> false
        }
    }
    
    private fun getBuiltinTypesForModule(moduleName: String): List<String> {
        return when (moduleName) {
            "option" -> listOf("Option", "Some", "None")
            "result" -> listOf("Result", "Ok", "Err")
            "int" -> listOf("Int")
            "float" -> listOf("Float")
            "string" -> listOf("String")
            "boolean" -> listOf("Boolean")
            "nat" -> listOf("Nat")
            "abilities" -> BuiltinAbilities.ALL.toList()
            "operators" -> listOf("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=")
            "primitives" -> BuiltinTypes.primitiveTypeNames.toList()
            else -> emptyList()
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

            is SpreadSyntaxError.MultipleSpreadsNotAllowed -> {
                sb.appendLine("Cannot use multiple spreads in a single record literal.")
                sb.appendLine("Only one spread is allowed per record.")
            }
        }
        
        return sb.toString().trim()
    }
}