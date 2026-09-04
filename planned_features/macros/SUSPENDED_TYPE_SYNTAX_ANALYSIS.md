# Analysis: Adding Suspended Type Syntax for Macros

## Goal
Allow suspended types in type annotations so we can write:
```mezze
# In macro signatures
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...

# And potentially in regular code
let x: Int <Log> = ...
```

## Current State

### AST (ALREADY EXISTS)
```kotlin
// MezzeAST.kt:523-549
data class SuspendType(
    val valueType: MezzeType,
    val effects: EffectRow
) : MezzeType
```
✅ **AST is ready**

### Type Checker (ALREADY USES IT)
The type checker creates `SuspendType` internally:
- When inferring types of effectful expressions
- When handling perform blocks
- 15+ locations in TypeChecker.kt

✅ **Type checker is ready**

### Parser (MISSING SUPPORT)
**NO `SuspendTypeAnnotation` exists.**

Current type annotations:
```kotlin
sealed interface TypeAnnotation
- IdentifierTypeAnnotation        // Int, String, 'a
- TypeApplicationAnnotation       // List Int, Option String
- RecordTypeAnnotation            // {x: Int, y: Int}
- VariantTypeAnnotation           // Red | Green | Blue
- FunctionTypeAnnotation          // {} -> Int <Log>  ← effects ONLY here!
- ArrayTypeAnnotation             // [Int]
- EffectRowAnnotation             // <Log, FileSystem>
```

**Problem**: Effects only allowed in `FunctionTypeAnnotation`, not standalone!

## Required Changes

### Change 1: Add SuspendTypeAnnotation to AST

```kotlin
// In MezzeAST.kt, add new type annotation:
@Serializable
data class SuspendTypeAnnotation(
    val valueType: TypeAnnotation,
    val effects: EffectRowAnnotation
) : TypeAnnotation
```

### Change 2: Update Parser to Allow <effects> on Any Type

Currently (MezzeParser.kt:4070-4129):
```kotlin
// Effects parsed ONLY after arrow in function types
paramType -> returnType <effects>
```

Need to support:
```kotlin
// Effects on any type
primaryType <effects>

// Examples:
Int <Log>
List String <FileSystem>
{x: Int} <State>
```

**Parser changes needed**:

1. **Allow effects after any `primaryTypeAnnotation`**:
```kotlin
// New parser combinator
val suspendedTypeAnnotation: Parser<TypeAnnotation> = { stream ->
    // Parse base type
    val baseType = primaryTypeAnnotation(stream)

    when (baseType) {
        is ParseResult.Success -> {
            val afterType = skipNewlines(baseType.remaining)
            val ltToken = afterType.current()

            if (ltToken?.type == TokenType.LT) {
                // Parse effect row <Effect1, Effect2, ...>
                val effectRow = parseEffectRow(afterType)
                when (effectRow) {
                    is ParseResult.Success ->
                        ParseResult.Success(
                            SuspendTypeAnnotation(
                                baseType.value,
                                effectRow.value
                            ),
                            effectRow.remaining
                        )
                    is ParseResult.Failure -> baseType  // No effects, return base type
                }
            } else {
                baseType  // No effects, return base type
            }
        }
        is ParseResult.Failure -> baseType
    }
}
```

2. **Update `typeAnnotationParser` to use `suspendedTypeAnnotation`**

3. **Keep backward compatibility** - `{}  -> Int <Log>` still works

### Change 3: Update convertTypeAnnotation

```kotlin
// In MezzeAST.kt, add conversion case:
fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
    return when (annotation) {
        // ... existing cases ...

        is SuspendTypeAnnotation -> {
            val valueType = convertTypeAnnotation(annotation.valueType)
            val effects = MezzeType.EffectRow(annotation.effects.effects.toSet())
            MezzeType.SuspendType(valueType, effects)
        }

        // ...
    }
}
```

## Potential Breakages

### 1. **Parser Ambiguity** ⚠️

**Problem**: `<` is also comparison operator:
```mezze
let result = x < y  # Comparison, NOT effect annotation!
```

**Solution**: Parser lookahead - effects only valid in type annotation context, not expressions.

### 2. **Type Checker Assumptions** ⚠️

The type checker might assume `SuspendType` only appears in certain contexts.

**Check needed**:
```bash
# Search for places that pattern match on MezzeType
grep -r "when.*MezzeType" typechecker/src/main/kotlin
```

Look for exhaustive `when` expressions that might be missing `SuspendType` case.

### 3. **Unification** ⚠️

**Current**: Type checker creates `SuspendType` during inference.

**After change**: User-written `SuspendType` in annotations.

**Question**: Does unification handle user-provided `SuspendType`?

Example:
```mezze
# User annotates suspended type
let x: Int <Log> = compute_value {}

# Does type checker verify compute_value returns Int <Log>?
```

**Check needed**: Review unification logic in ConstraintSolver.kt

### 4. **Effect Subtyping** ⚠️

```mezze
# If user writes:
let x: Int <Log> = ...

# Can we assign:
let y: Int = x  # Should fail - unhandled effects!
let z: Int <Log, FileSystem> = x  # Should fail - missing FileSystem!
```

**Check needed**: Ensure effect row unification is correct.

## Test Cases Needed

### Valid Cases
```mezze
# 1. Simple suspended type
let x: Int <Log> = perform Log in Log.compute {}

# 2. Suspended record type
let config: {port: Int} <FileSystem> =
    perform FileSystem in readConfig {}

# 3. Suspended function return
let f: {} -> (Int <Log>) =
    {} -> perform Log in Log.value {}

# 4. Macro signature
macro log_int: {tokens: TokenStream} -> Expr (Int <Log>) = ...

# 5. Type application with effects
let items: List Int <Log> =
    perform Log in fetchItems {}
```

### Invalid Cases (Should Error)
```mezze
# 1. Unhandled effects
let x: Int = perform Log in Log.compute {}
# Error: Cannot assign Int <Log> to Int (unhandled effects)

# 2. Missing effects
let x: Int <Log, FileSystem> = perform Log in Log.value {}
# Error: Missing FileSystem effect

# 3. Extra effects
let x: Int <Log> = perform Log, FileSystem in operation {}
# Error: Extra FileSystem effect
```

## Implementation Order

1. ✅ AST already has `SuspendType`
2. ✅ Type checker already uses `SuspendType`
3. ⏳ Add `SuspendTypeAnnotation` to AST
4. ⏳ Update parser to parse `Type <Effects>` syntax
5. ⏳ Update `convertTypeAnnotation` to handle `SuspendTypeAnnotation`
6. ⏳ Test with simple cases
7. ⏳ Review type checker for exhaustive pattern matches
8. ⏳ Test effect subtyping and unification
9. ⏳ Update macro system to use `Expr (Type <Effects>)`

## Risk Assessment

**Low Risk** ✅:
- AST already supports it
- Type checker already uses it
- Additive change (doesn't break existing code)

**Medium Risk** ⚠️:
- Parser changes (need careful lookahead for `<` operator)
- Type checker might have exhaustive `when` expressions to update

**High Risk** ❌:
- None identified

## Recommendation

**Proceed with implementation**, but:
1. Start with parser changes + basic tests
2. Search for exhaustive `when` expressions in type checker
3. Test effect subtyping thoroughly
4. Keep `Expr` as simple phantom type (no compiler magic needed!)

---

This enables:
```mezze
type Expr 'a = Expression  # Simple phantom type

macro list: {tokens: TokenStream} -> Expr (List 'a <...>) = ...
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...
```

No special compiler treatment needed - `SuspendType` composes naturally with type parameters!
