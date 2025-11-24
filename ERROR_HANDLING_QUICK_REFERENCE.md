# Funk Error Handling - Quick Reference Guide

## Error Type Hierarchy

```
Exception
├── ParseException
│   └── message: String, position: Int
│
├── TypeCheckError
│   ├── sourceLocation: SourceLocation?
│   ├── expression: Expression?
│   ├── errorType: ErrorType (enum with 14 values)
│   └── context: Map<String, Any>
│
├── SpreadSyntaxError (sealed)
│   ├── FieldAdditionNotAllowed(fieldName, availableFields)
│   ├── NonRecordSpread(actualType)
│   ├── NestedFieldNotFound(outerField, innerField, availableFields)
│   ├── NestedFieldInNonRecord(outerField, innerField, availableFields)
│   └── MultipleSpreadsNotAllowed
│
├── ModuleError (sealed)
│   ├── ModuleNotFound(modulePath, searchPaths)
│   ├── CircularDependency(cycle)
│   ├── ParseError(modulePath, error)
│   ├── InternalModuleAccess(fromModule, toModule)
│   ├── ExportNotFound(modulePath, exportName)
│   └── DeprecatedCoreModule(modulePath, suggestion)
│
├── ConstraintError variants
│   ├── QualifiedCallConstraintError
│   ├── ConstraintConflictError
│   ├── UnknownModuleError
│   └── UnknownFunctionError
```

## Source Location Data Structure

```kotlin
data class SourceLocation(
    val line: Int,        // 1-indexed
    val column: Int,      // 1-indexed
    val file: String? = null
)
```

## ErrorType Enumeration

```
GENERAL
TYPE_MISMATCH
UNDEFINED_VARIABLE
MISSING_FIELD
INEXHAUSTIVE_MATCH
ARITY_MISMATCH
UNKNOWN_CONSTRUCTOR
MISSING_HANDLER
CIRCULAR_REFERENCE
CONSTRAINT_VIOLATION
MISSING_ABILITY_IMPL
INVALID_PATTERN
DUPLICATE_TYPE
DUPLICATE_CONSTRUCTOR
```

## Creating Common Errors

### Type Mismatch
```kotlin
TypeCheckError.typeMismatch(
    expected = FunkType.IntType,
    actual = FunkType.StringType,
    expression = someExpr
)
```

### Undefined Variable
```kotlin
TypeCheckError.undefinedVariable(
    name = "x",
    expression = someExpr
)
```

### Missing Field
```kotlin
TypeCheckError.missingField(
    fieldName = "age",
    recordType = FunkType.RecordType(mapOf("name" to FunkType.StringType)),
    expression = someExpr
)
```

### Inexhaustive Match
```kotlin
TypeCheckError.inexhaustiveMatch(
    missingPatterns = listOf("None", "Some"),
    expression = someExpr
)
```

### Arity Mismatch
```kotlin
TypeCheckError.arityMismatch(
    expected = 2,
    actual = 3,
    expression = someExpr
)
```

### Unknown Constructor
```kotlin
TypeCheckError.unknownConstructor(
    constructor = "Bad",
    variantType = FunkType.VariantType(mapOf("Good" to null)),
    expression = someExpr
)
```

### Missing Handler
```kotlin
TypeCheckError.missingHandler(
    effectName = "Log",
    expression = someExpr
)
```

## Error Display Flow

```
Exception
    ↓
CLI catches (Main.kt)
    ↓
ErrorPrettyPrinter.prettyPrint()
    ↓
Type-specific formatting (e.g., printTypeError, prettyPrintModuleError)
    ↓
console output with emojis, line breaks, suggestions
    ↓
exit(1)
```

## Key Files

| File | Purpose |
|------|---------|
| `ast/src/main/kotlin/com/funk/language/ast/FunkAST.kt` | SourceLocation definition, Expression.sourceLocation property |
| `parser/src/main/kotlin/com/funk/language/parser/Lexer.kt` | Token with position/line/column tracking |
| `parser/src/main/kotlin/com/funk/language/parser/ParserCombinators.kt` | ParseResult, TokenStream |
| `parser/src/main/kotlin/com/funk/language/parser/FunkParser.kt` | ParseException, parser error creation |
| `typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt` | TypeCheckError, ErrorType, factory methods |
| `typechecker/src/main/kotlin/com/funk/language/typechecker/ConstraintErrors.kt` | Constraint error types |
| `typechecker/src/main/kotlin/com/funk/language/typechecker/SpreadSyntaxError.kt` | Spread syntax error types |
| `typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt` | ModuleError types |
| `typechecker/src/main/kotlin/com/funk/language/typechecker/ErrorPrettyPrinter.kt` | Error formatting and display |
| `cli/src/main/kotlin/com/funk/language/cli/Main.kt` | CLI error handling and display |

## ErrorPrettyPrinter Dispatch

```kotlin
ErrorPrettyPrinter.prettyPrint(error: Exception): String {
    when (error) {
        is SpreadSyntaxError -> prettyPrintSpreadError(error)
        is ModuleError -> prettyPrintModuleError(error)
        is TypeCheckError -> prettyPrintTypeCheckError(error)
        else -> error.message ?: "Unknown error"
    }
}
```

## Adding a New Error Type

1. **Create error class** (in appropriate module)
   ```kotlin
   data class NewError(
       val message: String,
       val sourceLocation: SourceLocation? = null,
       val context: Map<String, Any> = emptyMap()
   ) : Exception(message)
   ```

2. **Add factory method** (if applicable)
   ```kotlin
   fun staticCreate(param: Type, expression: Expression?): TypeCheckError {
       return TypeCheckError(
           message = "...",
           expression = expression,
           errorType = ErrorType.NEW_ERROR,
           context = mapOf(...)
       )
   }
   ```

3. **Add ErrorType enum variant** (if TypeCheckError)
   ```kotlin
   enum class ErrorType {
       // ...existing...
       NEW_ERROR,
   }
   ```

4. **Add pretty printer** (in ErrorPrettyPrinter.kt)
   ```kotlin
   is NewError -> {
       val sb = StringBuilder()
       sb.appendLine("❌ Error Title")
       sb.appendLine("━".repeat(50))
       sb.appendLine(error.message)
       // ... context-specific formatting
   }
   ```

5. **Handle in CLI** (if needed)
   ```kotlin
   catch (e: NewError) {
       println(ErrorPrettyPrinter.prettyPrint(e))
       exitProcess(1)
   }
   ```

## Best Practices

1. **Always include SourceLocation** when possible
   - Extract from Expression.sourceLocation
   - Or pass as explicit parameter to error factory

2. **Use ErrorType enum** for categorization
   - Enables error-specific display logic
   - Helps with filtering/reporting

3. **Include context in error** 
   - Store expected vs actual types
   - Store variable/field names
   - Store missing patterns

4. **Provide helpful messages**
   - Explain what went wrong
   - Suggest how to fix it
   - Show available options when applicable

5. **Use descriptive error names**
   - TypeMismatch vs TypeUnification
   - MissingField vs InvalidFieldAccess
   - Helps downstream error handling

## Testing Error Handling

```kotlin
// Create error with location
val error = TypeCheckError.typeMismatch(
    FunkType.IntType,
    FunkType.StringType,
    IntLiteral(42, sourceLocation = SourceLocation(5, 10))
)

// Check error properties
assertEquals(error.errorType, ErrorType.TYPE_MISMATCH)
assertEquals(error.sourceLocation?.line, 5)
assertEquals(error.sourceLocation?.column, 10)

// Format and display
val output = ErrorPrettyPrinter.prettyPrint(error)
assert(output.contains("Type Mismatch"))
```

## Common Patterns

### Propagating Errors Up Call Stack
```kotlin
try {
    val result = someFunction()
} catch (e: TypeCheckError) {
    // Add context if needed
    throw TypeCheckError(
        "${e.message} in parent context",
        sourceLocation = e.sourceLocation,
        errorType = e.errorType
    )
}
```

### Collecting Multiple Errors
```kotlin
val errors = mutableListOf<TypeCheckError>()
for (item in items) {
    try {
        process(item)
    } catch (e: TypeCheckError) {
        errors.add(e)
    }
}
if (errors.isNotEmpty()) {
    throw MultipleErrors(errors) // if supported
}
```

### Conditional Error Creation
```kotlin
if (actualType != expectedType) {
    throw TypeCheckError.typeMismatch(
        expected = expectedType,
        actual = actualType,
        expression = expr
    )
}
```
