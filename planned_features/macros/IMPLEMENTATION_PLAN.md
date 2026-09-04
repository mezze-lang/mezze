# Mezze Macro System - Implementation Plan

## Overview

This document provides a step-by-step plan for implementing Mezze's macro system, from basic suspended type syntax to full macro support.

## Prerequisites

- ✅ AST already has `SuspendType` (MezzeAST.kt:523-549)
- ✅ Type checker already uses `SuspendType` internally
- ⏳ Parser needs to support user-written suspended types
- ⏳ `Expr` type alias needs to be defined

## Phase 1: Suspended Type Syntax (Foundation)

**Goal**: Allow users to write `Type <Effects>` syntax anywhere, not just in function types.

### Step 1.1: Add SuspendTypeAnnotation to AST

**File**: `ast/src/main/kotlin/com/mezze/language/ast/MezzeAST.kt`

**Changes**:
```kotlin
// Add to sealed interface TypeAnnotation (around line 1806)
@Serializable
data class SuspendTypeAnnotation(
    val valueType: TypeAnnotation,
    val effects: EffectRowAnnotation
) : TypeAnnotation
```

**Test**: Compile AST module
```bash
./gradlew :ast:build
```

**Dependencies**: None

**Estimated time**: 15 minutes

---

### Step 1.2: Update convertTypeAnnotation

**File**: `ast/src/main/kotlin/com/mezze/language/ast/MezzeAST.kt`

**Changes**:
```kotlin
// Add to convertTypeAnnotation function (around line 1910)
fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
    return when (annotation) {
        // ... existing cases ...

        is SuspendTypeAnnotation -> {
            val valueType = convertTypeAnnotation(annotation.valueType)
            val effects = MezzeType.EffectRow(
                annotation.effects.effects.toSet(),
                if (annotation.effects.isPolymorphic) RowVar.fresh() else null
            )
            MezzeType.SuspendType(valueType, effects)
        }

        // ... rest ...
    }
}
```

**Test**: Unit test for conversion
```kotlin
@Test
fun testConvertSuspendTypeAnnotation() {
    val annotation = SuspendTypeAnnotation(
        IdentifierTypeAnnotation("Int"),
        EffectRowAnnotation(listOf("Log"), false)
    )
    val mezzeType = convertTypeAnnotation(annotation)

    assertTrue(mezzeType is MezzeType.SuspendType)
    val suspended = mezzeType as MezzeType.SuspendType
    assertTrue(suspended.valueType is MezzeType.IntType)
    assertEquals(setOf("Log"), suspended.effects.effects)
}
```

**Dependencies**: Step 1.1

**Estimated time**: 30 minutes

---

### Step 1.3: Update Parser to Parse Suspended Types

**File**: `parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt`

**Changes**:

1. **Extract effect row parsing into reusable function** (around line 4070):
```kotlin
/**
 * Parse effect row: <> or <Effect1, Effect2> or <...> or <Effect1, ...>
 * Returns (effects: List<String>, isPolymorphic: Boolean, remaining: TokenStream)
 */
private fun parseEffectRow(stream: TokenStream): Triple<List<String>, Boolean, TokenStream>? {
    val ltToken = stream.current()
    if (ltToken?.type != TokenType.LT) return null

    val afterLt = stream.advance()
    val gtToken = afterLt.current()

    if (gtToken?.type == TokenType.GT) {
        // Empty effect row <>
        return Triple(emptyList(), false, afterLt.advance())
    }

    // Check for purely polymorphic <...>
    val ellipsisToken = skipNewlines(afterLt).current()
    if (ellipsisToken?.type == TokenType.ELLIPSIS) {
        val afterEllipsis = skipNewlines(afterLt.advance())
        if (afterEllipsis.current()?.type == TokenType.GT) {
            // Polymorphic effect row <...>
            return Triple(emptyList(), true, afterEllipsis.advance())
        } else {
            return null  // Invalid syntax
        }
    }

    // Has effect names <Effect1, Effect2> or <Effect1, ...>
    val effectNames = mutableListOf<String>()
    var current = skipNewlines(afterLt)
    var foundEllipsis = false

    // Parse comma-separated effect names
    while (current.current()?.type == TokenType.IDENTIFIER) {
        effectNames.add(current.current()!!.text)
        current = skipNewlines(current.advance())

        // Check for comma
        if (current.current()?.type == TokenType.COMMA) {
            current = skipNewlines(current.advance())

            // Check if next is ... (effect extension)
            if (current.current()?.type == TokenType.ELLIPSIS) {
                foundEllipsis = true
                current = skipNewlines(current.advance())
                break
            }
        } else {
            break
        }
    }

    // Expect closing >
    if (current.current()?.type == TokenType.GT) {
        return Triple(effectNames, foundEllipsis, current.advance())
    } else {
        return null  // No closing >, invalid syntax
    }
}
```

2. **Add suspended type parser** (insert after primaryTypeAnnotation):
```kotlin
/**
 * Parse suspended type: Type <Effects>
 * This allows effects on any type, not just function return types
 */
private val suspendedTypeAnnotation: Parser<TypeAnnotation> = { stream ->
    // Parse base type (without effects)
    val baseType = primaryTypeAnnotation(stream)

    when (baseType) {
        is ParseResult.Success -> {
            val afterType = skipNewlines(baseType.remaining)

            // Try to parse effect row
            val effectRow = parseEffectRow(afterType)

            if (effectRow != null) {
                val (effects, isPolymorphic, remaining) = effectRow
                ParseResult.Success(
                    SuspendTypeAnnotation(
                        baseType.value,
                        EffectRowAnnotation(effects, isPolymorphic)
                    ),
                    remaining
                )
            } else {
                // No effects, return base type as-is
                baseType
            }
        }
        is ParseResult.Failure -> baseType
    }
}
```

3. **Update typeAnnotationParser to use suspendedTypeAnnotation**:
```kotlin
// Change the order to try suspended types first
private val typeAnnotationParser: Parser<TypeAnnotation> = { stream ->
    Parsers.oneOf(
        functionTypeAnnotation,      // {} -> Int <Log>
        suspendedTypeAnnotation,     // Int <Log> (NEW!)
        // Note: primaryTypeAnnotation is called inside suspendedTypeAnnotation
    )(stream)
}
```

4. **Update function type parser to use extracted parseEffectRow**:
```kotlin
// In functionTypeAnnotation, replace inline effect parsing with:
val effectRow = parseEffectRow(streamAfterReturn)
val (effects, isPolymorphic, finalStream) = if (effectRow != null) {
    effectRow
} else {
    Triple(emptyList<String>(), false, returnType.remaining)
}
```

**Test**: Parser tests
```kotlin
@Test
fun testParseSuspendType() {
    val source = "let x: Int <Log> = value"
    val program = parser.parse(source)

    val letBinding = program.items[0] as LetBinding
    assertTrue(letBinding.typeAnnotation is SuspendTypeAnnotation)

    val suspended = letBinding.typeAnnotation as SuspendTypeAnnotation
    assertEquals("Int", (suspended.valueType as IdentifierTypeAnnotation).name)
    assertEquals(listOf("Log"), suspended.effects.effects)
}

@Test
fun testParseSuspendedRecordType() {
    val source = "let config: {port: Int} <FileSystem> = getConfig {}"
    val program = parser.parse(source)

    val letBinding = program.items[0] as LetBinding
    assertTrue(letBinding.typeAnnotation is SuspendTypeAnnotation)
}

@Test
fun testParseSuspendedPolymorphicType() {
    val source = "let x: Int <...> = value"
    val program = parser.parse(source)

    val letBinding = program.items[0] as LetBinding
    val suspended = letBinding.typeAnnotation as SuspendTypeAnnotation
    assertTrue(suspended.effects.isPolymorphic)
}

@Test
fun testParseMultipleEffects() {
    val source = "let x: Int <Log, FileSystem> = value"
    val program = parser.parse(source)

    val letBinding = program.items[0] as LetBinding
    val suspended = letBinding.typeAnnotation as SuspendTypeAnnotation
    assertEquals(setOf("Log", "FileSystem"), suspended.effects.effects.toSet())
}
```

**Dependencies**: Steps 1.1, 1.2

**Estimated time**: 3 hours

---

### Step 1.4: Fix Bug in applySubstitution

**File**: `typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt`

**Issue**: `applySubstitution` (line 3528-3573) uses `else -> type` and doesn't explicitly handle `SuspendType`.

**Changes**:
```kotlin
private fun applySubstitution(
    type: com.mezze.language.ast.MezzeType,
    substitutions: Map<com.mezze.language.ast.TypeVar, com.mezze.language.ast.MezzeType>
): com.mezze.language.ast.MezzeType {
    return when (type) {
        // ... existing cases ...

        is com.mezze.language.ast.MezzeType.SuspendType -> {
            // Apply substitutions to the value type
            val substitutedValueType = applySubstitution(type.valueType, substitutions)
            // Effects don't contain type variables, so keep them as-is
            com.mezze.language.ast.MezzeType.SuspendType(
                substitutedValueType,
                type.effects
            )
        }

        is com.mezze.language.ast.MezzeType.SetType -> {
            // ... existing code ...
        }

        else -> type
    }
}
```

**Test**:
```kotlin
@Test
fun testApplySubstitutionToSuspendType() {
    val typeVar = TypeVar("'a")
    val suspendedType = MezzeType.SuspendType(
        MezzeType.VarType(typeVar),
        MezzeType.EffectRow(setOf("Log"))
    )

    val substitutions = mapOf(typeVar to MezzeType.IntType)
    val result = applySubstitution(suspendedType, substitutions)

    assertTrue(result is MezzeType.SuspendType)
    val suspended = result as MezzeType.SuspendType
    assertTrue(suspended.valueType is MezzeType.IntType)
    assertEquals(setOf("Log"), suspended.effects.effects)
}
```

**Dependencies**: None (bug fix)

**Estimated time**: 30 minutes

---

### Step 1.5: Search and Fix Exhaustive When Expressions

**File**: `typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt`

**Task**: Find all exhaustive `when` expressions on `MezzeType` and ensure they handle `SuspendType`.

**Command**:
```bash
grep -n "when (.*type.*)" typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt | grep -v "when {"
```

**Known locations to check**:
- `containsConcreteTypes` (line 2937)
- `typesAreCompatibleForAnnotation` (line 2961)
- `unwrapSuspendType` (line 3380) - already handles it
- `isNumericType` (line 3391) - already handles it
- `applySubstitution` (line 3528) - fixed in step 1.4

**For each location**: Add explicit `SuspendType` case or verify `else` clause is correct.

**Dependencies**: Step 1.4

**Estimated time**: 2 hours

---

### Step 1.6: Test Effect Subtyping

**File**: New test file `typechecker/src/test/kotlin/com/mezze/language/typechecker/SuspendTypeTest.kt`

**Tests**:
```kotlin
class SuspendTypeTest {
    @Test
    fun testSuspendTypeWithEffects() {
        val source = """
            let x: Int <Log> = perform Log in Log.compute {}
        """.trimIndent()

        val result = typeCheck(source)
        assertTrue(result.isSuccess)
    }

    @Test
    fun testCannotAssignSuspendedToPure() {
        val source = """
            let f = {} -> perform Log in Log.value {}
            let x: Int = f {}
        """.trimIndent()

        val result = typeCheck(source)
        assertTrue(result.isFailure)
        assertTrue(result.error.contains("unhandled effects"))
    }

    @Test
    fun testEffectSubtyping() {
        val source = """
            let x: Int <Log> = perform Log in Log.value {}
            let y: Int <Log, FileSystem> = x  # Should fail - missing FileSystem
        """.trimIndent()

        val result = typeCheck(source)
        assertTrue(result.isFailure)
    }

    @Test
    fun testEffectPolymorphism() {
        val source = """
            let x: Int <...> = perform Log in Log.value {}  # OK - polymorphic
        """.trimIndent()

        val result = typeCheck(source)
        assertTrue(result.isSuccess)
    }
}
```

**Dependencies**: Steps 1.1-1.5

**Estimated time**: 2 hours

---

## Phase 2: Expr Type and Basic Macros

**Goal**: Define `Expr` type and enable basic macro declarations.

### Step 2.1: Define Expr as Phantom Type

**File**: New file `stdlib/std/macro/expr.mz`

**Content**:
```mezze
# Phantom type wrapper for macro return values
#
# Expr 'a represents an AST expression that, when expanded, produces a value of type 'a
# This type only exists at compile-time and is unwrapped during macro expansion.
#
# Examples:
#   Expr Int                  # AST producing Int
#   Expr (List 'a)            # AST producing List 'a
#   Expr (Int <Log>)          # AST producing Int with Log effect
#
# The 'a parameter is a phantom type - it doesn't appear in the actual representation
# (Expression), but exists solely for type-checking.
pub type Expr 'a = Expression
```

**Note**: `Expression` is a compiler built-in type representing AST nodes.

**Test**: Parse and type check the file
```bash
./cli/build/install/mezze/bin/mezze check stdlib/std/macro/expr.mz
```

**Dependencies**: Phase 1 complete

**Estimated time**: 15 minutes

---

### Step 2.2: Add MacroDeclaration to AST

**File**: `ast/src/main/kotlin/com/mezze/language/ast/MezzeAST.kt`

**Changes**:
```kotlin
// Add to ProgramItem (around line 886)
@Serializable
data class MacroDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val paramType: TypeAnnotation,           // Always {tokens: TokenStream} for pattern macros
    val returnType: TypeAnnotation,          // Always Expr (SomeType)
    val body: Expression,                    // Macro implementation
    val inferredType: MezzeType? = null       // From typechecker
) : ProgramItem
```

**Test**: Compile AST module

**Dependencies**: Step 2.1

**Estimated time**: 30 minutes

---

### Step 2.3: Add Macro Declaration Parsing

**File**: `parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt`

**Changes**:
```kotlin
// Add macro parser (similar to let binding parser)
private val macroDeclaration: Parser<ProgramItem> = { stream ->
    // Parse: macro name: {tokens: TokenStream} -> Expr (ReturnType) = {tokens} -> body

    val macroToken = tok(TokenType.MACRO)(stream)
    when (macroToken) {
        is ParseResult.Success -> {
            // Parse name
            val nameToken = tok(TokenType.IDENTIFIER)(macroToken.remaining)
            when (nameToken) {
                is ParseResult.Success -> {
                    val name = nameToken.value.text

                    // Parse : Type annotation
                    // Parse = Expression
                    // ...
                }
            }
        }
    }
}

// Add MACRO to TokenType enum
enum class TokenType {
    // ...existing...
    MACRO,
    // ...
}

// Add "macro" to keyword recognition in lexer
```

**Test**: Parser tests
```kotlin
@Test
fun testParseMacroDeclaration() {
    val source = """
        macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
            build_list {tokens}
    """.trimIndent()

    val program = parser.parse(source)
    assertTrue(program.items[0] is MacroDeclaration)

    val macro = program.items[0] as MacroDeclaration
    assertEquals("list", macro.name)
}
```

**Dependencies**: Step 2.2

**Estimated time**: 4 hours

---

### Step 2.4: Add Macro Call Parsing

**File**: `parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt`

**Changes**:
```kotlin
// Recognize identifier!(args) as macro call
// This is similar to positional function call parsing

// Add MacroCall expression
@Serializable
data class MacroCall(
    val macroName: String,
    val tokens: List<Token>,  // Raw tokens inside !()
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression
```

**Test**: Parser tests
```kotlin
@Test
fun testParseMacroCall() {
    val source = "let x = list!(1, 2, 3)"
    val program = parser.parse(source)

    val letBinding = program.items[0] as LetBinding
    assertTrue(letBinding.expression is MacroCall)
}
```

**Dependencies**: Step 2.3

**Estimated time**: 3 hours

---

### Step 2.5: Type Check Macro Declarations

**File**: `typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt`

**Changes**:
```kotlin
// Add case for MacroDeclaration in typeCheck(program)
when (item) {
    is MacroDeclaration -> {
        // 1. Convert type annotations to MezzeType
        val paramType = convertTypeAnnotation(item.paramType)
        val returnType = convertTypeAnnotation(item.returnType)

        // 2. Verify returnType is Expr (SomeType)
        if (!isExprType(returnType)) {
            error("Macro must return Expr type, got: ${prettyPrintType(returnType)}")
        }

        // 3. Type check macro body
        val bodyType = inferType(item.body, context)

        // 4. Verify body type matches declared return type
        if (!unify(bodyType, returnType)) {
            error("Macro body type doesn't match declared return type")
        }

        // 5. Store macro in context
        context.addMacro(item.name, MacroInfo(paramType, returnType, item.body))
    }
}

fun isExprType(type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.TypeApplication -> type.constructor == "Expr" && type.args.size == 1
        else -> false
    }
}
```

**Test**: Type checker tests
```kotlin
@Test
fun testTypeCheckMacroDeclaration() {
    val source = """
        macro five: {tokens: TokenStream} -> Expr Int = {tokens} ->
            int_lit {value = 5}
    """.trimIndent()

    val result = typeCheck(source)
    assertTrue(result.isSuccess)
}
```

**Dependencies**: Steps 2.1-2.4

**Estimated time**: 4 hours

---

### Step 2.6: Implement Macro Expansion

**File**: `typechecker/src/main/kotlin/com/mezze/language/typechecker/MacroExpander.kt` (new file)

**Content**:
```kotlin
class MacroExpander(val context: TypeCheckContext) {

    fun expandMacro(call: MacroCall): Expression {
        // 1. Look up macro definition
        val macroInfo = context.getMacro(call.macroName)
            ?: throw TypeCheckError("Undefined macro: ${call.macroName}")

        // 2. Execute macro function (interpret at compile-time)
        val tokenStream = TokenStream(call.tokens)
        val result = executeMacroFunction(macroInfo.body, tokenStream)

        // 3. Result should be an Expression (AST node)
        if (result !is Expression) {
            throw TypeCheckError("Macro must return Expression, got: ${result::class}")
        }

        // 4. Type check generated AST
        val generatedType = inferType(result, context)

        // 5. Verify type matches macro's declared return type (unwrap Expr)
        val declaredType = unwrapExprType(macroInfo.returnType)
        if (!unify(generatedType, declaredType)) {
            throw TypeCheckError(
                "Macro ${call.macroName} generated wrong type\n" +
                "  Expected: ${prettyPrintType(declaredType)}\n" +
                "  Generated: ${prettyPrintType(generatedType)}"
            )
        }

        // 6. Return expanded AST (Expr unwrapped!)
        return result
    }

    private fun unwrapExprType(exprType: MezzeType): MezzeType {
        return when (exprType) {
            is MezzeType.TypeApplication ->
                if (exprType.constructor == "Expr" && exprType.args.size == 1)
                    exprType.args[0]
                else
                    exprType
            else -> exprType
        }
    }

    private fun executeMacroFunction(body: Expression, tokens: TokenStream): Any {
        // This requires a compile-time interpreter
        // For now, placeholder:
        TODO("Implement compile-time macro execution")
    }
}
```

**Test**: Macro expansion tests
```kotlin
@Test
fun testExpandSimpleMacro() {
    val source = """
        macro five: {tokens: TokenStream} -> Expr Int = {tokens} ->
            int_lit {value = 5}

        let x = five!()
    """.trimIndent()

    val result = typeCheck(source)
    assertTrue(result.isSuccess)

    // Verify x has type Int (not Expr Int)
    val xType = result.bindings["x"]
    assertEquals(MezzeType.IntType, xType)
}
```

**Dependencies**: Step 2.5

**Estimated time**: 6 hours

---

## Phase 3: Standard Library Support

**Goal**: Provide AST builder functions for macros to use.

### Step 3.1: Define AST Builder Functions

**File**: `stdlib/std/macro/ast_builders.mz`

**Content**:
```mezze
# AST builder functions for macros
# These are compiler built-ins that construct AST nodes

# Literals
pub let int_lit: {value: Int} -> Expr Int = __native_int_lit
pub let string_lit: {value: String} -> Expr String = __native_string_lit
pub let bool_lit: {value: Bool} -> Expr Bool = __native_bool_lit

# Identifier reference
pub let identifier: {name: String} -> Expr 'a = __native_identifier

# Qualified name (module::function)
pub let qualified: {name: String} -> Expr 'a = __native_qualified

# Function call
pub let call: {fn: Expr ('a -> 'b), args: Expr 'a} -> Expr 'b = __native_call

# Record literal
pub let record: {fields: [(String, Expr 'a)]} -> Expr 'b = __native_record

# List literal
pub let list_literal: {elements: [Expr 'a]} -> Expr (List 'a) = __native_list_literal

# Lambda expression
pub let lambda: {params: [String], body: Expr 'a} -> Expr ({...} -> 'a) = __native_lambda

# Do block
pub let do_expr: {statements: [Expr {}], result: Expr 'a} -> Expr 'a = __native_do_expr

# If expression
pub let if_expr: {
    condition: Expr Bool,
    then_branch: Expr 'a,
    else_branch: Expr 'a
} -> Expr 'a = __native_if_expr

# Match expression
pub let match_expr: {
    expression: Expr 'a,
    cases: [(Pattern, Expr 'b)]
} -> Expr 'b = __native_match_expr
```

**Note**: These `__native_*` functions are implemented in the compiler.

**Dependencies**: Step 2.6

**Estimated time**: 4 hours (implementing native functions)

---

### Step 3.2: Implement Built-in Macros

**File**: `stdlib/std/macro/builtins.mz`

**Content**:
```mezze
use std::macro::ast_builders::{int_lit, call, qualified, identifier}
use std::macro::expr::{Expr}

# Debug print macro - prints value and returns it
pub macro dbg: {tokens: TokenStream} -> Expr 'a <Console> = {tokens} ->
    match parse_expr {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {expr} ->
            # Generate: do
            #   let __tmp = expr
            #   perform Console in print {msg = "Debug: ..."}
            #   __tmp
            do_expr {
                statements = [
                    let_stmt {
                        name = "__tmp",
                        value = expr
                    },
                    expr_stmt {
                        call {
                            fn = qualified {name = "Console::print"},
                            args = record {fields = [
                                {"msg", string_concat {parts = [
                                    string_lit {value = "Debug: "},
                                    call {
                                        fn = qualified {name = "Inspect::to_str"},
                                        args = identifier {name = "__tmp"}
                                    }
                                ]}}
                            ]}
                        }
                    }
                ],
                result = identifier {name = "__tmp"}
            }

# Assert equality macro
pub macro assert_eq: {tokens: TokenStream} -> Expr {} <Exception> = {tokens} ->
    match parse_two_exprs {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}
        Ok {left, right} ->
            # Generate: do
            #   let __left = left
            #   let __right = right
            #   match Eq.eq {l = __left, r = __right} is
            #       True -> {}
            #       False -> perform Exception in throw {
            #           msg = "Assertion failed: $(__left) != $(__right)"
            #       }
            ...

# List construction macro
pub macro list: {tokens: TokenStream} -> Expr (List 'a <...>) = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}
        Ok {items} when list::is_empty {list = items} ->
            qualified {name = "list::empty"}
        Ok {items} ->
            list_literal {elements = items}
```

**Dependencies**: Steps 3.1, 2.6

**Estimated time**: 6 hours

---

## Phase 4: Testing and Documentation

### Step 4.1: Comprehensive Test Suite

**Files**: Various test files in `typechecker/src/test/kotlin`

**Test categories**:

1. **Parser tests** (already covered in earlier steps)
2. **Type checker tests** (already covered)
3. **Integration tests**:

```kotlin
@Test
fun testListMacro() {
    val source = """
        use std::macro::builtins::{list}

        let numbers = list!(1, 2, 3, 4, 5)
    """.trimIndent()

    val result = typeCheck(source)
    assertTrue(result.isSuccess)

    val numbersType = result.bindings["numbers"]
    assertTrue(numbersType is MezzeType.ListType)
    assertEquals(MezzeType.IntType, (numbersType as MezzeType.ListType).elementType)
}

@Test
fun testMacroWithEffects() {
    val source = """
        macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = {tokens} ->
            do_expr {
                statements = [
                    expr_stmt {call {
                        fn = qualified {name = "Log::info"},
                        args = record {fields = [{"msg", string_lit {value = "computing"}}]}
                    }}
                ],
                result = int_lit {value = 42}
            }

        let x = log_value!()
    """.trimIndent()

    val result = typeCheck(source)
    assertTrue(result.isSuccess)

    val xType = result.bindings["x"]
    assertTrue(xType is MezzeType.SuspendType)
    val suspended = xType as MezzeType.SuspendType
    assertEquals(MezzeType.IntType, suspended.valueType)
    assertEquals(setOf("Log"), suspended.effects.effects)
}
```

4. **Error message tests**:

```kotlin
@Test
fun testMacroTypeMismatch() {
    val source = """
        macro int_macro: {tokens: TokenStream} -> Expr Int = {tokens} ->
            string_lit {value = "wrong"}  # Should return Expr Int, not Expr String
    """.trimIndent()

    val result = typeCheck(source)
    assertTrue(result.isFailure)
    assertTrue(result.error.contains("type mismatch"))
}
```

**Dependencies**: Phases 1-3 complete

**Estimated time**: 8 hours

---

### Step 4.2: Update Documentation

**Files to update**:
1. `docs/macros/README.md` - Add suspended type examples
2. `docs/macros/01_PATTERN_MACROS.md` - Update with `Expr (Type <Effects>)` syntax
3. `docs/macros/02_TAGGED_TEMPLATES.md` - ✅ Updated to show DSL embedding with pattern macros
4. `docs/macros/04_SYNTAX_EXPANSION.md` - Update `Expr` explanation
5. `docs/macros/DESIGN_DECISIONS.md` - ✅ Updated DSL embedding section
6. `docs/macros/QUICK_REFERENCE.md` - ✅ Updated macro invocation examples
7. `docs/macros/INDEX.md` - ✅ Updated references to tagged templates

**Changes**: Replace all instances of:
- Old: `macro list: {tokens: TokenStream} -> List 'a = ...`
- New: `macro list: {tokens: TokenStream} -> Expr (List 'a) = ...`

Add suspended type examples:
```mezze
# Macro returning effectful code
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...

# Macro returning effect-polymorphic code
macro generic_op: {tokens: TokenStream} -> Expr ('a <...>) = ...
```

**Note**: Single macro type design - all macros use pattern syntax `!()` with `TokenStream`, including DSL embedding (e.g., `sql!("SELECT...")` instead of `sql```SELECT...````).

**Dependencies**: All previous steps

**Estimated time**: 4 hours

---

## Timeline and Effort Estimate

### Phase 1: Suspended Type Syntax
- **Total time**: ~10 hours
- **Critical path**: Parser changes (Step 1.3)
- **Risk**: Medium (parser complexity)

### Phase 2: Expr Type and Basic Macros
- **Total time**: ~17 hours
- **Critical path**: Macro expansion (Step 2.6)
- **Risk**: High (compile-time execution)

### Phase 3: Standard Library
- **Total time**: ~10 hours
- **Critical path**: Native AST builders (Step 3.1)
- **Risk**: Medium (compiler integration)

### Phase 4: Testing and Documentation
- **Total time**: ~12 hours
- **Risk**: Low

**Total estimated time**: ~49 hours (~6-7 days of focused work)

## Dependencies Graph

```
Phase 1 (Suspended Types)
├── 1.1 Add SuspendTypeAnnotation
│   └── 1.2 Update convertTypeAnnotation
│       └── 1.3 Update Parser
│           ├── 1.4 Fix applySubstitution (parallel)
│           └── 1.5 Fix exhaustive whens
│               └── 1.6 Test effect subtyping
│
Phase 2 (Expr and Macros) - depends on Phase 1
├── 2.1 Define Expr type
│   └── 2.2 Add MacroDeclaration AST
│       └── 2.3 Parse macro declarations
│           └── 2.4 Parse macro calls
│               └── 2.5 Type check macros
│                   └── 2.6 Macro expansion
│
Phase 3 (Stdlib) - depends on Phase 2
├── 3.1 AST builders
│   └── 3.2 Built-in macros
│
Phase 4 (Testing/Docs) - depends on Phase 3
├── 4.1 Test suite
└── 4.2 Documentation
```

## Risk Mitigation

### High Risk: Compile-time Macro Execution (Step 2.6)

**Risk**: Executing macros at compile-time requires an interpreter.

**Mitigation options**:
1. **Option A**: Reuse existing Mezze interpreter in "compile-time mode"
2. **Option B**: Implement minimal evaluator for AST builders only
3. **Option C**: Use Kotlin to implement built-in macros initially

**Recommendation**: Start with Option C, migrate to Option A later.

### Medium Risk: Parser Ambiguity (Step 1.3)

**Risk**: `<` token is both comparison operator and effect syntax start.

**Mitigation**: Context-sensitive parsing - `<` only starts effects in type annotation positions.

### Medium Risk: Effect System Interaction (Step 1.6)

**Risk**: Suspended types might break existing effect tracking.

**Mitigation**: Comprehensive tests covering effect subtyping, polymorphism, and handling.

## Success Criteria

Phase 1 is complete when:
- [ ] Users can write `let x: Int <Log> = ...` in source code
- [ ] Parser accepts suspended type syntax
- [ ] Type checker correctly infers and checks suspended types
- [ ] All tests pass

Phase 2 is complete when:
- [ ] Users can declare macros with `macro name: ... -> Expr (...) = ...`
- [ ] Macro calls expand correctly
- [ ] Generated code is type-checked
- [ ] `Expr` types unwrap properly

Phase 3 is complete when:
- [ ] Built-in macros work: `list!`, `dbg!`, `assert_eq!`
- [ ] AST builder functions are available
- [ ] Standard library macros are documented

Phase 4 is complete when:
- [ ] Comprehensive test suite passes
- [ ] Documentation is updated
- [ ] Example code works end-to-end

## Next Steps

1. **Review this plan** with the team
2. **Start with Phase 1, Step 1.1** (Add SuspendTypeAnnotation)
3. **Create feature branch**: `feature/macro-system`
4. **Implement incrementally**, testing at each step
5. **Create PRs** for each phase separately

---

**Questions or concerns?** Review DESIGN_DECISIONS.md and SUSPENDED_TYPE_SYNTAX_ANALYSIS.md for additional context.
