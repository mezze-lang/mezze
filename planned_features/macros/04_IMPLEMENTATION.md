# Implementation Guide

This document describes how to implement the macro system in the Mezze compiler.

## Architecture Overview

```
Source Code
    ↓
┌───────────────────────────────┐
│ Parser                        │
│ - Parse !() syntax            │
│ - Parse ``` templates         │
│ - Create MacroCall/Template   │
│   AST nodes                   │
└───────────────┬───────────────┘
                ↓
       Untyped AST with macro nodes
                ↓
┌───────────────────────────────┐
│ Type Checker - Phase 1        │
│ Macro Expansion               │
│ - Find macro implementations  │
│ - Execute expand functions    │
│ - Tag identifiers with        │
│   syntax contexts             │
│ - Replace macro nodes with    │
│   expanded AST                │
└───────────────┬───────────────┘
                ↓
       Expanded AST (no macro nodes)
                ↓
┌───────────────────────────────┐
│ Type Checker - Phase 2        │
│ Normal Type Checking          │
│ - Context-aware name          │
│   resolution (hygiene)        │
│ - Type inference              │
│ - Effect checking             │
└───────────────┬───────────────┘
                ↓
          Typed AST
                ↓
┌───────────────────────────────┐
│ Interpreter                   │
│ - Execute runtime code        │
│ - No macro-related logic      │
└───────────────────────────────┘
```

## 1. AST Changes

### File: `ast/src/main/kotlin/com/mezze/language/ast/MezzeAST.kt`

Add syntax context tracking:

```kotlin
/**
 * Tracks where an identifier originated for automatic hygiene.
 *
 * @property expansionId Unique ID for this macro expansion, or null for user code
 * @property callSiteContext The context at the macro call site (for captured variables)
 */
data class SyntaxContext(
    val expansionId: String? = null,
    val callSiteContext: SyntaxContext? = null
) {
    companion object {
        val USER_CODE = SyntaxContext(expansionId = null)
    }
}
```

Update `Identifier` to track context:

```kotlin
data class Identifier(
    val name: String,
    val context: SyntaxContext = SyntaxContext.USER_CODE,
    override val type: MezzeType? = null,
    override val location: Location
) : Expression {
    // For debugging
    override fun toString(): String {
        return if (context.expansionId != null) {
            "$name#${context.expansionId}"
        } else {
            name
        }
    }
}
```

Add macro-related expression types:

```kotlin
/**
 * Macro invocation: MacroName!(tokens)
 *
 * This node is created by the parser and replaced during macro expansion.
 */
data class MacroCall(
    val macroName: String,
    val tokens: List<Token>,
    val context: SyntaxContext = SyntaxContext.USER_CODE,
    override val type: MezzeType? = null,
    override val location: Location
) : Expression

/**
 * Tagged template: TemplateName```content```
 *
 * This node is created by the parser and replaced during macro expansion.
 */
data class TaggedTemplate(
    val templateName: String,
    val content: String,
    val interpolations: List<Pair<String, Expression>>,
    val context: SyntaxContext = SyntaxContext.USER_CODE,
    override val type: MezzeType? = null,
    override val location: Location
) : Expression
```

Add compile-time types:

```kotlin
/**
 * Token stream type - only valid during macro expansion
 */
data class TokenStreamType(
    override val location: Location
) : MezzeType

/**
 * AST node types - only valid during macro expansion
 */
data class ExpressionType(override val location: Location) : MezzeType
data class StatementType(override val location: Location) : MezzeType
data class PatternType(override val location: Location) : MezzeType
```

## 2. Parser Changes

### File: `parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt`

### Name Validation

Reject `!` in function and effect names:

```kotlin
fun parseFunctionName(): String {
    val name = consume(TokenType.IDENTIFIER)
    if (name.value.contains('!')) {
        throw ParseError(
            "Function names cannot contain '!' (reserved for macro syntax)",
            name.location
        )
    }
    return name.value
}

fun parseEffectName(): String {
    val name = consume(TokenType.IDENTIFIER)
    if (name.value.contains('!')) {
        throw ParseError(
            "Effect names cannot contain '!' (reserved for macro syntax)",
            name.location
        )
    }
    return name.value
}
```

### Parse Macro Calls

Add to `parsePrimaryExpression()`:

```kotlin
private fun parsePrimaryExpression(): Expression {
    // ... existing cases ...

    // Check for Name!(...) syntax
    if (peek().type == TokenType.IDENTIFIER && peekNext().type == TokenType.EXCLAMATION) {
        return parseMacroCallOrPositionalArgs()
    }

    // Check for Name```...``` syntax
    if (peek().type == TokenType.IDENTIFIER && peekNext().type == TokenType.BACKTICK) {
        if (peekAhead(2).type == TokenType.BACKTICK && peekAhead(3).type == TokenType.BACKTICK) {
            return parseTaggedTemplate()
        }
    }

    // ... rest of cases ...
}
```

### Parse Macro Call / Positional Args

```kotlin
private fun parseMacroCallOrPositionalArgs(): Expression {
    val name = consume(TokenType.IDENTIFIER)
    consume(TokenType.EXCLAMATION)
    consume(TokenType.LPAREN)

    // Collect all tokens until matching )
    val tokens = collectTokensUntilBalanced(TokenType.RPAREN)
    consume(TokenType.RPAREN)

    // Return MacroCall node - type checker will determine if it's a macro
    // or positional arg sugar
    return MacroCall(
        macroName = name.value,
        tokens = tokens,
        context = SyntaxContext.USER_CODE,
        location = name.location
    )
}

private fun collectTokensUntilBalanced(closingToken: TokenType): List<Token> {
    val tokens = mutableListOf<Token>()
    var depth = 0

    while (true) {
        val token = peek()

        when (token.type) {
            TokenType.LPAREN, TokenType.LBRACE, TokenType.LBRACKET -> depth++
            TokenType.RPAREN, TokenType.RBRACE, TokenType.RBRACKET -> {
                if (depth == 0 && token.type == closingToken) {
                    return tokens
                }
                depth--
            }
            TokenType.EOF -> throw ParseError("Unexpected end of file", token.location)
            else -> {}
        }

        tokens.add(token)
        advance()
    }
}
```

### Parse Tagged Template

```kotlin
private fun parseTaggedTemplate(): Expression {
    val name = consume(TokenType.IDENTIFIER)

    // Consume triple backticks
    consume(TokenType.BACKTICK)
    consume(TokenType.BACKTICK)
    consume(TokenType.BACKTICK)

    // Parse template content with ${...} interpolations
    val (content, interpolations) = parseTemplateContent()

    // Consume closing triple backticks
    consume(TokenType.BACKTICK)
    consume(TokenType.BACKTICK)
    consume(TokenType.BACKTICK)

    return TaggedTemplate(
        templateName = name.value,
        content = content,
        interpolations = interpolations,
        context = SyntaxContext.USER_CODE,
        location = name.location
    )
}

private fun parseTemplateContent(): Pair<String, List<Pair<String, Expression>>> {
    val content = StringBuilder()
    val interpolations = mutableListOf<Pair<String, Expression>>()

    while (true) {
        val token = peek()

        // Check for closing backticks
        if (token.type == TokenType.BACKTICK &&
            peekNext().type == TokenType.BACKTICK &&
            peekAhead(2).type == TokenType.BACKTICK) {
            break
        }

        // Check for ${...} interpolation
        if (token.type == TokenType.DOLLAR && peekNext().type == TokenType.LBRACE) {
            advance() // $
            advance() // {

            // Parse expression inside ${}
            val expr = parseExpression()

            // Extract variable name for placeholder
            val varName = when (expr) {
                is Identifier -> expr.name
                else -> "expr_${interpolations.size}"
            }

            interpolations.add(varName to expr)
            content.append("\${$varName}")

            consume(TokenType.RBRACE)
        } else {
            content.append(token.value)
            advance()
        }
    }

    return content.toString() to interpolations
}
```

## 3. Type Checker Changes

### File: `typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt`

### Macro Expansion Phase

Add expansion before type checking:

```kotlin
class TypeChecker(
    private val moduleResolver: ModuleResolver,
    private val interpreter: MezzeInterpreter
) {
    private var nextExpansionId = 0

    fun typeCheckProgram(program: Program): TypedProgram {
        // Phase 1: Expand all macros
        val expandedProgram = expandAllMacros(program)

        // Phase 2: Type check the expanded AST
        return typeCheckExpandedProgram(expandedProgram)
    }
}
```

### Expand All Macros

```kotlin
private fun expandAllMacros(program: Program): Program {
    return program.copy(
        statements = program.statements.map { stmt ->
            expandMacrosInStatement(stmt)
        }
    )
}

private fun expandMacrosInStatement(stmt: Statement): Statement {
    return when (stmt) {
        is LetStatement -> stmt.copy(
            value = expandMacrosInExpression(stmt.value)
        )
        is ExpressionStatement -> stmt.copy(
            expression = expandMacrosInExpression(stmt.expression)
        )
        // ... handle other statement types ...
        else -> stmt
    }
}

private fun expandMacrosInExpression(expr: Expression): Expression {
    return when (expr) {
        is MacroCall -> expandMacroCall(expr)
        is TaggedTemplate -> expandTaggedTemplate(expr)
        is FunctionCall -> expr.copy(
            function = expandMacrosInExpression(expr.function),
            arguments = expandMacrosInExpression(expr.arguments)
        )
        // ... recursively handle all expression types ...
        else -> expr
    }
}
```

### Expand Macro Call

```kotlin
private fun expandMacroCall(call: MacroCall): Expression {
    // First, check if it's a function (for positional arg sugar)
    val binding = lookupName(call.macroName)

    if (binding is FunctionBinding) {
        return expandPositionalArgs(call, binding)
    }

    // Otherwise, must be a macro
    val macroImpl = findMacroImplementation(call.macroName)
        ?: throw TypeError(
            "Unknown macro: ${call.macroName}",
            call.location
        )

    // Generate unique expansion ID
    val expansionId = "expansion_${nextExpansionId++}"

    // Execute the expand function at compile-time
    val expandFn = macroImpl.operations["expand"]
        ?: throw TypeError(
            "Macro ${call.macroName} has no 'expand' operation",
            call.location
        )

    // Create TokenStream from tokens
    val tokenStream = TokenStream(call.tokens)

    // Run the expand function via interpreter in compile-time mode
    val result = try {
        interpreter.compileTimeEvaluate(expandFn, tokenStream)
    } catch (e: Exception) {
        throw TypeError(
            "Error during macro expansion: ${e.message}",
            call.location,
            cause = e
        )
    }

    // Result should be an Expression
    if (result !is Expression) {
        throw TypeError(
            "Macro expand function must return Expression, got ${result::class.simpleName}",
            call.location
        )
    }

    // Tag all identifiers with expansion context
    return tagWithContext(result, expansionId, call.context)
}
```

### Expand Positional Args

```kotlin
private fun expandPositionalArgs(call: MacroCall, function: FunctionBinding): Expression {
    // Parse tokens as comma-separated expressions
    val args = parseCommaSeparatedExprs(call.tokens)

    // Match with function parameters
    if (args.size != function.parameters.size) {
        throw TypeError(
            "Function ${call.macroName} expects ${function.parameters.size} arguments, got ${args.size}",
            call.location
        )
    }

    // Build record with named arguments
    val fields = function.parameters.zip(args).map { (param, arg) ->
        RecordFieldEntry(param.name, arg)
    }

    return FunctionCall(
        function = Identifier(call.macroName, context = call.context, location = call.location),
        arguments = RecordLiteral(fields, location = call.location),
        location = call.location
    )
}
```

### Tag With Context

```kotlin
private fun tagWithContext(
    expr: Expression,
    expansionId: String,
    callSiteContext: SyntaxContext
): Expression {
    return when (expr) {
        is Identifier -> {
            // Determine context for this identifier
            val newContext = if (expr.context.expansionId == null) {
                // This identifier came from user code (captured via parse_expr)
                // Give it call-site context
                SyntaxContext(
                    expansionId = null,
                    callSiteContext = callSiteContext
                )
            } else {
                // This identifier was created in macro code
                // Give it expansion context
                SyntaxContext(
                    expansionId = expansionId,
                    callSiteContext = null
                )
            }

            expr.copy(context = newContext)
        }

        is FunctionCall -> expr.copy(
            function = tagWithContext(expr.function, expansionId, callSiteContext),
            arguments = tagWithContext(expr.arguments, expansionId, callSiteContext)
        )

        is RecordLiteral -> expr.copy(
            fields = expr.fields.map { field ->
                field.copy(value = tagWithContext(field.value, expansionId, callSiteContext))
            }
        )

        // ... recursively tag all expression types ...

        else -> expr
    }
}
```

### Context-Aware Name Resolution

```kotlin
private fun resolveIdentifier(id: Identifier): Pair<Binding, MezzeType> {
    val candidates = lookupAllBindings(id.name)

    // Filter by syntax context for hygiene
    val resolved = candidates.firstOrNull { candidate ->
        contextMatches(id.context, candidate.context)
    }

    if (resolved == null) {
        throw TypeError(
            "Unresolved identifier: ${id.name}",
            id.location
        )
    }

    return resolved to resolved.type
}

private fun contextMatches(
    useContext: SyntaxContext,
    defContext: SyntaxContext
): Boolean {
    // Same expansion? Can see it
    if (useContext.expansionId == defContext.expansionId) {
        return true
    }

    // From captured input? Can see call-site scope
    if (useContext.callSiteContext != null) {
        return contextMatches(useContext.callSiteContext, defContext)
    }

    // Otherwise, can't see it
    return false
}
```

## 4. Interpreter Changes

### File: `interpreter/src/main/kotlin/com/mezze/language/interpreter/MezzeInterpreter.kt`

### Compile-Time Execution Mode

```kotlin
class MezzeInterpreter {
    private var isCompileTime = false

    /**
     * Execute a function at compile-time.
     * Used for macro expansion.
     */
    fun compileTimeEvaluate(fn: Function, arg: Any): Any {
        val previousMode = isCompileTime
        isCompileTime = true
        try {
            return evaluate(FunctionCall(fn, arg))
        } finally {
            isCompileTime = previousMode
        }
    }
}
```

### Native AST Builders

Register native functions for AST construction:

```kotlin
private fun registerCompileTimeBuiltins() {
    // Identifiers
    registerNative("__native_ast_identifier") { args ->
        val name = args["name"] as String
        Identifier(
            name = name,
            context = SyntaxContext.USER_CODE, // Will be retagged
            location = syntheticLocation()
        )
    }

    registerNative("__native_ast_qualified") { args ->
        val name = args["name"] as String
        // Parse "module::member" into qualified identifier
        QualifiedIdentifier(name, location = syntheticLocation())
    }

    // Function calls
    registerNative("__native_ast_call") { args ->
        val fn = args["fn"] as Expression
        val arguments = args["args"] as Expression
        FunctionCall(fn, arguments, location = syntheticLocation())
    }

    // Records
    registerNative("__native_ast_record") { args ->
        val fields = args["fields"] as List<Pair<String, Expression>>
        RecordLiteral(
            fields = fields.map { (name, expr) -> RecordFieldEntry(name, expr) },
            location = syntheticLocation()
        )
    }

    // ... more builders ...
}

private fun syntheticLocation() = Location("<macro-generated>", 0, 0)
```

### Native Parser Helpers

```kotlin
private fun registerParserBuiltins() {
    registerNative("__native_parse_expr") { args ->
        val tokens = args["tokens"] as TokenStream
        try {
            val parser = MezzeParser(tokens.tokens)
            parser.parseExpression()
        } catch (e: ParseError) {
            throw RuntimeException("Parse error: ${e.message}", e)
        }
    }

    registerNative("__native_parse_separated") { args ->
        val tokens = args["tokens"] as TokenStream
        val separator = args["separator"] as String

        // Split tokens by separator
        val items = tokens.splitBy(separator)
        items.map { TokenStream(it) }
    }

    // ... more parsers ...
}
```

## 5. Standard Library Structure

Create the following files:

### `stdlib/std/macro/core.mz`

Types, parsers, and AST builders.

### `stdlib/std/macro/pattern_macro.mz`

PatternMacro effect definition.

### `stdlib/std/macro/tagged_template.mz`

TaggedTemplate effect and common handlers.

### `stdlib/std/macro/comptime.mz`

CompileTime effect and Execute handler.

### `stdlib/std/macro/builtins.mz`

List!, Map!, AssertEq!, Dbg! implementations.

## Testing Strategy

1. **Parser tests** - Verify `!()` and ``` parsing
2. **Hygiene tests** - Verify automatic variable renaming
3. **Expansion tests** - Verify macros expand correctly
4. **Type checking tests** - Verify expanded code type checks
5. **Error message tests** - Verify good error messages
6. **Integration tests** - Full macro usage examples

## Error Handling

Provide clear errors at each stage:

**Parse errors:**
```
Error: Invalid macro syntax
  --> example.mz:5:12
   |
 5 | List!(1, 2, 3
   |            ^ Expected closing )
```

**Expansion errors:**
```
Error: Macro expansion failed: Map! expects key => value pairs
  --> example.mz:10:5
   |
10 | Map!("x", 5)
   | ^^^^^^^^^^^^ Invalid syntax for Map!
```

**Hygiene errors (shouldn't happen with automatic hygiene):**
```
Error: Unresolved identifier: tmp#expansion_5
  --> example.mz:15:10
   |
15 | DoTwice!(x)
   | ^^^^^^^^^^^ Generated by macro expansion
   |
   = note: This is a compiler bug - automatic hygiene failed
```

## Performance Considerations

1. **Cache expansions** - Don't re-expand the same macro
2. **Limit recursion** - Prevent infinite macro expansion
3. **Profile compile-time** - Track expensive macro executions
4. **Parallelize** - Expand independent macros in parallel

## Estimated Implementation Effort

- **AST changes**: 200 LOC, 2 hours
- **Parser changes**: 400 LOC, 1 day
- **Type checker changes**: 800 LOC, 3 days
- **Interpreter changes**: 300 LOC, 1 day
- **Standard library**: 600 LOC, 2 days
- **Testing**: 500 LOC, 2 days
- **Documentation**: Already done!

**Total: ~2800 LOC, ~2 weeks**

## Next Steps

1. Implement AST changes
2. Implement parser changes and test
3. Implement type checker macro expansion
4. Implement context-aware name resolution
5. Implement interpreter compile-time mode
6. Write standard library
7. Write comprehensive tests
8. Optimize and profile

This is a significant feature but well-scoped and clearly defined!
