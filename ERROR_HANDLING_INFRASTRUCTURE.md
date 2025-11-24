# Funk Language Compiler - Error Handling Infrastructure Summary

## Overview

The Funk language compiler has a well-structured error handling infrastructure distributed across the parser, typechecker, AST, and CLI modules. The system supports precise error reporting with source location tracking, error categorization, and pretty-printed output for users.

---

## 1. SOURCE LOCATION TRACKING

### Location Data Structure
**File:** `ast/src/main/kotlin/com/funk/language/ast/FunkAST.kt` (lines 829-836)

```kotlin
@Serializable
data class SourceLocation(
    val line: Int,
    val column: Int,
    val file: String? = null
) {
    override fun toString(): String = if (file != null) "$file:$line:$column" else "$line:$column"
}
```

**Key Features:**
- Tracks line number, column number, and optional file path
- All AST expressions have an optional `sourceLocation` property
- Enables precise error reporting with file/line/column information
- Default implementation in Expression interface returns null (must be set by parser/typechecker)

### Token-Level Position Tracking
**File:** `parser/src/main/kotlin/com/funk/language/parser/Lexer.kt` (lines 8-14)

```kotlin
data class Token(
    val type: TokenType,
    val text: String,
    val position: Int,  // Character position in source
    val line: Int,      // Line number
    val column: Int     // Column number
)
```

**Key Features:**
- Lexer tracks position, line, and column for every token
- Position tracking starts at 0 and increments with each character
- Line tracking handles newlines (both `\n` and implicit newline tracking)
- Column tracking resets on newlines and increments otherwise

### TokenStream Position Management
**File:** `parser/src/main/kotlin/com/funk/language/parser/ParserCombinators.kt` (lines 16-27)

```kotlin
data class TokenStream(
    val tokens: List<Token>,
    val position: Int = 0
) {
    fun current(): Token? = tokens.getOrNull(position)
    fun peek(offset: Int = 0): Token? = tokens.getOrNull(position + offset)
    fun advance(count: Int = 1): TokenStream = copy(position = position + count)
    fun isAtEnd(): Boolean = position >= tokens.size
    fun getSourcePosition(): Int = current()?.position ?: tokens.lastOrNull()?.position ?: 0
}
```

**Key Features:**
- Maintains position within token stream
- Can query current/peek tokens without advancing
- Provides source position for error reporting

---

## 2. PARSER ERROR HANDLING

### Parser Error Type
**File:** `parser/src/main/kotlin/com/funk/language/parser/FunkParser.kt` (line 3720)

```kotlin
class ParseException(message: String, position: Int) : Exception(message)
```

**Error Format:** `"Parse error at position $position: $message"`

### ParseResult Type
**File:** `parser/src/main/kotlin/com/funk/language/parser/ParserCombinators.kt` (lines 11-14)

```kotlin
sealed class ParseResult<out T> {
    data class Success<T>(val value: T, val remaining: TokenStream) : ParseResult<T>()
    data class Failure(val message: String, val position: Int) : ParseResult<Nothing>()
}
```

**Key Features:**
- Functional result type for parser combinators
- Success carries parsed value and remaining token stream
- Failure carries error message and position in source
- Enables composable error handling with parser combinators

### Example Parser Error Creation
**File:** `parser/src/main/kotlin/com/funk/language/parser/FunkParser.kt` (lines 162-182)

```kotlin
val patternResult = letBindingPattern(stream1)
when (patternResult) {
    is ParseResult.Success -> {
        val pattern = patternResult.value
        // ...check pattern is irrefutable...
        if (!pattern.isIrrefutable()) {
            return ParseResult.Failure(
                "Let bindings only support irrefutable patterns. " +
                "${pattern.kindDescription()} can fail to match. " +
                "Use 'match' expression for refutable patterns.",
                stream1.getSourcePosition()
            )
        }
    }
    is ParseResult.Failure -> patternResult
}
```

**Parser Error Handling Characteristics:**
- Errors propagate as ParseResult.Failure during parsing
- Thrown as ParseException when final result is Failure
- Includes helpful context about what patterns are allowed
- Source position available from token stream

---

## 3. TYPECHECKER ERROR HANDLING

### TypeCheckError Class
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt` (lines 15-206)

```kotlin
class TypeCheckError : Exception {
    val sourceLocation: SourceLocation?
    val expression: Expression?
    val errorType: ErrorType
    val context: Map<String, Any>
    
    constructor(message: String, errorType: ErrorType = ErrorType.GENERAL)
    constructor(message: String, sourceLocation: SourceLocation?, errorType: ErrorType, context: Map<String, Any>)
    constructor(message: String, expression: Expression?, errorType: ErrorType, context: Map<String, Any>)
}
```

**Key Features:**
- Captures source location for precise error reporting
- Can extract location from Expression's sourceLocation property
- Includes errorType for categorization
- Context map carries additional error information (expected type, actual type, etc.)
- Static factory methods for common error patterns

### Error Type Enumeration
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt` (lines 191-206)

```kotlin
enum class ErrorType {
    GENERAL,
    TYPE_MISMATCH,
    UNDEFINED_VARIABLE,
    MISSING_FIELD,
    INEXHAUSTIVE_MATCH,
    ARITY_MISMATCH,
    UNKNOWN_CONSTRUCTOR,
    MISSING_HANDLER,
    CIRCULAR_REFERENCE,
    CONSTRAINT_VIOLATION,
    MISSING_ABILITY_IMPL,
    INVALID_PATTERN,
    DUPLICATE_TYPE,
    DUPLICATE_CONSTRUCTOR
}
```

### Type Mismatch Error Factory
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt` (lines 61-76)

```kotlin
fun typeMismatch(expected: FunkType, actual: FunkType, expression: Expression?): TypeCheckError {
    val message = "Expected type '${prettyPrintType(expected)}' but got '${prettyPrintType(actual)}'"
    return TypeCheckError(
        message,
        expression,
        ErrorType.TYPE_MISMATCH,
        mapOf("expected" to expected, "actual" to actual)
    )
}
```

**Other Factory Methods:**
- `undefinedVariable(name, expression)` - for undefined variable errors
- `missingField(fieldName, recordType, expression)` - for missing fields
- `inexhaustiveMatch(missingPatterns, expression)` - for non-exhaustive patterns
- `arityMismatch(expected, actual, expression)` - for wrong argument counts
- `unknownConstructor(constructor, variantType, expression)` - for variant constructor errors
- `missingHandler(effectName, expression)` - for missing effect handlers
- Plus more...

### Error Message Formatting
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt` (lines 52-59)

```kotlin
companion object {
    private fun formatMessage(message: String, sourceLocation: SourceLocation?): String {
        return if (sourceLocation != null) {
            "Type Error at line ${sourceLocation.line}, column ${sourceLocation.column}: $message"
        } else {
            "Type Error: $message"
        }
    }
}
```

---

## 4. ADDITIONAL TYPECHECKER ERROR TYPES

### Constraint Errors
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/ConstraintErrors.kt` (lines 14-49)

```kotlin
class QualifiedCallConstraintError(
    message: String,
    val qualifiedName: String,
    val argumentType: FunkType,
    val expectedType: FunkType,
    val sourceLocation: SourceLocation? = null
) : Exception(message)

class ConstraintConflictError(
    message: String,
    val conflictingTypes: List<FunkType>,
    val sourceLocation: SourceLocation? = null
) : Exception(message)

class UnknownModuleError(
    message: String,
    val moduleName: String,
    val sourceLocation: SourceLocation? = null
) : Exception(message)

class UnknownFunctionError(
    message: String,
    val moduleName: String,
    val functionName: String,
    val availableFunctions: List<String> = emptyList(),
    val sourceLocation: SourceLocation? = null
) : Exception(message)
```

### Spread Syntax Errors
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/SpreadSyntaxError.kt` (lines 1-60)

```kotlin
sealed class SpreadSyntaxError : RuntimeException() {
    data class FieldAdditionNotAllowed(
        val fieldName: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError()
    
    data class NonRecordSpread(val actualType: String) : SpreadSyntaxError()
    
    data class NestedFieldNotFound(
        val outerField: String,
        val innerField: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError()
    
    data class NestedFieldInNonRecord(
        val outerField: String,
        val innerField: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError()
    
    object MultipleSpreadsNotAllowed : SpreadSyntaxError()
}
```

### Module Errors
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt` (lines 57-75)

```kotlin
sealed class ModuleError(message: String) : Exception(message) {
    data class ModuleNotFound(val modulePath: String, val searchPaths: List<String>) : ModuleError(...)
    data class CircularDependency(val cycle: List<String>) : ModuleError(...)
    data class ParseError(val modulePath: String, val error: String) : ModuleError(...)
    data class InternalModuleAccess(val fromModule: String, val toModule: String) : ModuleError(...)
    data class ExportNotFound(val modulePath: String, val exportName: String) : ModuleError(...)
    data class DeprecatedCoreModule(val modulePath: String, val suggestion: String) : ModuleError(...)
}
```

---

## 5. ERROR PRETTY PRINTING

### ErrorPrettyPrinter Object
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/ErrorPrettyPrinter.kt` (lines 1-200)

```kotlin
object ErrorPrettyPrinter {
    fun prettyPrint(error: Exception): String {
        return when (error) {
            is SpreadSyntaxError -> prettyPrintSpreadError(error)
            is ModuleError -> prettyPrintModuleError(error)
            is TypeCheckError -> prettyPrintTypeCheckError(error)
            else -> error.message ?: "Unknown error"
        }
    }
}
```

**Key Features:**
- Single entry point for error formatting
- Dispatches to specific pretty printers based on error type
- Provides helpful hints and suggestions for each error type
- Uses emoji and formatting for user-friendly output
- Includes migration guidance for deprecated modules

### Module Error Pretty Printing
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/ErrorPrettyPrinter.kt` (lines 19-96)

```kotlin
private fun prettyPrintModuleError(error: ModuleError): String {
    val sb = StringBuilder()
    
    when (error) {
        is ModuleError.ModuleNotFound -> {
            sb.appendLine("❌ Module Not Found")
            sb.appendLine("━".repeat(50))
            sb.appendLine("Cannot find module: ${error.modulePath}")
            // ... search paths and suggestions
        }
        is ModuleError.ExportNotFound -> {
            sb.appendLine("❌ Export Not Found")
            // ... check for built-in types that moved
        }
        is ModuleError.DeprecatedCoreModule -> {
            sb.appendLine("⚠️  Deprecated Core Module")
            // ... provide migration suggestions
        }
    }
}
```

### Spread Error Pretty Printing
**File:** `typechecker/src/main/kotlin/com/funk/language/typechecker/ErrorPrettyPrinter.kt` (lines 154-199)

```kotlin
private fun prettyPrintSpreadError(error: SpreadSyntaxError): String {
    val sb = StringBuilder()
    sb.appendLine("❌ Spread Syntax Error")
    sb.appendLine("━".repeat(50))
    
    when (error) {
        is SpreadSyntaxError.FieldAdditionNotAllowed -> {
            sb.appendLine("Cannot add new field '${error.fieldName}' to record type.")
            sb.appendLine("Only existing fields can be overridden.")
            sb.appendLine("Available fields: ${error.availableFields.joinToString(", ")}")
        }
        // ... more cases
    }
}
```

---

## 6. CLI ERROR DISPLAY

### Main Error Handling
**File:** `cli/src/main/kotlin/com/funk/language/cli/Main.kt` (lines 44-63)

```kotlin
try {
    when (command) {
        "parse" -> handleParseCommand(remainingArgs)
        "check" -> handleCheckCommand(remainingArgs)
        "exec" -> handleExecCommand(remainingArgs)
        "compile" -> handleCompileCommand(remainingArgs)
    }
} catch (e: TypeCheckError) {
    printTypeError(e)
    kotlin.system.exitProcess(1)
} catch (e: Exception) {
    printGeneralError(e)
    kotlin.system.exitProcess(1)
}
```

### Type Error Display
**File:** `cli/src/main/kotlin/com/funk/language/cli/Main.kt` (lines 852-919)

```kotlin
private fun printTypeError(error: TypeCheckError) {
    println("❌ Type Error")
    println("━".repeat(50))
    
    if (error.sourceLocation != null) {
        val loc = error.sourceLocation!!
        println("📍 Location: line ${loc.line}, column ${loc.column}")
        println()
    }
    
    when (error.errorType) {
        ErrorType.TYPE_MISMATCH -> {
            println("🔍 Type Mismatch")
            val expected = error.context["expected"] as? FunkType
            val actual = error.context["actual"] as? FunkType
            if (expected != null && actual != null) {
                println("   Expected: ${prettyPrintType(expected)}")
                println("   Actual:   ${prettyPrintType(actual)}")
            }
        }
        ErrorType.UNDEFINED_VARIABLE -> {
            println("🔍 Undefined Variable")
            val variable = error.context["variable"] as? String
            if (variable != null) {
                println("   Variable '$variable' is not defined")
                println("💡 Tip: Check for typos or make sure it's declared before use")
            }
        }
        // ... more error types with specific handling
    }
}
```

### Check Command Error Handling
**File:** `cli/src/main/kotlin/com/funk/language/cli/Main.kt` (lines 229-279)

```kotlin
private fun handleCheckCommand(args: List<String>) {
    // ... read source code ...
    try {
        val parser = FunkParser()
        val ast = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val result = if (filePath != null) {
            typeChecker.typeCheck(ast, filePath.parent ?: filePath)
        } else {
            typeChecker.typeCheck(ast)
        }
        val typeAliases = typeChecker.getTypeAliases()
        
        printTypeCheckSuccess(result, typeAliases)
    } catch (e: TypeCheckError) {
        println(ErrorPrettyPrinter.prettyPrint(e))
        kotlin.system.exitProcess(1)
    } catch (e: Exception) {
        println("❌ Error:")
        println(e.message ?: "Unknown error")
        kotlin.system.exitProcess(1)
    }
}
```

---

## 7. INTEGRATION POINTS & FLOW

### Parser to AST
1. **Lexer** tokenizes source with position/line/column tracking
2. **TokenStream** maintains position in token list
3. **FunkParser** uses parser combinators
4. **ParseResult** carries error message and position
5. **ParseException** thrown with position information

### AST Expression Enhancement
1. Expressions include optional `sourceLocation: SourceLocation?` property
2. Parser should populate this from tokens during parsing
3. Typechecker preserves or adds source location information
4. Error reporting uses expression's sourceLocation

### Typechecker Error Generation
1. **TypeChecker** creates **TypeCheckError** instances
2. Provides **ErrorType** enum for categorization
3. Includes **context map** with error-specific data (expected/actual types, etc.)
4. Extracts **SourceLocation** from Expression or explicit parameter
5. Throws **TypeCheckError** which propagates to CLI

### CLI to User
1. CLI catches **TypeCheckError**
2. Uses **ErrorPrettyPrinter** for formatting
3. Displays with emojis, formatting, line breaks
4. Provides helpful suggestions based on error type
5. Exit code 1 on error

---

## 8. CURRENT INFRASTRUCTURE STATUS

### What Exists
✅ **Comprehensive Error Types**
- TypeCheckError with categorization (14+ error types)
- Specialized error classes (SpreadSyntaxError, ModuleError, ConstraintErrors)
- ParseException for parser errors

✅ **Source Location Tracking**
- SourceLocation data class with line/column/file
- Token-level position tracking in Lexer
- TokenStream position management
- Expression sourceLocation property in AST

✅ **Error Pretty Printing**
- ErrorPrettyPrinter with dispatch logic
- Module error-specific formatting with migration hints
- Spread syntax error formatting with available fields
- Type error formatting with error-specific handling
- Emoji and formatting for user-friendly display

✅ **CLI Error Display**
- Type error display with location information
- Error type-specific handling with helpful hints
- General error handling for unexpected exceptions
- Proper exit codes

### What Could Be Enhanced
⚠️ **Parser Error Location**
- ParseException uses position (character index) not line/column
- Should convert position to line/column for better UX
- Could enhance error messages with context (show offending line)

⚠️ **Source Location Population**
- Not all AST nodes have sourceLocation set by parser
- Could systematically populate for all expressions
- Would improve error messages across the board

⚠️ **Error Context**
- Some error types could include more context
- Could show code snippet with caret pointing to error
- Could include "did you mean" suggestions

⚠️ **Constraint Error Handling**
- ConstraintErrors not integrated into ErrorPrettyPrinter
- Could provide more detailed unification failure explanations
- Could show type constraint graphs for complex errors

⚠️ **Parser Error Recovery**
- Currently stops at first error
- Could collect multiple errors and report together
- Would help with batch error reporting

---

## 9. KEY FILES TO MODIFY FOR ENHANCEMENTS

For improving error handling, focus on these files:

1. **`parser/src/main/kotlin/com/funk/language/parser/FunkParser.kt`**
   - Populate sourceLocation on all expressions
   - Convert ParseException position to line/column

2. **`typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`**
   - Add source locations to constraint errors
   - Enhance error context with more details

3. **`typechecker/src/main/kotlin/com/funk/language/typechecker/ErrorPrettyPrinter.kt`**
   - Add handling for ConstraintErrors and UnknownModuleError
   - Implement code snippet display
   - Add "did you mean" suggestions

4. **`cli/src/main/kotlin/com/funk/language/cli/Main.kt`**
   - Enhance error display with code snippets
   - Improve formatting and readability
   - Add verbose mode for additional context

---

## Summary

The Funk compiler has a **solid foundation** for error handling with:
- Well-designed error type hierarchy
- Source location tracking at token and AST levels
- Pretty printing infrastructure with helpful suggestions
- CLI integration with proper error display

The infrastructure is **extensible** and can be enhanced to provide:
- Better error messages with code snippets
- Multi-error reporting capability
- More detailed constraint violation explanations
- Smart suggestions using "did you mean" algorithms

All error handling is **cleanly separated** across modules, making it maintainable and testable.
