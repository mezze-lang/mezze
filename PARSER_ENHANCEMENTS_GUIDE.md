# Parser Enhancements: Complete Guide

## Overview

This document explains all parser enhancement priorities, their implementation, and use cases.

---

## Priority 2 (High) - Essential for Tooling

### 2.1 Thread Safety Documentation

#### What It Is
Documentation clarifying that `FunkParser` is **NOT thread-safe** and explaining proper usage patterns.

#### Why It's Important
**Problem**: `FunkParser` has mutable state:
```kotlin
class FunkParser {
    internal var currentSource: String? = null
    internal var currentFile: String? = null
}
```

This state is set during `parse()` and cleared in a `finally` block. If multiple threads share one parser instance, they can corrupt each other's state.

#### Current Situation
- CLI creates fresh parser per call ✅ Safe
- ModuleResolver stores parser as instance variable ⚠️ Could be unsafe in concurrent scenarios

#### Solution
Add comprehensive KDoc to `FunkParser.kt`:

```kotlin
/**
 * Funk language parser using parser combinators.
 *
 * ## Thread Safety
 *
 * **NOT THREAD-SAFE**: This class maintains mutable state during parsing
 * (currentSource, currentFile) which is cleared after each parse() call.
 *
 * ### Correct Usage Patterns:
 *
 * ✅ **GOOD - Fresh instance per thread**:
 * ```kotlin
 * // Thread 1
 * val parser1 = FunkParser()
 * val program1 = parser1.parse(source1, file = "file1.funk")
 *
 * // Thread 2
 * val parser2 = FunkParser()
 * val program2 = parser2.parse(source2, file = "file2.funk")
 * ```
 *
 * ✅ **GOOD - Sequential usage in same thread**:
 * ```kotlin
 * val parser = FunkParser()
 * val program1 = parser.parse(source1, file = "file1.funk")
 * val program2 = parser.parse(source2, file = "file2.funk")  // OK - sequential
 * ```
 *
 * ❌ **BAD - Shared instance across threads**:
 * ```kotlin
 * val sharedParser = FunkParser()  // DON'T DO THIS
 *
 * thread1 { sharedParser.parse(source1) }  // Race condition!
 * thread2 { sharedParser.parse(source2) }  // Race condition!
 * ```
 *
 * ### For Concurrent Parsing
 *
 * Use thread-local storage or create fresh instances:
 * ```kotlin
 * val sources = listOf(source1, source2, source3)
 * val programs = sources.parallelStream()
 *     .map { source -> FunkParser().parse(source) }  // Fresh instance per thread
 *     .toList()
 * ```
 *
 * ## Error Handling
 *
 * Throws ParseException on errors. The exception includes:
 * - Full source location (file, line, column)
 * - Code snippet with error pointer (^)
 * - Helpful error messages with examples
 *
 * Example error output:
 * ```
 * Parse error: Expected '=' after type variable
 *    --> test.funk:10:25
 *    |
 * 10 | effect Reader having 'state =
 *    |                         ^
 *    |
 * ```
 *
 * @see ParseException for error details
 * @see Program for the returned AST structure
 */
class FunkParser {
    // ... rest of implementation
}
```

#### Benefits
- Prevents subtle concurrency bugs
- Clear guidance for tool developers
- Better IDE integration support
- Reduces support burden

---

### 2.2 Parser Metrics/Stats API

#### What It Is
API to retrieve statistics about parsing operations for performance monitoring and tooling.

#### Use Cases

**1. IDE Integration**:
```kotlin
// VS Code extension
val parser = FunkParser()
val program = parser.parse(source, file = "main.funk")
val stats = parser.getLastParseStatistics()

// Show in status bar: "Parsed 1,543 tokens in 45ms"
statusBar.text = "Parsed ${stats.tokensProcessed} tokens in ${stats.parseTimeMs}ms"
```

**2. Performance Monitoring**:
```kotlin
// Language server
fun monitorParsePerformance(file: String, source: String) {
    val parser = FunkParser()
    val start = System.currentTimeMillis()
    val program = parser.parse(source, file = file)
    val stats = parser.getLastParseStatistics()

    if (stats.parseTimeMs > 1000) {
        logger.warn("Slow parse: $file took ${stats.parseTimeMs}ms for ${stats.tokensProcessed} tokens")
    }

    metrics.record("parse.time", stats.parseTimeMs)
    metrics.record("parse.tokens", stats.tokensProcessed)
}
```

**3. Complexity Analysis**:
```kotlin
// Code quality tool
val stats = parser.getLastParseStatistics()

if (stats.maxNestingDepth > 10) {
    warnings.add("File has very deep nesting (${stats.maxNestingDepth}), consider refactoring")
}

if (stats.programItemCount > 100) {
    warnings.add("File has ${stats.programItemCount} items, consider splitting into modules")
}
```

#### Implementation

**Data Structure**:
```kotlin
/**
 * Statistics collected during parsing
 */
data class ParseStatistics(
    /** Total number of tokens processed */
    val tokensProcessed: Int,

    /** Total parsing time in milliseconds */
    val parseTimeMs: Long,

    /** Number of top-level items in program */
    val programItemCount: Int,

    /** Maximum nesting depth encountered */
    val maxNestingDepth: Int,

    /** Number of lines in source */
    val lineCount: Int,

    /** Number of parser errors recovered from (future feature) */
    val recoveredErrors: Int = 0
) {
    /** Tokens per second throughput */
    val tokensPerSecond: Double
        get() = if (parseTimeMs > 0) (tokensProcessed * 1000.0) / parseTimeMs else 0.0

    /** Average nesting depth */
    val avgNestingDepth: Double
        get() = if (programItemCount > 0) maxNestingDepth / programItemCount.toDouble() else 0.0
}
```

**Parser Changes**:
```kotlin
class FunkParser {
    private var lastStats: ParseStatistics? = null
    private var currentNestingDepth = 0
    private var maxNestingDepth = 0

    /**
     * Get statistics from the last parse() call.
     * Returns null if parse() has not been called yet.
     */
    fun getLastParseStatistics(): ParseStatistics? = lastStats

    fun parse(source: String, desugar: Boolean = true, file: String? = null): Program {
        val startTime = System.currentTimeMillis()
        currentSource = source
        currentFile = file

        // Reset nesting tracking
        currentNestingDepth = 0
        maxNestingDepth = 0

        SyntaxMetadata.clear()

        try {
            val lexer = Lexer(source)
            val tokens = lexer.tokenize()
            val stream = TokenStream(tokens)

            val result = programParser(stream)

            when (result) {
                is ParseResult.Success -> {
                    val program = if (desugar) desugarProgram(result.value) else result.value

                    // Collect statistics
                    val parseTimeMs = System.currentTimeMillis() - startTime
                    lastStats = ParseStatistics(
                        tokensProcessed = tokens.size,
                        parseTimeMs = parseTimeMs,
                        programItemCount = program.items.size,
                        maxNestingDepth = maxNestingDepth,
                        lineCount = source.lines().size
                    )

                    return program
                }
                is ParseResult.Failure -> throw ParseException(...)
            }
        } finally {
            currentSource = null
            currentFile = null
        }
    }

    // Helper for tracking nesting depth
    internal fun <T> trackNesting(block: () -> T): T {
        currentNestingDepth++
        maxNestingDepth = maxOf(maxNestingDepth, currentNestingDepth)
        try {
            return block()
        } finally {
            currentNestingDepth--
        }
    }
}
```

**Usage in Expression Parsers**:
```kotlin
// In nested expressions, track depth
internal val FunkParser.doExpression: Parser<Expression> = { stream ->
    trackNesting {
        // Parse do block
        // ...
    }
}
```

#### Benefits
- IDE performance insights
- Identify slow-parsing files
- Monitor parser performance regressions
- Code quality metrics
- Better debugging tools

---

## Priority 3 (Medium) - Enhanced Developer Experience

### 3.1 Structured Error Types

#### What It Is
Replace single `ParseException` with a hierarchy of typed errors for programmatic handling.

#### Current Limitation
```kotlin
catch (e: ParseException) {
    // Can only access generic message string
    // Can't distinguish between different error types
    println(e.message)  // "Expected '}' but got 'EOF'"
}
```

#### Proposed Design

**Error Type Hierarchy**:
```kotlin
/**
 * Structured error types for programmatic error handling
 */
sealed class ParseErrorType {
    /**
     * Expected a specific token but got something else
     * Example: Expected ')' but got 'EOF'
     */
    data class UnexpectedToken(
        val expected: List<String>,  // [")", "]", "}"]
        val actual: TokenType,        // EOF
        val context: String          // "in function call"
    ) : ParseErrorType()

    /**
     * Missing required delimiter or token
     * Example: Missing closing brace in record literal
     */
    data class MissingDelimiter(
        val delimiter: String,       // "}"
        val opened: SourceLocation,  // Where it was opened
        val context: String          // "record literal"
    ) : ParseErrorType()

    /**
     * Invalid literal value
     * Example: "123abc" is not a valid integer
     */
    data class InvalidLiteral(
        val literalType: String,     // "integer"
        val value: String,           // "123abc"
        val reason: String           // "contains non-numeric characters"
    ) : ParseErrorType()

    /**
     * Syntax rule violation
     * Example: Type variable must start with '
     */
    data class SyntaxError(
        val rule: String,            // "type_variable_syntax"
        val description: String,     // "Type variables must start with '"
        val suggestion: String?      // "Try: 'state instead of state"
    ) : ParseErrorType()

    /**
     * Invalid pattern in match expression
     */
    data class InvalidPattern(
        val patternType: String,     // "constructor"
        val issue: String,           // "Constructor must start with uppercase"
        val value: String            // "some"
    ) : ParseErrorType()

    /**
     * Ambiguous syntax that could be parsed multiple ways
     */
    data class AmbiguousSyntax(
        val alternatives: List<String>,  // ["lambda", "record literal"]
        val hint: String                 // "Use '->' for lambda or '=' for record"
    ) : ParseErrorType()

    /**
     * Effect/ability syntax errors
     */
    data class EffectSyntaxError(
        val errorType: String,       // "missing_having_type"
        val effectName: String,      // "Reader"
        val suggestion: String       // "Add: having 'state = Int"
    ) : ParseErrorType()

    /**
     * Generic syntax error (fallback)
     */
    data class GenericError(
        val description: String
    ) : ParseErrorType()
}
```

**Enhanced ParseException**:
```kotlin
class ParseException(
    val errorType: ParseErrorType,       // NEW: Structured type
    val errorMessage: String,             // Keep for backward compatibility
    val position: Int,
    val line: Int = 0,
    val column: Int = 0,
    val sourceCode: String? = null,
    val file: String? = null,
    val hints: List<String> = emptyList()  // NEW: Recovery hints
) : Exception(formatParseError(...)) {

    /**
     * Check if this is a specific error type
     */
    inline fun <reified T : ParseErrorType> isType(): Boolean = errorType is T

    /**
     * Get typed error details if matches type
     */
    inline fun <reified T : ParseErrorType> getTyped(): T? = errorType as? T
}
```

#### Use Cases

**1. IDE Error Categorization**:
```kotlin
// Language server
fun categorizeError(e: ParseException): ErrorSeverity {
    return when (e.errorType) {
        is ParseErrorType.MissingDelimiter -> ErrorSeverity.ERROR
        is ParseErrorType.SyntaxError -> ErrorSeverity.ERROR
        is ParseErrorType.AmbiguousSyntax -> ErrorSeverity.WARNING
        else -> ErrorSeverity.ERROR
    }
}
```

**2. Automated Error Recovery**:
```kotlin
// Auto-fix tool
fun suggestFix(e: ParseException): String? {
    return when (val err = e.errorType) {
        is ParseErrorType.MissingDelimiter -> {
            "Add '${err.delimiter}' at line ${e.line}"
        }
        is ParseErrorType.InvalidPattern -> {
            if (err.issue.contains("uppercase")) {
                "Capitalize '${err.value}' to '${err.value.capitalize()}'"
            } else null
        }
        is ParseErrorType.SyntaxError -> {
            err.suggestion
        }
        else -> null
    }
}
```

**3. Error Analytics**:
```kotlin
// Collect most common errors
fun trackParseErrors(e: ParseException) {
    val category = when (e.errorType) {
        is ParseErrorType.UnexpectedToken -> "unexpected_token"
        is ParseErrorType.MissingDelimiter -> "missing_delimiter"
        is ParseErrorType.InvalidLiteral -> "invalid_literal"
        is ParseErrorType.SyntaxError -> "syntax_error"
        else -> "other"
    }

    analytics.increment("parse_error.$category")

    // Track specific patterns
    if (e.errorType is ParseErrorType.MissingDelimiter) {
        analytics.increment("missing_delimiter.${(e.errorType as ParseErrorType.MissingDelimiter).delimiter}")
    }
}
```

**4. Better Testing**:
```kotlin
@Test
fun `should report missing closing brace with location`() {
    val source = """
        let x = {
            a = 1
        // Missing }
    """.trimIndent()

    val exception = assertThrows<ParseException> {
        FunkParser().parse(source)
    }

    // Can assert on structured type
    assertTrue(exception.isType<ParseErrorType.MissingDelimiter>())
    val error = exception.getTyped<ParseErrorType.MissingDelimiter>()!!

    assertEquals("}", error.delimiter)
    assertEquals("record literal", error.context)
    assertEquals(2, error.opened.line)
}
```

---

### 3.2 Parser Recovery Hints

#### What It Is
Actionable suggestions attached to parse errors to help users fix problems quickly.

#### Current State
```
Parse error: Expected type annotation after '=' in having clause
   --> test.funk:10:25
   |
10 | effect Reader having 'state =
   |                         ^
   |
```

#### Enhanced With Hints
```
Parse error: Expected type annotation after '=' in having clause
   --> test.funk:10:25
   |
10 | effect Reader having 'state =
   |                         ^
   |
💡 Hints:
   • Add a type after '=': effect Reader having 'state = Int where { ... }
   • The type must be a valid type annotation (Int, String, {x: Int}, etc.)

📖 See: https://funk-lang.org/docs/effects#associated-types
```

#### Implementation

**Hint System**:
```kotlin
/**
 * Represents a hint for fixing a parse error
 */
data class ParseHint(
    val message: String,
    val code: String? = null,        // Example code
    val docUrl: String? = null,      // Documentation link
    val severity: HintSeverity = HintSeverity.SUGGESTION
)

enum class HintSeverity {
    INFO,          // General information
    SUGGESTION,    // Suggested fix
    WARNING,       // Possible issue
    TIP            // Best practice
}
```

**Common Hint Patterns**:
```kotlin
object ParseHints {
    fun missingDelimiter(delimiter: String, context: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Add closing '$delimiter' to match the opening delimiter",
            code = when (delimiter) {
                "}" -> "{ field = value }"
                ")" -> "(expression)"
                "]" -> "[element1, element2]"
                else -> null
            }
        ),
        ParseHint(
            message = "Check for unbalanced $delimiter in your $context",
            severity = HintSeverity.TIP
        )
    )

    fun invalidTypeVariable(actual: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Type variables must start with an apostrophe (')",
            code = "Try: '${actual} instead of ${actual}"
        ),
        ParseHint(
            message = "Valid type variables: 'a, 'b, 'state, 'error",
            severity = HintSeverity.INFO
        )
    )

    fun expectedFunctionType(context: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Ability operations must have function types",
            code = "operation_name: {param: Type} -> ReturnType"
        ),
        ParseHint(
            message = "Effects require function signatures in their operations",
            docUrl = "https://funk-lang.org/docs/effects#operations"
        )
    )

    fun missingHavingType(): List<ParseHint> = listOf(
        ParseHint(
            message = "Specify the type for the associated type binding",
            code = "effect Reader having 'state = Int where { ... }"
        ),
        ParseHint(
            message = "Associated types must be bound to concrete types in implementations",
            docUrl = "https://funk-lang.org/docs/effects#associated-types",
            severity = HintSeverity.INFO
        )
    )

    fun ambiguousLambdaRecord(): List<ParseHint> = listOf(
        ParseHint(
            message = "This could be a lambda or record literal",
            code = """
                Lambda:  {x} -> x + 1
                Record:  {x = 1}
            """.trimIndent()
        ),
        ParseHint(
            message = "Use '->' for lambda, '=' for record fields",
            severity = HintSeverity.TIP
        )
    )

    fun deprecatedSyntax(old: String, new: String): List<ParseHint> = listOf(
        ParseHint(
            message = "Deprecated syntax: '$old' is no longer supported",
            code = "Use: $new",
            severity = HintSeverity.WARNING
        )
    )
}
```

**Usage in Parser**:
```kotlin
// In AssociatedTypeParser.kt
when (typeAnn) {
    is ParseResult.Failure -> {
        throw ParseException(
            errorType = ParseErrorType.SyntaxError(
                rule = "associated_type_binding",
                description = "Expected type annotation after '=' in having clause",
                suggestion = "Add a type: having 'state = Int"
            ),
            errorMessage = "Expected type annotation after '=' in having clause",
            position = streamAfterEq.getSourcePosition(),
            line = streamAfterEq.getSourceLine(),
            column = streamAfterEq.getSourceColumn(),
            sourceCode = currentSource,
            file = currentFile,
            hints = ParseHints.missingHavingType()  // ADD HINTS
        )
    }
}
```

**CLI Display**:
```kotlin
// In Main.kt - enhance error display
private fun displayParseError(e: ParseException) {
    println("❌ Parse Error")
    println("━".repeat(50))
    println(e.message)  // Includes code snippet

    // Display hints
    if (e.hints.isNotEmpty()) {
        println()
        println("💡 Hints:")
        e.hints.forEach { hint ->
            val icon = when (hint.severity) {
                HintSeverity.INFO -> "ℹ️"
                HintSeverity.SUGGESTION -> "💡"
                HintSeverity.WARNING -> "⚠️"
                HintSeverity.TIP -> "✨"
            }
            println("   $icon ${hint.message}")
            if (hint.code != null) {
                println("      ${hint.code}")
            }
            if (hint.docUrl != null) {
                println("      📖 See: ${hint.docUrl}")
            }
        }
    }
}
```

#### Benefits
- Faster error resolution for beginners
- Reduced documentation lookups
- Better learning experience
- Actionable error messages
- Links to relevant docs

---

### 3.3 Parse Warnings Support

#### What It Is
Non-fatal warnings for deprecated syntax, style issues, and potential problems.

#### Use Cases

**1. Deprecated Syntax**:
```funk
// Old array syntax (deprecated)
let x = []

// Parser warns: "Empty [] syntax is deprecated, use Array instead"
// Suggestion: let x = Array
```

**2. Style Warnings**:
```funk
// Unnecessary parentheses
let x = (5)

// Parser warns: "Unnecessary parentheses around literal"
// Suggestion: let x = 5
```

**3. Performance Hints**:
```funk
// Large array literal
let x = [1, 2, 3, ..., 10000]  // 10000 elements

// Parser warns: "Large array literal (10000 elements) may impact parse performance"
// Suggestion: Consider loading from file or generating programmatically
```

**4. Potential Issues**:
```funk
// Unused import
use std::collections::array

// No array functions used

// Parser warns: "Imported module 'array' appears unused"
```

#### Implementation

**Warning System**:
```kotlin
/**
 * Represents a non-fatal parser warning
 */
data class ParseWarning(
    val message: String,
    val location: SourceLocation,
    val severity: WarningSeverity,
    val category: WarningCategory,
    val suggestion: String? = null,
    val autoFixable: Boolean = false
)

enum class WarningSeverity {
    INFO,       // Informational
    STYLE,      // Code style suggestion
    WARNING,    // Potential issue
    DEPRECATED  // Deprecated syntax
}

enum class WarningCategory {
    DEPRECATED_SYNTAX,
    STYLE,
    PERFORMANCE,
    UNUSED_CODE,
    BEST_PRACTICE,
    COMPATIBILITY
}
```

**Parser Changes**:
```kotlin
class FunkParser {
    private val warnings = mutableListOf<ParseWarning>()

    /**
     * Get all warnings collected during the last parse
     */
    fun getWarnings(): List<ParseWarning> = warnings.toList()

    /**
     * Add a warning during parsing
     */
    internal fun addWarning(
        message: String,
        location: SourceLocation,
        severity: WarningSeverity,
        category: WarningCategory,
        suggestion: String? = null,
        autoFixable: Boolean = false
    ) {
        warnings.add(ParseWarning(
            message, location, severity, category, suggestion, autoFixable
        ))
    }

    fun parse(source: String, desugar: Boolean = true, file: String? = null): Program {
        warnings.clear()  // Clear previous warnings

        // ... parsing logic

        // Warnings don't stop parsing - they're collected
    }
}
```

**Warning Detection Examples**:
```kotlin
// In ExpressionParsers.kt - detect unnecessary parentheses
internal val FunkParser.primaryExpression: Parser<Expression> = { stream ->
    when (stream.current()?.type) {
        TokenType.LPAREN -> {
            val afterParen = stream.advance()
            val expr = expression(afterParen)

            when (expr) {
                is ParseResult.Success -> {
                    // Check if it's a simple literal
                    if (expr.value is IntLiteral ||
                        expr.value is FloatLiteral ||
                        expr.value is StringLiteral) {

                        addWarning(
                            message = "Unnecessary parentheses around literal",
                            location = SourceLocation(
                                stream.getSourcePosition(),
                                stream.getSourceLine(),
                                stream.getSourceColumn(),
                                currentFile
                            ),
                            severity = WarningSeverity.STYLE,
                            category = WarningCategory.STYLE,
                            suggestion = "Remove parentheses: ${expr.value}",
                            autoFixable = true
                        )
                    }

                    // Continue parsing...
                }
            }
        }
    }
}

// In ArrayLiteral parser - detect large arrays
internal val FunkParser.arrayLiteral: Parser<Expression> = { stream ->
    // ... parse array

    if (elements.size > 1000) {
        addWarning(
            message = "Large array literal (${elements.size} elements) may impact performance",
            location = ...,
            severity = WarningSeverity.WARNING,
            category = WarningCategory.PERFORMANCE,
            suggestion = "Consider generating array programmatically or loading from file"
        )
    }
}

// Detect deprecated empty array syntax
when (stream.current()?.type) {
    TokenType.LBRACKET -> {
        val next = stream.peek(1)
        if (next?.type == TokenType.RBRACKET) {
            addWarning(
                message = "Empty [] syntax is deprecated",
                location = ...,
                severity = WarningSeverity.DEPRECATED,
                category = WarningCategory.DEPRECATED_SYNTAX,
                suggestion = "Use: Array",
                autoFixable = true
            )
        }
    }
}
```

**CLI Display**:
```kotlin
// In Main.kt
private fun displayWarnings(warnings: List<ParseWarning>) {
    if (warnings.isEmpty()) return

    println()
    println("⚠️  Warnings (${warnings.size}):")
    println("━".repeat(50))

    warnings.forEach { warning ->
        val icon = when (warning.severity) {
            WarningSeverity.INFO -> "ℹ️"
            WarningSeverity.STYLE -> "✨"
            WarningSeverity.WARNING -> "⚠️"
            WarningSeverity.DEPRECATED -> "🔄"
        }

        println("$icon ${warning.message}")
        println("   --> ${warning.location.file}:${warning.location.line}:${warning.location.column}")

        if (warning.suggestion != null) {
            println("   💡 Suggestion: ${warning.suggestion}")
        }

        if (warning.autoFixable) {
            println("   🔧 Auto-fixable")
        }
        println()
    }
}

// Usage
val parser = FunkParser()
val program = parser.parse(source, file = "test.funk")
displayWarnings(parser.getWarnings())
```

**Configuration**:
```kotlin
// Allow users to configure warning levels
data class ParserConfig(
    val warnDeprecated: Boolean = true,
    val warnStyle: Boolean = true,
    val warnPerformance: Boolean = true,
    val warnUnused: Boolean = true,
    val maxWarnings: Int = 100
)

class FunkParser(
    private val config: ParserConfig = ParserConfig()
) {
    // Only add warnings based on config
    internal fun addWarning(...) {
        if (warnings.size >= config.maxWarnings) return

        val shouldAdd = when (category) {
            WarningCategory.DEPRECATED_SYNTAX -> config.warnDeprecated
            WarningCategory.STYLE -> config.warnStyle
            WarningCategory.PERFORMANCE -> config.warnPerformance
            WarningCategory.UNUSED_CODE -> config.warnUnused
            else -> true
        }

        if (shouldAdd) {
            warnings.add(...)
        }
    }
}
```

#### Benefits
- Gradual migration from deprecated syntax
- Improved code quality
- Catch potential issues early
- Educational for beginners
- Auto-fix support for tooling

---

## Priority 4 (Low) - Advanced Features

### 4.1 Parser Benchmarking Suite

#### What It Is
Comprehensive benchmarking infrastructure to measure parser performance and detect regressions.

#### Why It Matters
- Ensure parser stays fast as language grows
- Detect performance regressions in CI
- Optimize hot paths
- Compare different parsing strategies

#### Implementation

**Benchmark Structure**:
```kotlin
// parser/src/test/kotlin/benchmarks/ParserBenchmarks.kt
class ParserBenchmarks {

    @Benchmark
    fun parseSmallFile() {
        // < 100 lines
        val source = generateSmallProgram()
        FunkParser().parse(source)
    }

    @Benchmark
    fun parseMediumFile() {
        // 100-1000 lines
        val source = generateMediumProgram()
        FunkParser().parse(source)
    }

    @Benchmark
    fun parseLargeFile() {
        // 1000-10000 lines
        val source = generateLargeProgram()
        FunkParser().parse(source)
    }

    @Benchmark
    fun parseStdlibModule() {
        // Real-world stdlib file
        val source = File("stdlib/std/collections/array.funk").readText()
        FunkParser().parse(source)
    }

    @Benchmark
    fun parseComplexExpressions() {
        // Deeply nested expressions
        val source = """
            let result = f { x =
                g { y =
                    h { z =
                        i { a = 1 }
                    }
                }
            }
        """.trimIndent()
        FunkParser().parse(source)
    }

    @Benchmark
    fun parseManyFunctions() {
        // 100 simple functions
        val source = (1..100).joinToString("\n") { i ->
            "let f$i = {x} -> x + $i"
        }
        FunkParser().parse(source)
    }
}
```

**Benchmark Fixtures**:
```kotlin
object BenchmarkFixtures {
    fun generateSmallProgram(): String = """
        let add = {a, b} -> a + b
        let multiply = {a, b} -> a * b
        let result = add {a = 5, b = multiply {a = 2, b = 3}}
    """.trimIndent()

    fun generateMediumProgram(): String {
        return buildString {
            // Generate 50 type definitions
            repeat(50) { i ->
                appendLine("type Type$i = Variant${i}A | Variant${i}B | Variant${i}C")
            }

            // Generate 50 functions
            repeat(50) { i ->
                appendLine("let func$i = {x} -> match x")
                appendLine("  | Variant${i}A -> 1")
                appendLine("  | Variant${i}B -> 2")
                appendLine("  | Variant${i}C -> 3")
            }
        }
    }

    fun generateLargeProgram(): String {
        // Generate realistic large program
        return File("test_fixtures/large_program.funk").readText()
    }
}
```

**Performance Tracking**:
```kotlin
// Track performance over time
class PerformanceTracker {
    fun recordBenchmark(name: String, tokensPerSecond: Double, parseTimeMs: Long) {
        val results = File("benchmark_results.csv")
        val timestamp = System.currentTimeMillis()
        val commit = getGitCommit()

        results.appendText("$timestamp,$commit,$name,$tokensPerSecond,$parseTimeMs\n")
    }

    fun checkForRegression(name: String, currentTime: Long): Boolean {
        val baseline = getBaselineTime(name)
        val threshold = baseline * 1.2  // 20% regression threshold

        return currentTime > threshold
    }
}
```

**CI Integration**:
```yaml
# .github/workflows/benchmark.yml
name: Parser Benchmarks

on:
  pull_request:
    paths:
      - 'parser/**'
  schedule:
    - cron: '0 0 * * 0'  # Weekly

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Run benchmarks
        run: gradle :parser:benchmark
      - name: Check for regressions
        run: python scripts/check_benchmark_regression.py
      - name: Post results
        uses: benchmark-action/github-action-benchmark@v1
        with:
          tool: 'customBiggerIsBetter'
          output-file-path: benchmark_results.json
```

#### Benefits
- Prevent performance regressions
- Optimize parser hot paths
- Track performance trends
- Compare implementation strategies

---

### 4.2 Incremental Parsing (for IDEs)

#### What It Is
Only re-parse changed sections of code instead of the entire file on every edit.

#### The Problem

**Current**: Full re-parse on every edit
```
User types one character → Parse entire file (1000+ lines) → 100ms delay
```

**Desired**: Incremental re-parse
```
User types one character → Re-parse only affected function → 5ms delay
```

#### Use Case: IDE Integration

```kotlin
// Language server
class FunkLanguageServer {
    private var lastParsedProgram: Program? = null
    private var lastSource: String = ""

    fun handleDocumentChange(change: TextDocumentContentChangeEvent) {
        val newSource = applyChange(lastSource, change)

        // WITHOUT incremental parsing (current):
        val program = FunkParser().parse(newSource)  // Re-parse everything!

        // WITH incremental parsing (future):
        val program = FunkParser().parseIncremental(
            lastParsedProgram!!,
            changes = listOf(change)
        )  // Only re-parse changed region!

        lastParsedProgram = program
        lastSource = newSource
    }
}
```

#### Implementation Strategy

**1. Track Parse Tree Spans**:
```kotlin
// Each AST node knows its source range
data class SourceSpan(
    val start: Int,      // Character offset
    val end: Int,        // Character offset
    val lineStart: Int,
    val lineEnd: Int
)

// All AST nodes get spans
sealed class Expression {
    abstract val span: SourceSpan?
}

data class LetBinding(
    val variableName: String,
    val expression: Expression,
    override val span: SourceSpan
) : ProgramItem()
```

**2. Identify Affected Nodes**:
```kotlin
class IncrementalParser {
    fun findAffectedNodes(
        program: Program,
        change: TextEdit
    ): List<ProgramItem> {
        return program.items.filter { item ->
            item.span?.overlaps(change.range) == true
        }
    }
}
```

**3. Re-parse Only Affected Nodes**:
```kotlin
fun parseIncremental(
    oldProgram: Program,
    changes: List<TextEdit>,
    newSource: String
): Program {
    val affectedItems = changes.flatMap { change ->
        findAffectedNodes(oldProgram, change)
    }.toSet()

    // Keep unchanged items
    val unchangedItems = oldProgram.items.filterNot { it in affectedItems }

    // Re-parse affected regions
    val reparsedItems = affectedItems.map { item ->
        val regionSource = extractRegion(newSource, item.span!!)
        parseItem(regionSource)
    }

    // Merge and sort by position
    return Program((unchangedItems + reparsedItems).sortedBy { it.span?.start })
}
```

**4. Validation & Fallback**:
```kotlin
fun parseIncrementalWithFallback(
    oldProgram: Program,
    changes: List<TextEdit>,
    newSource: String
): Program {
    try {
        val incrementalResult = parseIncremental(oldProgram, changes, newSource)

        // Validate: check for cross-boundary references
        if (hasInvalidReferences(incrementalResult)) {
            // Fallback to full parse
            return FunkParser().parse(newSource)
        }

        return incrementalResult
    } catch (e: Exception) {
        // Fallback to full parse on any error
        return FunkParser().parse(newSource)
    }
}
```

#### Challenges

**1. Cross-Function Dependencies**:
```funk
// Function A
let double = {x} -> x * 2

// Function B uses A
let quadruple = {x} -> double {x = double {x = x}}
```
If `double` signature changes, `quadruple` might need re-checking (type level, not parse level).

**2. Top-Level Order Matters**:
```funk
type Option 'a = Some 'a | None

let x = Some 5  // Uses type defined above
```

**3. Module Imports**:
```funk
use std::collections::array

let x = array.map  // Depends on import
```

#### Benefits (When Implemented)
- **10-100x faster** for large files
- Real-time error checking
- Smooth IDE experience
- Less CPU usage
- Better battery life

#### Current Status
**Not implemented** - requires significant refactoring:
- Add span tracking to all AST nodes
- Implement incremental algorithm
- Extensive testing
- Handle edge cases

**Recommendation**: Implement only when IDE integration becomes a priority.

---

## Summary: Priority Comparison

| Priority | Feature | Effort | Impact | Users Benefited |
|----------|---------|--------|--------|-----------------|
| **P2** | Thread Safety Docs | 1 hour | High | Tool developers |
| **P2** | Parser Metrics API | 4 hours | High | IDEs, monitoring |
| **P3** | Structured Error Types | 1 day | Medium | All developers |
| **P3** | Recovery Hints | 2 days | High | Beginners |
| **P3** | Parse Warnings | 2 days | Medium | All developers |
| **P4** | Benchmark Suite | 3 days | Low | Core team |
| **P4** | Incremental Parsing | 2-3 weeks | Very High | IDE users |

## Recommended Implementation Order

1. **Week 1**: P3 - Structured Error Types + Recovery Hints
2. **Week 2**: P3 - Parse Warnings Support
3. **Week 3**: P2 - Thread Safety Docs + Metrics API
4. **Future**: P4 - Benchmark Suite (when optimizing)
5. **Future**: P4 - Incremental Parsing (when building IDE)

---

**Next Steps**: Ready to implement Priority 3 features now!
