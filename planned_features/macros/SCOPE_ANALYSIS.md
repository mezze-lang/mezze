# Macro System Implementation - Scope Analysis

This document analyzes the scope of changes needed across the codebase to implement the macro system.

## Executive Summary

**Total estimated changes: ~5,000-7,000 lines of code across 4 modules**

| Module | Current Size | New Code | Modified Code | Total Impact |
|--------|-------------|----------|---------------|--------------|
| **AST** | 3,489 lines | ~200 lines | ~50 lines | **~250 lines (~7% change)** |
| **Parser** | 5,363 lines | ~800 lines | ~200 lines | **~1,000 lines (~19% change)** |
| **TypeChecker** | 19,246 lines | ~2,500 lines | ~500 lines | **~3,000 lines (~16% change)** |
| **Interpreter** | 3,092 lines | ~200 lines | ~100 lines | **~300 lines (~10% change)** |
| **Stdlib** | 30 files | ~15 new files | ~5 files | **~20 files (~67% addition)** |
| **Tests** | 31,442 lines | ~3,000 lines | ~200 lines | **~3,200 lines (~10% addition)** |

---

## Module-by-Module Breakdown

### 1. AST Module

**Current State:**
- **Files**: 3 Kotlin files
- **Main file**: `MezzeAST.kt` (82 KB, ~2,200 lines)
- **Total lines**: 3,489 lines

**Phase 1: Suspended Type Syntax (~250 lines)**

#### New Code (~200 lines):
```kotlin
// File: MezzeAST.kt
// Location: Add to sealed interface TypeAnnotation (around line 1806)

// 1. Add SuspendTypeAnnotation (30 lines)
@Serializable
data class SuspendTypeAnnotation(
    val valueType: TypeAnnotation,
    val effects: EffectRowAnnotation
) : TypeAnnotation

// 2. Add EffectRowAnnotation if not exists (20 lines)
@Serializable
data class EffectRowAnnotation(
    val effects: List<String>,
    val isPolymorphic: Boolean
)

// 3. Update convertTypeAnnotation (30 lines)
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
    }
}
```

**Phase 2: Macro Infrastructure (~100 lines)**

#### New Code (~100 lines):
```kotlin
// 4. Add MacroDeclaration (40 lines)
@Serializable
data class MacroDeclaration(
    val name: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val body: Expression,
    val inferredType: MezzeType? = null
) : ProgramItem

// 5. Add MacroCall expression (30 lines)
@Serializable
data class MacroCall(
    val macroName: String,
    val tokens: List<Token>,
    override val inferredType: MezzeType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

// 6. Add TokenStream type (30 lines)
@Serializable
data class Token(
    val type: TokenType,
    val text: String,
    val location: SourceLocation
)

enum class TokenType {
    Identifier, StringLiteral, IntLiteral, FloatLiteral,
    LParen, RParen, LBrace, RBrace, LBracket, RBracket,
    LT, GT, Comma, Dot, Colon, Arrow, FatArrow, // ...
}
```

#### Modified Code (~50 lines):
- Update serialization for new types (~20 lines)
- Update pattern matching in existing code (~30 lines)

**Risk**: Low - AST changes are mostly additive

---

### 2. Parser Module

**Current State:**
- **Files**: 4 Kotlin files
- **Main file**: `MezzeParser.kt` (237 KB, ~5,000 lines)
- **Total lines**: 5,363 lines

**Phase 1: Suspended Type Syntax (~400 lines)**

#### New Code (~300 lines):
```kotlin
// File: MezzeParser.kt

// 1. Extract parseEffectRow function (80 lines)
private fun parseEffectRow(stream: TokenStream):
    Triple<List<String>, Boolean, TokenStream>? {
    // Parse: <> or <Effect1, Effect2> or <...> or <Effect1, ...>
    // ...
}

// 2. Add suspendedTypeAnnotation parser (100 lines)
private val suspendedTypeAnnotation: Parser<TypeAnnotation> = { stream ->
    // Parse: Type <Effects>
    // ...
}

// 3. Update typeAnnotationParser (50 lines)
private val typeAnnotationParser: Parser<TypeAnnotation> = { stream ->
    Parsers.oneOf(
        functionTypeAnnotation,
        suspendedTypeAnnotation,  // NEW!
        // ...
    )(stream)
}

// 4. Update function type parser to use parseEffectRow (70 lines)
// Refactor inline effect parsing to use extracted function
```

#### Modified Code (~100 lines):
- Update existing function type parsing (~50 lines)
- Update type annotation ordering (~50 lines)

**Phase 2: Macro Declarations (~600 lines)**

#### New Code (~500 lines):
```kotlin
// 5. Add MACRO token type (10 lines)
// Update lexer to recognize "macro" keyword

// 6. Add macroDeclaration parser (200 lines)
private val macroDeclaration: Parser<ProgramItem> = { stream ->
    // Parse: macro name: Type = body
    // ...
}

// 7. Add macroCall parser (150 lines)
private val macroCall: Parser<Expression> = { stream ->
    // Parse: identifier!(tokens)
    // Capture raw tokens inside !()
    // ...
}

// 8. Update program item parsers (80 lines)
// Add macro declaration to top-level parsers

// 9. Token capturing utilities (60 lines)
private fun captureRawTokens(
    stream: TokenStream,
    open: String,
    close: String
): Pair<List<Token>, TokenStream> {
    // Capture tokens between delimiters
    // ...
}
```

#### Modified Code (~100 lines):
- Update expression parsing priority (~50 lines)
- Update program item dispatching (~50 lines)

**Risk**: Medium - Parser is large and complex, but changes are well-isolated

---

### 3. TypeChecker Module

**Current State:**
- **Files**: 13 Kotlin files
- **Main file**: `TypeChecker.kt` (859 KB, ~17,000 lines!)
- **Total lines**: 19,246 lines

**Phase 1: Suspended Type Syntax (~500 lines)**

#### New Code (~200 lines):
```kotlin
// File: TypeChecker.kt

// 1. Add unit tests for suspended types (100 lines)
// In SuspendTypeTest.kt

// 2. Add helper functions (100 lines)
private fun unwrapSuspendType(type: MezzeType): MezzeType {
    return when (type) {
        is MezzeType.SuspendType -> type.valueType
        else -> type
    }
}

private fun getSuspendedEffects(type: MezzeType): EffectRow? {
    return when (type) {
        is MezzeType.SuspendType -> type.effects
        else -> null
    }
}
```

#### Modified Code (~300 lines):
```kotlin
// 3. Fix applySubstitution (30 lines)
private fun applySubstitution(
    type: MezzeType,
    substitutions: Map<TypeVar, MezzeType>
): MezzeType {
    return when (type) {
        // ... existing cases ...
        is MezzeType.SuspendType -> {
            val substitutedValueType = applySubstitution(type.valueType, substitutions)
            MezzeType.SuspendType(substitutedValueType, type.effects)
        }
        // ...
    }
}

// 4. Update exhaustive when expressions (~200 lines across multiple locations)
// Add SuspendType cases to:
// - containsConcreteTypes
// - typesAreCompatibleForAnnotation
// - prettyPrintType
// - isSubtype
// - unify
// (~40 lines each across 5 functions)

// 5. Effect subtyping validation (~70 lines)
```

**Phase 2: Macro Type Checking (~2,300 lines)**

#### New Code (~2,000 lines):
```kotlin
// File: TypeChecker.kt

// 6. Macro context management (150 lines)
data class MacroInfo(
    val paramType: MezzeType,
    val returnType: MezzeType,
    val body: Expression
)

private val macros = mutableMapOf<String, MacroInfo>()

// 7. Type check macro declarations (300 lines)
private fun typeCheckMacroDeclaration(macro: MacroDeclaration): TypedMacroDeclaration {
    // 1. Convert type annotations
    // 2. Verify returnType is Expr type
    // 3. Type check macro body
    // 4. Store macro in context
    // ...
}

private fun isExprType(type: MezzeType): Boolean {
    return when (type) {
        is MezzeType.TypeApplication ->
            type.constructor == "Expr" && type.args.size == 1
        else -> false
    }
}

// File: MacroExpander.kt (NEW FILE, ~1,500 lines)

// 8. Macro expansion engine (800 lines)
class MacroExpander(val context: TypeCheckContext) {

    fun expandMacro(call: MacroCall): Expression {
        // 1. Look up macro definition
        // 2. Execute macro function (interpret at compile-time)
        // 3. Type check generated AST
        // 4. Verify type matches declaration
        // 5. Return expanded AST
        // ...
    }

    private fun executeMacroFunction(
        body: Expression,
        tokens: TokenStream
    ): Expression {
        // Compile-time execution
        // Use interpreter in "macro mode"
        // ...
    }

    private fun unwrapExprType(exprType: MezzeType): MezzeType {
        // Extract 'a from Expr 'a
        // ...
    }

    private fun validateGeneratedCode(
        expr: Expression,
        expectedType: MezzeType
    ): TypeCheckResult {
        // Type check the generated code
        // ...
    }
}

// 9. Macro hygiene (200 lines)
// Ensure no variable capture
// Generate unique names for macro-generated variables

// 10. Error reporting for macros (200 lines)
// Special error messages for macro failures
// Show both macro call site and generated code

// File: MacroValidation.kt (NEW FILE, ~300 lines)

// 11. Validate macro signatures (150 lines)
private fun validateMacroSignature(macro: MacroDeclaration) {
    // Ensure paramType is {tokens: TokenStream}
    // Ensure returnType is Expr (...)
    // Validate effect annotations
}

// 12. Validate macro calls (150 lines)
private fun validateMacroCall(call: MacroCall) {
    // Check macro exists
    // Validate tokens are well-formed
    // Check for circular macro expansion
}
```

#### Modified Code (~300 lines):
```kotlin
// 13. Update type inference (100 lines)
// Add case for MacroCall in inferType

// 14. Update type checking entry point (50 lines)
// Add macro expansion pass before main type checking

// 15. Update context management (150 lines)
// Track macro definitions
// Manage macro expansion depth (prevent infinite recursion)
```

**Risk**: High - TypeChecker is very large and complex. Macro expansion touches many parts.

---

### 4. Interpreter Module

**Current State:**
- **Files**: 18 Kotlin files
- **Main file**: `MezzeInterpreter.kt` (15 KB, ~400 lines)
- **Total lines**: 3,092 lines

**Phase 2: Compile-Time Execution (~300 lines)**

#### New Code (~200 lines):
```kotlin
// File: MezzeInterpreter.kt

// 1. Add "macro mode" flag (10 lines)
private var isCompileTime: Boolean = false

// 2. Restrict effects in macro mode (50 lines)
private fun checkEffectAllowedInMacro(effect: String): Boolean {
    // Only pure operations allowed in macros
    // No FileSystem, Network, etc.
    return effect in setOf("CompileError", "Parse")
}

// 3. Execute macro at compile time (100 lines)
fun executeAtCompileTime(
    expr: Expression,
    env: Environment
): Value {
    isCompileTime = true
    try {
        return eval(expr, env)
    } finally {
        isCompileTime = false
    }
}

// File: MacroRuntime.kt (NEW FILE, ~40 lines)

// 4. Macro-specific built-ins
// compile_error, parse_expr, etc.
```

#### Modified Code (~100 lines):
```kotlin
// 5. Update eval to check compile-time restrictions (50 lines)
// Block certain operations when isCompileTime = true

// 6. Add AST value type (50 lines)
// Macros return Expression values
sealed class Value {
    // ... existing cases ...
    data class ASTNode(val expr: Expression) : Value()
}
```

**Risk**: Medium - Interpreter changes are moderate but critical for macro execution

---

### 5. Standard Library

**Current State:**
- **Files**: 30 .mz files
- **Directories**: std/effects, std/collections, std/math

**Phase 2 & 3: Macro Infrastructure (~15 new files)**

#### New Files (~1,500 lines):
```
std/macro/
├── expr.mz                    # ~50 lines
│   └── type Expr 'a = Expression
│
├── token_stream.mz            # ~100 lines
│   ├── type TokenStream
│   ├── peek, advance, current
│   └── Token type and TokenType enum
│
├── patterns.mz                # ~600 lines
│   ├── comma_separated
│   ├── separated_by
│   ├── key_value_pairs
│   ├── delimited
│   ├── parse_string_literal
│   ├── parse_expr, parse_identifier, parse_type
│   ├── optional, repeated, repeated1
│   ├── sequence, try_parsers
│   └── map_result, flat_map_result
│
├── token_utils.mz             # ~200 lines
│   ├── peek_token, next_token
│   ├── expect_token
│   ├── skip_whitespace
│   └── is_empty, token_count
│
├── errors.mz                  # ~100 lines
│   ├── compile_error
│   └── format_error
│
├── ast_builders.mz            # ~400 lines
│   ├── Literals: int_lit, string_lit, bool_lit
│   ├── Identifiers: identifier, qualified
│   ├── Calls: call
│   ├── Records: record
│   ├── Lists: list_literal
│   ├── Lambdas: lambda
│   ├── Control flow: if_expr, match_expr
│   └── Do blocks: do_expr
│
└── builtins.mz                # ~50 lines (initially)
    ├── list! macro
    ├── dbg! macro
    └── assert_eq! macro
```

#### Files for DSL Support (~300 lines):
```
std/dsl/
├── sql.mz                     # ~100 lines
│   ├── validate_sql
│   └── build_parameterized_query
│
├── html.mz                    # ~100 lines
│   ├── validate_html
│   ├── parse_html
│   └── build_html_ast
│
└── json.mz                    # ~100 lines
    ├── validate_json
    ├── parse_json_template
    └── build_json_ast
```

**Risk**: Low - New files, minimal modification to existing stdlib

---

### 6. Tests

**Current State:**
- **Test files**: ~119 files in typechecker alone
- **Test lines**: 31,442 lines in typechecker tests

**New Tests Needed (~3,000 lines)**

#### Parser Tests (~500 lines):
```kotlin
// ParserSuspendTypeTest.kt (~200 lines)
@Test fun testParseSuspendType()
@Test fun testParseSuspendedRecordType()
@Test fun testParseSuspendedPolymorphicType()
@Test fun testParseMultipleEffects()
@Test fun testParseFunctionWithSuspendedReturn()
// ...

// ParserMacroTest.kt (~300 lines)
@Test fun testParseMacroDeclaration()
@Test fun testParseMacroCall()
@Test fun testParseMacroWithEffects()
@Test fun testParseNestedMacroCall()
// ...
```

#### TypeChecker Tests (~1,500 lines):
```kotlin
// SuspendTypeTest.kt (~300 lines)
@Test fun testSuspendTypeWithEffects()
@Test fun testCannotAssignSuspendedToPure()
@Test fun testEffectSubtyping()
@Test fun testEffectPolymorphism()
@Test fun testSuspendTypeInference()
// ...

// MacroTypeCheckTest.kt (~600 lines)
@Test fun testTypeCheckMacroDeclaration()
@Test fun testMacroReturnTypeValidation()
@Test fun testMacroCallTypeInference()
@Test fun testMacroExpansionBasic()
@Test fun testMacroWithEffects()
@Test fun testMacroTypeMismatch()
@Test fun testNestedMacroExpansion()
// ...

// MacroExpansionTest.kt (~600 lines)
@Test fun testExpandSimpleMacro()
@Test fun testExpandListMacro()
@Test fun testExpandMapMacro()
@Test fun testExpandSqlMacro()
@Test fun testMacroHygiene()
@Test fun testMacroErrorReporting()
// ...
```

#### Interpreter Tests (~500 lines):
```kotlin
// MacroRuntimeTest.kt (~500 lines)
@Test fun testExecuteMacroAtCompileTime()
@Test fun testMacroBuiltins()
@Test fun testASTBuilderFunctions()
@Test fun testCompileTimeRestrictions()
@Test fun testMacroValueReturns()
// ...
```

#### Integration Tests (~500 lines):
```kotlin
// MacroIntegrationTest.kt (~500 lines)
@Test fun testListMacroEndToEnd()
@Test fun testMapMacroEndToEnd()
@Test fun testAssertMacroEndToEnd()
@Test fun testSqlMacroEndToEnd()
@Test fun testMacroInMacro()
@Test fun testMacroErrorMessages()
// ...
```

**Risk**: Low - Tests are new code, help validate implementation

---

## Implementation Complexity by Phase

### Phase 1: Suspended Type Syntax
**Estimated effort**: ~10 hours (~1,150 lines)

| Module | Lines | Complexity | Risk |
|--------|-------|------------|------|
| AST | 250 | Low | Low |
| Parser | 400 | Medium | Medium |
| TypeChecker | 500 | Medium | High |
| Tests | 500 | Low | Low |

**Key challenges:**
- Parser: Avoiding ambiguity with `<` operator
- TypeChecker: Ensuring all `when` expressions handle `SuspendType`

### Phase 2: Expr and Macro Infrastructure
**Estimated effort**: ~17 hours (~3,300 lines)

| Module | Lines | Complexity | Risk |
|--------|-------|------------|------|
| AST | 100 | Low | Low |
| Parser | 600 | Medium | Medium |
| TypeChecker | 2,300 | Very High | Very High |
| Interpreter | 300 | Medium | Medium |
| Tests | 2,000 | Medium | Low |

**Key challenges:**
- TypeChecker: Macro expansion engine is complex
- Interpreter: Compile-time execution
- Ensuring macro hygiene (no variable capture)

### Phase 3: Standard Library Macros
**Estimated effort**: ~10 hours (~1,800 lines)

| Module | Lines | Complexity | Risk |
|--------|-------|------------|------|
| Stdlib | 1,500 | Medium | Low |
| Tests | 500 | Low | Low |

**Key challenges:**
- Writing pattern matching helpers correctly
- Implementing AST builders
- Creating useful built-in macros

### Phase 4: Testing and Documentation
**Estimated effort**: ~12 hours (~1,200 lines)

| Module | Lines | Complexity | Risk |
|--------|-------|------------|------|
| Tests | 1,200 | Low | Low |
| Docs | N/A | Low | Low |

---

## Risk Assessment

### High Risk Areas

#### 1. TypeChecker Macro Expansion (~2,300 lines)
**Why risky:**
- TypeChecker is already very large (859 KB, 17,000 lines)
- Macro expansion touches many parts of type inference
- Compile-time execution introduces new failure modes
- Hygiene is tricky to get right

**Mitigation:**
- Create separate `MacroExpander.kt` file (don't bloat TypeChecker.kt)
- Extensive testing at each step
- Start with simple macros, add complexity gradually

#### 2. Parser Suspended Type Syntax (~400 lines)
**Why risky:**
- Parser is already large (237 KB, 5,000 lines)
- `<` token ambiguity with comparison operator
- Need careful ordering of parsers

**Mitigation:**
- Context-sensitive parsing (only in type positions)
- Extract effect parsing into reusable function
- Comprehensive parser tests

### Medium Risk Areas

#### 3. Interpreter Compile-Time Execution (~300 lines)
**Why risky:**
- New execution mode (compile-time vs runtime)
- Need to restrict certain operations
- Error handling must be clear

**Mitigation:**
- Simple flag-based approach (`isCompileTime`)
- Clear error messages
- Start with pure operations only

### Low Risk Areas

#### 4. AST Changes (~350 lines)
**Why low risk:**
- Mostly additive changes
- Well-isolated
- Straightforward serialization

#### 5. Standard Library (~1,800 lines)
**Why low risk:**
- New files, minimal modification to existing code
- Can iterate independently
- Easy to test in isolation

---

## File Organization Recommendations

To keep the codebase maintainable, consider splitting large additions:

### TypeChecker Module
```
typechecker/src/main/kotlin/com/mezze/language/typechecker/
├── TypeChecker.kt (existing, ~17,000 lines)
├── MacroExpander.kt (NEW, ~1,500 lines)
├── MacroValidation.kt (NEW, ~300 lines)
└── MacroHygiene.kt (NEW, ~300 lines)
```

**Rationale**: Keep TypeChecker.kt focused on type inference, extract macro logic.

### Test Organization
```
typechecker/src/test/kotlin/com/mezze/language/typechecker/
├── SuspendTypeTest.kt (NEW, ~300 lines)
├── MacroTypeCheckTest.kt (NEW, ~600 lines)
├── MacroExpansionTest.kt (NEW, ~600 lines)
└── MacroIntegrationTest.kt (NEW, ~500 lines)
```

---

## Estimated Timeline

Based on the implementation plan and code analysis:

| Phase | Duration | Lines Changed | Risk |
|-------|----------|---------------|------|
| Phase 1: Suspended Types | ~10 hours | ~1,150 lines | Medium |
| Phase 2: Macros | ~17 hours | ~3,300 lines | High |
| Phase 3: Stdlib | ~10 hours | ~1,800 lines | Low |
| Phase 4: Testing/Docs | ~12 hours | ~1,200 lines | Low |
| **Total** | **~49 hours** | **~7,450 lines** | |

**Note**: These are focused coding hours. Total calendar time will be longer due to:
- Testing and debugging
- Code review
- Documentation updates
- Handling unexpected issues

---

## Success Metrics

### Phase 1 Complete When:
- [ ] Can write `let x: Int <Log> = ...` in source code
- [ ] Parser accepts suspended type syntax everywhere
- [ ] Type checker correctly infers suspended types
- [ ] All 500+ new tests pass
- [ ] Bug in `applySubstitution` is fixed

### Phase 2 Complete When:
- [ ] Can declare macros: `macro list: {tokens} -> Expr (List 'a) = ...`
- [ ] Can call macros: `list!(1, 2, 3)`
- [ ] Macro expansion works correctly
- [ ] Generated code is type-checked
- [ ] Compile-time execution works
- [ ] All 2,000+ new tests pass

### Phase 3 Complete When:
- [ ] Built-in macros work: `list!`, `map!`, `dbg!`, `assert_eq!`
- [ ] Pattern matching helpers available
- [ ] AST builder functions available
- [ ] DSL support (sql!, html!) works
- [ ] All 500+ new tests pass

### Phase 4 Complete When:
- [ ] Comprehensive test suite passes (3,000+ total tests)
- [ ] All documentation updated
- [ ] Example code works end-to-end
- [ ] Performance is acceptable

---

## Conclusion

The macro system implementation is **substantial but manageable**:

✅ **Positive factors:**
- Clear, well-documented design
- Phased approach reduces risk
- AST already has `SuspendType` (foundation exists)
- Most changes are additive (new files)

⚠️ **Challenges:**
- TypeChecker is already very large
- Macro expansion is complex
- Need careful testing at each step
- Compile-time execution is new territory

**Recommendation**:
- Proceed with Phase 1 (Suspended Types) first
- Validate approach with working examples before Phase 2
- Consider extracting macro logic into separate files
- Invest heavily in testing (3,000+ lines of tests is appropriate)

The ~7,500 lines of new/modified code represents roughly **20% increase** to the core compiler (AST, Parser, TypeChecker combined currently ~28,000 lines). This is significant but reasonable for a major new feature.
