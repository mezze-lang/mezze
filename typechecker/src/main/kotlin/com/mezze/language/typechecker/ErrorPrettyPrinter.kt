package com.mezze.language.typechecker

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.BuiltinAbilities

/**
 * Enhanced error pretty printer for spread syntax and other errors
 * Now includes source code context for better error localization
 */
object ErrorPrettyPrinter {
    /**
     * Pretty print an error with optional source code context
     */
    fun prettyPrint(error: Exception, sourceCode: String? = null, file: String? = null): String {
        return when (error) {
            is SpreadSyntaxError -> prettyPrintSpreadError(error, sourceCode, file)
            is ModuleError -> prettyPrintModuleError(error)
            is TypeCheckError -> prettyPrintTypeCheckError(error, sourceCode, file)
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
    
    private fun prettyPrintTypeCheckError(error: TypeCheckError, sourceCode: String? = null, file: String? = null): String {
        val sb = StringBuilder()

        sb.appendLine("❌ Type Error")
        sb.appendLine("━".repeat(50))

        // Add location info if available
        val location = error.sourceLocation
        if (location != null) {
            val fileInfo = file ?: location.file ?: "<input>"
            sb.appendLine("   --> $fileInfo:${location.line}:${location.column}")
        }

        // Add code snippet if source is available
        if (sourceCode != null && location != null) {
            sb.append(formatCodeSnippet(sourceCode, location.line, location.column, location.length ?: 1))
        }

        // Add error message
        sb.appendLine()
        sb.appendLine("Error: ${extractErrorMessage(error.message ?: "Unknown type error")}")

        // Add helpful suggestions based on error type
        val message = error.message ?: ""

        when (error.errorType) {
            ErrorType.TYPE_MISMATCH -> {
                sb.appendLine()
                sb.appendLine("💡 Type Mismatch Help:")
                sb.appendLine("   - Check that function arguments match expected types")
                sb.appendLine("   - Verify that return values match declared types")
                sb.appendLine("   - Consider adding explicit type annotations")
            }

            ErrorType.UNDEFINED_VARIABLE -> {
                sb.appendLine()
                sb.appendLine("💡 Undefined Variable Help:")
                sb.appendLine("   - Check the variable name for typos")
                sb.appendLine("   - Ensure the variable is declared before use")
                sb.appendLine("   - For imported functions, use 'use module_name'")
            }

            ErrorType.MISSING_FIELD -> {
                sb.appendLine()
                sb.appendLine("💡 Missing Field Help:")
                sb.appendLine("   - Check the field name for typos")
                sb.appendLine("   - Verify the record type has this field")
            }

            ErrorType.CONSTRAINT_VIOLATION -> {
                // Check if this is an effect handling error vs ability constraint
                when {
                    message.contains("None of the provided handlers handle effects") -> {
                        sb.appendLine()
                        sb.appendLine("💡 Effect Handling Help:")
                        sb.appendLine("   - The handler doesn't implement the effect that the expression uses")
                        // Extract the effect names from the error message for a more specific suggestion
                        val usesMatch = Regex("Expression uses: ([^.]+)\\.").find(message)
                        val handlesMatch = Regex("handles ([^)]+)\\)").find(message)
                        if (usesMatch != null && handlesMatch != null) {
                            val uses = usesMatch.groupValues[1]
                            val handles = handlesMatch.groupValues[1]
                            sb.appendLine("   - Use a handler that implements $uses instead of $handles")
                            sb.appendLine("   - Or check if $uses should be handled at a different level")
                        } else {
                            sb.appendLine("   - Add a handler that implements the missing effect")
                            sb.appendLine("   - Or restructure to handle effects at the correct level")
                        }
                    }
                    message.contains("doesn't use this effect") -> {
                        sb.appendLine()
                        sb.appendLine("💡 Unnecessary Handler:")
                        sb.appendLine("   - This handler isn't needed because the expression doesn't use its effect")
                        sb.appendLine("   - Remove the handler or check if you meant to use a different effect")
                    }
                    message.contains("handler") && message.contains("effect") -> {
                        sb.appendLine()
                        sb.appendLine("💡 Effect Handling Help:")
                        sb.appendLine("   - Ensure each effect used by the expression has a matching handler")
                        sb.appendLine("   - Handlers are matched by the effect they implement, not their name")
                    }
                    else -> {
                        sb.appendLine()
                        sb.appendLine("💡 Constraint Violation Help:")
                        sb.appendLine("   - Built-in types (Int, String, etc.) have common abilities")
                        sb.appendLine("   - Custom types need 'impl' declarations")
                        sb.appendLine("   - Check that your type implements the required ability")
                    }
                }
            }

            ErrorType.INEXHAUSTIVE_MATCH -> {
                sb.appendLine()
                sb.appendLine("💡 Pattern Match Help:")
                sb.appendLine("   - Add cases for all missing patterns")
                sb.appendLine("   - Use '_ ->' for a catch-all pattern")
            }

            else -> {
                // Generic suggestions based on message content
                when {
                    message.contains("Missing ability implementation") -> {
                        sb.appendLine()
                        sb.appendLine("💡 Ability Implementation Help:")
                        sb.appendLine("   - Built-in types automatically implement common abilities")
                        sb.appendLine("   - For custom types, you need to provide implementations")
                    }

                    message.contains("Type mismatch") || message.contains("not compatible") -> {
                        sb.appendLine()
                        sb.appendLine("💡 Type Compatibility Help:")
                        sb.appendLine("   - Ensure both sides of an expression have compatible types")
                        sb.appendLine("   - Built-in types: ${BuiltinTypes.allBuiltinTypeNames.take(5).joinToString(", ")}...")
                    }
                }
            }
        }

        return sb.toString().trim()
    }

    /**
     * Extract the core error message (strip location prefix if present)
     */
    private fun extractErrorMessage(message: String): String {
        // Strip "Type Error at line X, column Y: " prefix if present
        val prefix = Regex("^Type Error at line \\d+, column \\d+: ")
        return message.replace(prefix, "")
    }

    /**
     * Format a code snippet with line numbers and error pointer
     */
    private fun formatCodeSnippet(sourceCode: String, errorLine: Int, errorColumn: Int, errorLength: Int = 1, contextLines: Int = 2): String {
        val lines = sourceCode.lines()

        // Validate line number
        if (errorLine < 1 || errorLine > lines.size) {
            return ""
        }

        val sb = StringBuilder()

        // Calculate range of lines to show
        val startLine = maxOf(1, errorLine - contextLines)
        val endLine = minOf(lines.size, errorLine + contextLines)

        // Calculate the width needed for line numbers
        val lineNumberWidth = endLine.toString().length

        // Gutter line: needs to align with content lines which have " $lineNumStr | "
        // That's: 1 space + lineNumberWidth chars + " |" = lineNumberWidth + 3 chars before the |
        // We want spaces before |, so: lineNumberWidth + 2 spaces, then |
        val gutterLine = " ".repeat(lineNumberWidth + 2) + "|"

        sb.appendLine(gutterLine)

        // Show lines with context
        for (lineNum in startLine..endLine) {
            val lineContent = lines[lineNum - 1]
            val lineNumStr = lineNum.toString().padStart(lineNumberWidth)

            if (lineNum == errorLine) {
                // Error line - show with marker
                sb.appendLine(" $lineNumStr | $lineContent")

                // Show error pointer
                val padding = " ".repeat(lineNumberWidth + 3) // account for " " + lineNum + " | "
                val pointer = " ".repeat(maxOf(0, errorColumn - 1)) + "^".repeat(maxOf(1, errorLength))
                sb.appendLine("$padding$pointer")
            } else {
                // Context line
                sb.appendLine(" $lineNumStr | $lineContent")
            }
        }

        sb.appendLine(gutterLine)

        return sb.toString()
    }

    private fun isBuiltinModuleName(moduleName: String): Boolean {
        return when (moduleName) {
            "abilities", "operators", "primitives" -> true
            "option", "result" -> true
            "int", "float", "string", "bool", "nat" -> true
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
            "bool" -> listOf("Bool")
            "nat" -> listOf("Nat")
            "abilities" -> BuiltinAbilities.ALL.toList()
            "operators" -> listOf("+", "-", "*", "/", "%", "==", "!=", "<", ">", "<=", ">=")
            "primitives" -> BuiltinTypes.primitiveTypeNames.toList()
            else -> emptyList()
        }
    }
    
    private fun prettyPrintSpreadError(error: SpreadSyntaxError, sourceCode: String? = null, file: String? = null): String {
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