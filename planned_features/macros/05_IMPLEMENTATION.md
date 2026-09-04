# Macro System Implementation Guide

This document provides implementation guidance for Mezze's macro system.

## Overview

The macro system consists of:
1. **Parser changes** - New AST nodes for macro calls
2. **Type checker expansion** - Macro execution during type-checking
3. **Hygiene system** - Context-aware name resolution
4. **CompileTime mode** - Interpreter execution at compile-time
5. **Reflection API** - Type introspection functions

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         SOURCE CODE                         │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ↓
┌─────────────────────────────────────────────────────────────┐
│                          PARSER                             │
│  - Recognizes macro keyword                                 │
│  - Creates PositionalCall for !()                          │
│  - Creates TaggedTemplate for ```                          │
│  - Creates CompileTimeExecution for comptime {...}         │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ↓ AST
                         │
┌─────────────────────────────────────────────────────────────┐
│                     TYPE CHECKER                            │
│  Phase 1: Macro Discovery                                   │
│    - Identify macro declarations                            │
│    - Store in macro registry                                │
│                                                              │
│  Phase 2: Macro Expansion                                   │
│    - Traverse AST                                            │
│    - For PositionalCall:                                     │
│      - If function: expand to named args                     │
│      - If pattern macro: execute, replace with result       │
│    - For TaggedTemplate: execute, replace with result      │
│    - For CompileTimeExecution: execute, embed result       │
│                                                              │
│  Phase 3: Type Checking                                     │
│    - Type check expanded AST                                │
│    - Apply hygiene rules                                    │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ↓ Typed AST
                         │
┌─────────────────────────────────────────────────────────────┐
│                      INTERPRETER                            │
│  - Execute typed AST at runtime                             │
│  - Macros already expanded (no runtime overhead)            │
└─────────────────────────────────────────────────────────────┘
```

## 1. Parser Changes

### New AST Nodes

```kotlin
// In AST.kt

sealed class Expression {
    // Existing nodes...

    // Pattern macro or positional function call
    data class PositionalCall(
        val name: String,
        val args: List<Expression>,
        val location: Location
    ) : Expression()

    // Tagged template macro
    data class TaggedTemplate(
        val macroName: String,
        val content: String,
        val interpolations: List<Interpolation>,
        val location: Location
    ) : Expression()

    // CompileTime execution
    data class CompileTimeExecution(
        val eval: Expression,  // Lambda expression
        val location: Location
    ) : Expression()
}

data class Interpolation(
    val key: String,
    val value: Expression,
    val location: Location
)

sealed class Declaration {
    // Existing declarations...

    // Macro declaration
    data class MacroDeclaration(
        val name: String,
        val signature: FunctionType,
        val body: Expression,
        val location: Location
    ) : Declaration()
}
```

### Parser Updates

```kotlin
// In Parser.kt

fun parseDeclaration(): Declaration {
    return when {
        match("let") -> parseLetDeclaration()
        match("type") -> parseTypeDeclaration()
        match("effect") -> parseEffectDeclaration()
        match("macro") -> parseMacroDeclaration()  // NEW
        // ...
    }
}

fun parseMacroDeclaration(): Declaration.MacroDeclaration {
    expect("macro")
    val name = parseIdentifier()
    expect(":")
    val signature = parseType()
    expect("=")
    val body = parseExpression()

    return Declaration.MacroDeclaration(
        name = name,
        signature = signature,
        body = body,
        location = currentLocation()
    )
}

fun parsePrimaryExpression(): Expression {
    return when {
        // ...

        // Check for positional call: name!(args)
        currentToken is Identifier && peek() == Token.Bang -> {
            val name = parseIdentifier()
            expect("!")
            expect("(")
            val args = parseCommaSeparated(::parseExpression)
            expect(")")
            Expression.PositionalCall(name, args, currentLocation())
        }

        // Check for tagged template: name```content```
        currentToken is Identifier && peek() == Token.TripleBacktick -> {
            val name = parseIdentifier()
            expect("```")
            val (content, interpolations) = parseTemplateContent()
            expect("```")
            Expression.TaggedTemplate(name, content, interpolations, currentLocation())
        }

        // Check for comptime
        match("comptime") -> {
            expect("{")
            expect("eval")
            expect("=")
            val eval = parseExpression()
            expect("}")
            Expression.CompileTimeExecution(eval, currentLocation())
        }

        // ...
    }
}

fun parseTemplateContent(): Pair<String, List<Interpolation>> {
    val content = StringBuilder()
    val interpolations = mutableListOf<Interpolation>()

    while (currentToken != Token.TripleBacktick) {
        when {
            match("${") -> {
                // Parse interpolation
                val expr = parseExpression()
                expect("}")

                val key = extractInterpolationKey(expr)
                interpolations.add(Interpolation(key, expr, currentLocation()))
                content.append("\${$key}")
            }
            else -> {
                content.append(currentToken.text)
                advance()
            }
        }
    }

    return Pair(content.toString(), interpolations)
}
```

## 2. Type Checker: Macro Expansion

### Macro Registry

```kotlin
// In TypeChecker.kt

class MacroRegistry {
    private val patternMacros = mutableMapOf<String, PatternMacro>()
    private val templateMacros = mutableMapOf<String, TemplateMacro>()

    fun register(name: String, signature: FunctionType, body: Expression) {
        when {
            isPatternMacroSignature(signature) ->
                patternMacros[name] = PatternMacro(name, signature, body)

            isTemplateMacroSignature(signature) ->
                templateMacros[name] = TemplateMacro(name, signature, body)

            else ->
                error("Invalid macro signature: $signature")
        }
    }

    fun isPatternMacro(name: String) = patternMacros.containsKey(name)
    fun isTemplateMacro(name: String) = templateMacros.containsKey(name)

    fun getPatternMacro(name: String) = patternMacros[name]
    fun getTemplateMacro(name: String) = templateMacros[name]
}

fun isPatternMacroSignature(sig: FunctionType): Boolean {
    // Check: {tokens: TokenStream} -> 'result
    return sig.params.size == 1 &&
           sig.params[0].name == "tokens" &&
           sig.params[0].type == Type.Named("TokenStream")
}

fun isTemplateMacroSignature(sig: FunctionType): Boolean {
    // Check: {content: String, interpolations: ...} -> 'result
    return sig.params.any { it.name == "content" && it.type == Type.String } &&
           sig.params.any { it.name == "interpolations" }
}
```

### Expansion Phase

```kotlin
// In TypeChecker.kt

class MacroExpander(
    private val macroRegistry: MacroRegistry,
    private val interpreter: Interpreter
) {
    fun expand(expr: Expression): Expression {
        return when (expr) {
            is Expression.PositionalCall -> expandPositionalCall(expr)
            is Expression.TaggedTemplate -> expandTaggedTemplate(expr)
            is Expression.CompileTimeExecution -> expandCompileTime(expr)
            else -> expr.mapChildren(::expand)  // Recursively expand
        }
    }

    private fun expandPositionalCall(call: Expression.PositionalCall): Expression {
        val binding = context.lookup(call.name)

        return when {
            // Pattern macro
            macroRegistry.isPatternMacro(call.name) -> {
                val macro = macroRegistry.getPatternMacro(call.name)!!
                val tokens = argsToTokenStream(call.args)
                executeMacro(macro, tokens)
            }

            // Regular function - expand to named args
            binding is FunctionBinding -> {
                validatePositionalArgs(call, binding.signature)
                expandToNamedArgs(call, binding.signature)
            }

            else -> error("Unknown function or macro: ${call.name}")
        }
    }

    private fun expandTaggedTemplate(template: Expression.TaggedTemplate): Expression {
        val macro = macroRegistry.getTemplateMacro(template.macroName)
            ?: error("Unknown template macro: ${template.macroName}")

        executeMacro(
            macro,
            content = template.content,
            interpolations = template.interpolations
        )
    }

    private fun expandCompileTime(comptime: Expression.CompileTimeExecution): Expression {
        // Execute the lambda at compile-time
        val result = interpreter.executeAtCompileTime(comptime.eval)

        // Convert result to AST literal
        return resultToLiteral(result)
    }

    private fun executeMacro(macro: PatternMacro, tokens: TokenStream): Expression {
        // Create macro execution context
        val macroContext = createMacroContext(macro)

        // Execute macro body with tokens argument
        val result = interpreter.executeInContext(
            expr = macro.body,
            context = macroContext,
            args = mapOf("tokens" to tokens)
        )

        // Result should be an Expression AST node
        return result as Expression
    }

    private fun validatePositionalArgs(call: Expression.PositionalCall, sig: FunctionType) {
        // Count only explicitly named parameters (ignore open record `...`)
        val namedParamCount = sig.params.count { !it.isOpenRecord }

        if (call.args.size != namedParamCount) {
            error("""
                !() requires all named arguments
                Expected $namedParamCount arguments (${sig.params.joinToString { it.name }}), got ${call.args.size}

                note: Function signature: $sig
                help: !() maps to named parameters only
                help: For partial application, use named arguments: ${call.name} {${sig.params[0].name} = ...}
            """.trimIndent())
        }
    }

    private fun expandToNamedArgs(call: Expression.PositionalCall, sig: FunctionType): Expression {
        val namedArgs = sig.params.zip(call.args).map { (param, arg) ->
            param.name to arg
        }.toMap()

        return Expression.FunctionCall(
            fn = Expression.Identifier(call.name),
            args = Expression.Record(namedArgs),
            location = call.location
        )
    }
}
```

## 3. Hygiene System

### Context-Aware Name Resolution

```kotlin
// In TypeChecker.kt

data class ExpansionContext(
    val expansionId: Int,
    val macroName: String
)

class HygienicNameResolver {
    private var nextExpansionId = 0
    private val currentContext = mutableListOf<ExpansionContext>()

    fun enterMacroExpansion(macroName: String): ExpansionContext {
        val ctx = ExpansionContext(nextExpansionId++, macroName)
        currentContext.add(ctx)
        return ctx
    }

    fun exitMacroExpansion() {
        currentContext.removeLast()
    }

    fun resolveIdentifier(name: String, location: Location): Binding? {
        // Try to resolve in current expansion context first
        for (ctx in currentContext.reversed()) {
            val binding = lookupInContext(name, ctx)
            if (binding != null) return binding
        }

        // Fall back to user code context
        return lookupInUserContext(name)
    }

    fun createHygienicName(name: String): String {
        val ctx = currentContext.lastOrNull()
        return if (ctx != null) {
            "${name}_\$${ctx.expansionId}"
        } else {
            name
        }
    }
}

// Usage in macro execution:
fun executeMacro(macro: PatternMacro, tokens: TokenStream): Expression {
    val ctx = hygienicResolver.enterMacroExpansion(macro.name)

    try {
        // Execute macro
        val result = interpreter.executeInContext(
            expr = macro.body,
            context = createMacroContext(macro),
            args = mapOf("tokens" to tokens)
        )

        // Tag all identifiers in result with expansion ID
        return tagIdentifiers(result, ctx.expansionId)
    } finally {
        hygienicResolver.exitMacroExpansion()
    }
}

fun tagIdentifiers(expr: Expression, expansionId: Int): Expression {
    return expr.transform { node ->
        when (node) {
            is Expression.Identifier ->
                node.copy(expansionId = expansionId)
            else -> node
        }
    }
}
```

## 4. Interpreter: CompileTime Mode

### Compile-Time Execution

```kotlin
// In Interpreter.kt

class Interpreter {
    private var compileTimeMode = false

    fun executeAtCompileTime(expr: Expression): Any {
        val previousMode = compileTimeMode
        compileTimeMode = true

        try {
            return evaluate(expr)
        } finally {
            compileTimeMode = previousMode
        }
    }

    override fun evaluate(expr: Expression): Any {
        // Allow file I/O in compile-time mode
        if (compileTimeMode && expr is Expression.EffectCall) {
            when (expr.effectName) {
                "FileSystem" -> return evaluateFileSystemEffect(expr)
                else -> error("Effects not allowed in compile-time mode (except FileSystem)")
            }
        }

        // Normal evaluation
        return when (expr) {
            // ...
        }
    }

    private fun evaluateFileSystemEffect(call: Expression.EffectCall): Any {
        return when (call.operation) {
            "read_file" -> {
                val path = call.args["path"] as String
                File(path).readText()
            }
            "read_binary" -> {
                val path = call.args["path"] as String
                File(path).readBytes()
            }
            else -> error("Operation ${call.operation} not allowed in compile-time mode")
        }
    }
}
```

## 5. Reflection API

### Type Information

```kotlin
// In std/macro/reflection.mz (implemented in Kotlin)

// Built-in function
fun type_info(for_type: Type): TypeInfo {
    return when (for_type) {
        is Type.Record -> TypeInfo.RecordType(
            fields = for_type.fields.map { (name, type) ->
                FieldInfo(name, type)
            }
        )

        is Type.Variant -> TypeInfo.VariantType(
            constructors = for_type.constructors.map { (name, payload) ->
                ConstructorInfo(name, payload)
            }
        )

        is Type.Function -> TypeInfo.FunctionType(
            params = for_type.params,
            return = for_type.returnType
        )

        is Type.Named -> TypeInfo.PrimitiveType(
            name = for_type.name
        )

        else -> error("Cannot get type info for $for_type")
    }
}

// Built-in function
fun emit_impl(
    trait: String,
    for_type: String,
    methods: List<MethodInfo>
) {
    // Generate trait implementation
    val impl = createTraitImplementation(trait, for_type, methods)

    // Add to type checker context
    typeChecker.addImplementation(impl)
}
```

## 6. Standard Library Primitives

### Parser Primitives

```kotlin
// In std/macro/primitives.mz (implemented in Kotlin)

fun parse_expr(tokens: TokenStream): Result<Expression, String> {
    return try {
        val parser = Parser(tokens)
        Result.Ok(parser.parseExpression())
    } catch (e: ParseError) {
        Result.Err(e.message)
    }
}

fun parse_separated(
    tokens: TokenStream,
    separator: String
): Result<List<TokenStream>, String> {
    val result = mutableListOf<TokenStream>()
    var current = mutableListOf<Token>()

    for (token in tokens) {
        if (token.text == separator) {
            result.add(TokenStream(current))
            current = mutableListOf()
        } else {
            current.add(token)
        }
    }

    if (current.isNotEmpty()) {
        result.add(TokenStream(current))
    }

    return Result.Ok(result)
}
```

### AST Builders

```kotlin
// In std/macro/primitives.mz (implemented in Kotlin)

fun identifier(name: String): Expression {
    return Expression.Identifier(name)
}

fun qualified(name: String): Expression {
    val parts = name.split("::")
    return Expression.QualifiedName(parts)
}

fun call(fn: Expression, args: Expression): Expression {
    return Expression.FunctionCall(fn, args)
}

fun record(fields: Map<String, Expression>): Expression {
    return Expression.Record(fields)
}

fun string_lit(value: String): Expression {
    return Expression.StringLiteral(value)
}

fun int_lit(value: Int): Expression {
    return Expression.IntLiteral(value)
}

fun compile_error(msg: String): Nothing {
    throw CompileError(msg)
}
```

## 7. Error Handling

### Error Messages

```kotlin
// In TypeChecker.kt

class MacroError(
    val message: String,
    val originalCall: Expression,
    val expandedCode: Expression?,
    val location: Location
) : Exception()

fun reportMacroError(error: MacroError) {
    println("""
        Error: ${error.message}
          --> ${error.location.file}:${error.location.line}:${error.location.column}
         |
        ${error.location.line} | ${error.originalCall.sourceText}
         | ${" ".repeat(error.location.column)}^ here
         |
        ${if (error.expandedCode != null) """
        Expanded to:
          ${error.expandedCode.prettyPrint()}
        """ else ""}
    """.trimIndent())
}
```

## 8. Testing Strategy

### Unit Tests

```kotlin
class MacroSystemTest {
    @Test
    fun `pattern macro expands correctly`() {
        val source = """
            macro list: {tokens: TokenStream} -> List 'a = {tokens} ->
                parse_separated {tokens, separator = ","} |> build_list {@}

            let x = list!(1, 2, 3)
        """.trimIndent()

        val result = compileAndExpand(source)

        assertEquals(
            expected = "list::append {xs = list::append {xs = list::empty, x = 1}, x = 2}",
            actual = result.prettyPrint()
        )
    }

    @Test
    fun `tagged template validates syntax`() {
        val source = """
            macro sql: {content: String, interpolations: ...} -> SqlQuery = ...

            let query = sql```SELCT * FROM users```
        """.trimIndent()

        assertThrows<CompileError> {
            compileAndExpand(source)
        }
    }

    @Test
    fun `hygiene prevents variable capture`() {
        val source = """
            macro with_temp = {tokens} ->
                let tmp = "tmp"
                do_expr {
                    statements = [let_stmt {name = tmp, value = parse_expr {tokens}}],
                    result = identifier {name = tmp}
                }

            let tmp = 5
            let result = with_temp!(10)
        """.trimIndent()

        val result = evaluate(source)

        // result should be 10, not 5 (no capture!)
        assertEquals(10, result)
    }
}
```

### Integration Tests

```kotlin
class MacroIntegrationTest {
    @Test
    fun `derive macro generates Eq implementation`() {
        val source = """
            type Point = {x: Int, y: Int}

            comptime {eval = {} ->
                derive_eq {for_type = Point}
            }

            let p1 = {x = 1, y = 2}
            let p2 = {x = 1, y = 2}
            let result = p1 == p2
        """.trimIndent()

        val result = evaluate(source)
        assertTrue(result as Boolean)
    }
}
```

## 9. Performance Considerations

### Caching

```kotlin
class MacroCache {
    private val expansionCache = mutableMapOf<String, Expression>()

    fun getCached(call: Expression): Expression? {
        val key = call.structuralHash()
        return expansionCache[key]
    }

    fun cache(call: Expression, result: Expression) {
        val key = call.structuralHash()
        expansionCache[key] = result
    }
}
```

### Lazy Expansion

- Only expand macros when type-checking requires it
- Cache expansion results for identical macro calls
- Parallelize independent macro expansions

## 10. Implementation Checklist

### Phase 1: Parser (Week 1-2)
- [ ] Add `macro` keyword to lexer
- [ ] Implement `PositionalCall` AST node
- [ ] Implement `TaggedTemplate` AST node
- [ ] Implement `CompileTimeExecution` AST node
- [ ] Implement `MacroDeclaration` AST node
- [ ] Parse template content with interpolations
- [ ] Write parser tests

### Phase 2: Type Checker (Week 3-5)
- [ ] Implement `MacroRegistry`
- [ ] Implement macro discovery phase
- [ ] Implement `MacroExpander`
- [ ] Implement positional call expansion
- [ ] Implement tagged template expansion
- [ ] Implement comptime expansion
- [ ] Write expansion tests

### Phase 3: Hygiene (Week 6-7)
- [ ] Implement `HygienicNameResolver`
- [ ] Tag identifiers with expansion IDs
- [ ] Context-aware name resolution
- [ ] Write hygiene tests

### Phase 4: Interpreter (Week 8-9)
- [ ] Implement compile-time execution mode
- [ ] Allow file I/O effects in compile-time mode
- [ ] Disallow other effects in compile-time mode
- [ ] Write comptime tests

### Phase 5: Reflection (Week 10-11)
- [ ] Implement `type_info` function
- [ ] Implement `fields_of` function
- [ ] Implement `constructors_of` function
- [ ] Implement `emit_impl` function
- [ ] Write reflection tests

### Phase 6: Standard Library (Week 12-14)
- [ ] Implement parser primitives (parse_expr, parse_separated, etc.)
- [ ] Implement AST builders (identifier, call, record, etc.)
- [ ] Implement derive functions (derive_eq, derive_show, etc.)
- [ ] Implement built-in macros (list!, map!, assert_eq!, etc.)
- [ ] Write stdlib tests

### Phase 7: Error Messages (Week 15)
- [ ] Implement rich error messages for macro errors
- [ ] Show original and expanded code
- [ ] Provide helpful suggestions
- [ ] Write error message tests

### Phase 8: Integration (Week 16)
- [ ] End-to-end integration tests
- [ ] Performance benchmarks
- [ ] Documentation updates
- [ ] Release!

## Summary

The macro system implementation requires:
1. Parser changes for new syntax
2. Type checker macro expansion phase
3. Hygiene system for name resolution
4. Interpreter compile-time mode
5. Reflection API for type introspection
6. Standard library primitives
7. Comprehensive testing
8. Great error messages

Total estimated time: **16 weeks** with 1-2 developers.

The design is clean, type-safe, and fits naturally into Mezze's existing architecture.
