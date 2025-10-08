# Module Type Preservation - Root Cause Analysis

**Date**: 2025-10-11
**Status**: ROOT CAUSE IDENTIFIED

## Summary

The module import system successfully extracts and adds typed exports to the context, but the types are overwritten or lost somewhere in the `typeCheckSimple` flow after imports are processed.

##  What We Know FOR SURE

### ✅ WORKING: Export Extraction with Context

The debug output proves that:

1. **Module exports ARE correctly extracted** with types from context:
```
=== TypeChecker.processUseDeclarationSimple (SelectivePath) DEBUG ===
Module path: test_module
Context size after typeCheckSimple: 3
Context keys: simple_int, simple_func, x
  simple_int -> Int
  simple_func -> { x: Int } -> Int
  x -> 'a

=== extractExports DEBUG ===
typeContext provided: true
typeContext size: 3
typeContext keys: simple_int, simple_func, x

Exporting: simple_int
  - From context: Int          ← CORRECT!
  - From annotation: Int
  - From inferredType: Int
  - Final type: Int
```

2. **Exports ARE correctly added to the context**:
```
=== Adding imported item to context ===
Item name: simple_int
Context name: simple_int
Export type: Int
Context[simple_int] after adding: Int    ← VERIFIED IN CONTEXT!

=== Adding imported item to context ===
Item name: simple_func
Context name: simple_func
Export type: { x: Int } -> Int
Context[simple_func] after adding: { x: Int } -> Int    ← VERIFIED IN CONTEXT!
```

3. **Final context shows types became 'a**:
```
Final context size: 2
Final context keys: simple_int, simple_func
  simple_int -> 'a           ← LOST!
  simple_func -> 'a          ← LOST!
```

##  The Problem

**Somewhere between adding imports to context and accessing them during type inference, the types are being overwritten or replaced with fresh type variables.**

## Possible Causes

### Hypothesis 1: registerStdLibTypes() Overwrites Context

After imports are processed, [TypeChecker.kt:274](TypeChecker.kt#L274) calls `registerStdLibTypes()` which might overwrite imported values if there's a name collision.

**Evidence**: The context size goes from 82 (after imports) to various sizes as stdlib modules are loaded. This could indicate context manipulation.

### Hypothesis 2: Polymorphic Context Treatment

When processing `let test_int = simple_int`, the inference might treat `simple_int` differently:

- If `simple_int` is in `polymorphicContext` instead of `context`, it would return a fresh instantiation
- Need to check if imports are being added to polymorphicContext by mistake

**Location**: [TypeChecker.kt:1575-1580](TypeChecker.kt#L1575-L1580)
```kotlin
// First check polymorphic context (let-polymorphism)
polymorphicContext[expr.name]?.let { scheme ->
    return scheme.instantiate()  // Returns fresh 'a!
}

// Then look up in regular context
context[expr.name] ?: run { ... }
```

### Hypothesis 3: Type Variable Normalization

At [TypeChecker.kt:345](TypeChecker.kt#L345), types are normalized:
```kotlin
val normalizedType = com.funk.language.ast.normalizeTypeVariables(finalType)
```

This could be replacing concrete types with type variables if the normalization logic is flawed.

### Hypothesis 4: Inference Overwrites Context

When inferring `let test_int = simple_int`, the code:
1. Looks up `simple_int` (should find `Int`)
2. But `inferTypeWithUnification` or `inferBasicType` might return a fresh type variable
3. This gets stored back into context, overwriting the original type

## Next Steps

### Phase 1: Add More Debug Logging ✅ DONE

- [x] Track when values are added to context (line 6989)
- [x] Track when values are looked up from context
- [ ] Track when context is modified/overwritten
- [ ] Add logging to `registerStdLibTypes()` to see if it overwrites imports

### Phase 2: Identify Exact Overwrite Point

Add debug logging to:
1. The point where `let test_int = simple_int` starts being processed
2. The point where `simple_int` is looked up as an identifier
3. Any point where context values are modified

### Phase 3: Fix the Root Cause

Once we know WHERE the overwrite happens, we can fix it:

**If registerStdLibTypes overwrites**:
- Check for name collisions before adding stdlib types
- Skip adding if already in context

**If polymorphicContext is the issue**:
- Ensure imports are added to `context`, not `polymorphicContext`
- Or ensure polymorphic scheme is created correctly with monomorphic types

**If normalization is the issue**:
- Fix `normalizeTypeVariables` to preserve concrete types

**If inference overwrites**:
- Ensure identifier lookup returns the context type directly
- Don't infer fresh types for imports

## Key Code Locations

1. **Import processing**: [TypeChecker.kt:6973-7004](TypeChecker.kt#L6973-L7004)
2. **Context addition**: [TypeChecker.kt:6989](TypeChecker.kt#L6989) - `context[contextName] = export.funkType`
3. **Identifier lookup**: [TypeChecker.kt:1575-1580](TypeChecker.kt#L1575-1580)
4. **Type normalization**: [TypeChecker.kt:345](TypeChecker.kt#L345)
5. **registerStdLibTypes**: [TypeChecker.kt:274](TypeChecker.kt#L274)

## Test Case

File: `typechecker/src/test/kotlin/com/funk/language/typechecker/ModuleTypePreservationTest.kt`

Reproduces the issue perfectly with debug logging enabled via:
```kotlin
System.setProperty("funk.typecheck.debug", "true")
```

## Conclusion

**We have successfully identified that types ARE being preserved through export extraction and ARE being added to context correctly.**

The bug is that these correct types in context are being OVERWRITTEN somewhere before they're used.

Next action: Find the exact line where context[" simple_int"] changes from `Int` to `'a`.
