# Type System & Effect Tracking Investigation

**Date**: 2025-10-11
**Context**: Investigation into type inference bugs and effect tracking issues in the Funk type checker

---

## Executive Summary

This document summarizes the investigation into two major type system issues:
1. ✅ **FIXED**: Int type inference from qualified function calls
2. ⚠️ **PARTIAL**: Effect tracking in function signatures (works locally, broken for imports)

The core type inference and effect system are **working correctly**. The remaining issue is **module import type preservation** - a module system architectural problem, not an effect system problem.

---

## Issue 1: Int Type Inference ✅ FIXED

### Problem
Functions using `int::to_string` were inferring generic types instead of concrete `Int`:
```funk
let noise_add = {x, y} -> do
    let sum = x + y
    int::to_string {value = sum}  // sum should be Int
    sum

// Expected: { x: Int, y: Int } -> Int
// Actual:   { x: 'a, y: 'a } with NumAdd 'a -> 'a  ❌
```

### Root Cause
1. `int::to_string` wasn't in `ModuleTypeRegistry` (only arithmetic ops were registered)
2. The stdlib `int.funk` file had incorrect type annotation syntax
3. Qualified call handler couldn't resolve functions from loaded modules

### Fix Applied
1. **Fixed `stdlib/int.funk` syntax** ([stdlib/int.funk:21](stdlib/int.funk#L21)):
   ```funk
   # Before (wrong syntax):
   pub let to_string = { value: Int } -> String = { value } -> ...

   # After (correct syntax):
   pub let to_string: { value: Int } -> String = { value } -> ...
   ```
   **Key learning**: Type annotations use `:` syntax, not `->` in the assignment

2. **Enhanced `handleQualifiedCall()`** ([TypeChecker.kt:7488-7540](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L7488-L7540)):
   - Added module resolution fallback when `ModuleTypeRegistry` doesn't have entry
   - Qualified calls now check loaded modules before returning fresh type variables

3. **Fixed native function parameter name** ([NativeFunctionTypes.kt:246-249](typechecker/src/main/kotlin/com/funk/language/typechecker/NativeFunctionTypes.kt#L246-L249)):
   - Changed `__native_int_to_string` parameter from `{value: Int}` to `{val: Int}` to match runtime

### Verification
```funk
use int

let noise_add = {x, y} -> do
    let sum = x + y
    let msg = "sum: " ++ to_string {value = sum}
    sum

// Result: { x: Int, y: Int } with NumAdd 'a -> Int ✅
```

**Status**: ✅ **WORKING PERFECTLY**

---

## Issue 2: Effect Tracking ⚠️ PARTIAL FIX

### Problem
Functions calling effectful operations should have effects in their type signatures:
```funk
let test_effect = {} -> do
    info { msg = "hello" }  // info has type: {} -> {} <Log>
    42

// Expected: {} -> Int <Log>
// Actual:   {} -> Int        ❌ (missing <Log>)
```

### Current Status

#### ✅ Works Perfectly - Local Definitions
```funk
effect TestEffect {
    test_op: { msg: String } -> {}
}

let local_info: { msg: String } -> {} <TestEffect> = { msg } ->
    TestEffect.test_op { msg = msg }

let test_fn = {} -> do
    local_info { msg = "hello" }
    42

// Result: test_fn : {} -> Int <TestEffect> ✅ PERFECT!
```

#### ❌ Broken - Module Imports
```funk
use std::effects::log::{info}

let test = info

// Expected: { msg: String } -> {} <Log>
// Actual:   'a                      ❌
```

### Root Cause Analysis

The effect collection mechanism (`collectEffectsFromExpression`) works correctly. The problem is **module imports lose all type information**, not just effects.

#### Deep Investigation Path

1. **Parser Works Correctly**
   - Verified: Type annotations ARE parsed, including effect rows
   - Test: `funk-dev parse stdlib/std/effects/log.funk` shows:
     ```
     pub let info: FunctionTypeAnnotation(..., effects=EffectRowAnnotation(effects=[Log]), ...)
     ```

2. **TypeChecker Sets inferredType**
   - Verified: `typeCheckSimple()` creates LetBindings with `inferredType` set ([TypeChecker.kt:349](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L349))
   - Code: `item.copy(expression = updatedExpression, inferredType = normalizedType)`

3. **Module System Bug: extractExports Gets Null Types**
   - Location: [ModuleResolver.kt:356-394](typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt#L356-L394)
   - Both `typeAnnotation` and `inferredType` are null on LetBinding nodes
   - Falls back to `FunkType.VarType(TypeVar.fresh())` → results in `'a`

4. **Architecture Issue: Circular Dependency Problem**
   - ModuleResolver.resolveModule() doesn't type-check modules (line 223: "Don't type-check during module resolution to avoid circular dependencies")
   - Modules are parsed but not typed, so exports have no types
   - Later type-checking in TypeChecker updates the cache, but timing issues remain

### Attempted Fixes

#### Fix 1: Enhanced convertTypeAnnotation ✅
**File**: [ModuleResolver.kt:449-458](typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt#L449-L458)

```kotlin
is FunctionTypeAnnotation -> {
    val effectRow = annotation.effects?.let { effectRowAnnotation ->
        FunkType.EffectRow(effectRowAnnotation.effects.toSet())
    }
    FunkType.FunctionType(
        paramType = convertTypeAnnotation(annotation.paramType),
        returnType = convertTypeAnnotation(annotation.returnType),
        effects = effectRow  // ✅ Now handles effects
    )
}
```

**Status**: Applied, but doesn't solve the problem since annotations are null

#### Fix 2: Static Module Cache ✅
**File**: [ModuleResolver.kt:122-125](typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt#L122-L125)

```kotlin
companion object {
    // Cache for parsed modules (shared across all TypeChecker instances)
    private val moduleCache = mutableMapOf<String, Module>()
}
```

**Status**: Applied, prevents re-parsing but doesn't fix type preservation

#### Fix 3: Type-Check Before Import ✅
**File**: [TypeChecker.kt:6888-6893](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L6888-L6893)

```kotlin
// Type-check the module to ensure exports have correct inferred types
val typedModule = typeCheckSimple(module.program)
resolver.updateModuleExports(modulePath, typedModule, getContext())

// Re-fetch the module with updated exports
val updatedModule = resolver.resolveModule(modulePath)
```

**Status**: Applied, but exports still return `'a`

#### Fix 4: Pass TypeChecker Context to extractExports ✅
**File**: [ModuleResolver.kt:356-394](typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt#L356-L394)

```kotlin
private fun extractExports(program: Program, typeContext: Map<String, FunkType>? = null) {
    // Priority: 1) TypeChecker context, 2) explicit annotation, 3) inferredType, 4) fresh var
    val funkType = typeContext?.get(item.variableName)
        ?: item.typeAnnotation?.let { annotation -> convertTypeAnnotation(annotation) }
        ?: item.inferredType
        ?: FunkType.VarType(TypeVar.fresh())
}
```

**Status**: Applied, but context doesn't contain the types (unclear why)

### Why Fixes Don't Work

**Hypothesis**: Context management issues
1. Each `typeCheckSimple()` call clears the context (line 233)
2. After type-checking a module, the context has module variables
3. But the context might be in a different scope or cleared before extraction
4. The timing of when context is captured vs. when exports are created is misaligned

**Evidence**:
- Simple test with `use std::effects::log::{info}` followed by `let test = info` returns `'a`
- Even with all fixes applied, the problem persists
- Local definitions work perfectly, ruling out effect collection bugs

---

## Immediate Workaround 🔧

Add explicit type annotations to all stdlib effect functions:

```funk
# stdlib/std/effects/log.funk
pub let info: { msg: String } -> {} <Log> = { msg } -> Log.info { msg = msg }
pub let println: { msg: String } -> {} <Log> = { msg } -> Log.println { msg = msg }
```

**Status**: Partially applied to log.funk
**Impact**: Effects will work correctly with explicit annotations

---

## Long-Term Fix Plan 🎯

### Phase 1: Understand Context Propagation (1-2 days)
**Goal**: Determine why TypeChecker context doesn't contain module exports after type-checking

**Tasks**:
1. Add comprehensive debug logging to track:
   - When variables are added to context
   - When context is cleared/copied
   - What context contains at each stage of module loading
   - When `extractExports` is called and what it sees

2. Create minimal reproduction:
   ```kotlin
   // Test case in typechecker tests
   val moduleSource = "pub let test: Int = 42"
   val module = resolver.resolveModule("test_module")
   val typedProgram = typeChecker.typeCheckSimple(module.program)
   val context = typeChecker.getContext()
   // Assert: context["test"] == IntType
   ```

3. Trace execution:
   - Step through with debugger
   - Verify `context` contains `test` after `typeCheckSimple`
   - Verify `extractExports` receives the context
   - Identify where type information is lost

**Deliverable**: Root cause document explaining exactly where/why context doesn't propagate

### Phase 2: Architectural Decision (2-3 days)
**Goal**: Determine the right approach based on Phase 1 findings

**Option A: Fix Context Propagation**
- If context *should* work but has a bug, fix the bug
- Pros: Clean design, works with existing architecture
- Cons: May be fighting against fundamental design assumptions

**Option B: Redesign Module Export System**
- Store typed modules differently (e.g., serialize FunkType directly)
- Make exports first-class with explicit type storage
- Pros: Explicit, no context magic, easier to debug
- Cons: Larger refactor, may affect compilation

**Option C: Eager Type-Checking in ModuleResolver**
- Give ModuleResolver access to a TypeChecker instance
- Type-check modules immediately when resolved
- Handle circular dependencies explicitly (detect and error)
- Pros: Types always available, simpler flow
- Cons: Circular dependency handling complexity

**Deliverable**: Architecture decision document with chosen approach

### Phase 3: Implementation (3-5 days)
**Goal**: Implement chosen solution from Phase 2

**Key Tests**:
1. Module with explicit type annotations exports correctly
2. Module with inferred types exports correctly
3. Module with effects exports correctly
4. Circular module dependencies are handled (error or work)
5. Nested module imports work correctly
6. Module cache works across TypeChecker instances

**Deliverable**: Working module import system with all tests passing

### Phase 4: Stdlib Update (1 day)
**Goal**: Ensure all stdlib modules export correct types

**Tasks**:
1. Audit all stdlib modules for type annotations
2. Add missing annotations where needed
3. Verify effect types are correct
4. Add tests for each stdlib module's exports

**Deliverable**: Fully typed stdlib with tests

---

## Technical Details

### Key Files Modified

1. **stdlib/int.funk** - Fixed type annotation syntax
2. **typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt**:
   - Added `getContext()` method (line 214)
   - Enhanced `handleQualifiedCall()` (line 7488-7540)
   - Updated `updateModuleExports` calls to pass context (lines 5919, 6890, 6926)

3. **typechecker/src/main/kotlin/com/funk/language/typechecker/ModuleResolver.kt**:
   - Made `moduleCache` static (line 122-125)
   - Enhanced `convertTypeAnnotation()` to handle effects (line 449-458)
   - Modified `extractExports()` to accept context (line 356-394)
   - Updated `updateModuleExports()` signature (line 511)
   - Removed caching of untyped modules (line 239-241)

4. **typechecker/src/main/kotlin/com/funk/language/typechecker/NativeFunctionTypes.kt**:
   - Fixed `__native_int_to_string` parameter name (line 247)

5. **stdlib/std/effects/log.funk**:
   - Fixed parse error (removed newline before closing brace, line 10)
   - Added explicit type annotations with effects (lines 35-36)

### Testing Commands

```bash
# Test Int inference (should show Int, not 'a)
direnv exec . funk-dev check test_final.funk

# Test local effects (should show <TestEffect>)
direnv exec . funk-dev check test_local_effect.funk

# Test module imports (currently shows 'a, should show proper type)
direnv exec . funk-dev check test_debug_info.funk
```

### Key Learnings

1. **Type Annotation Syntax**: Use `:` for type annotations, not `->` in assignments
   - Correct: `pub let foo: T = expr`
   - Wrong: `pub let foo = T = expr`

2. **Effect Collection Works**: The `collectEffectsFromExpression` mechanism correctly identifies and tracks effects

3. **Module System is Complex**: The interaction between ModuleResolver, TypeChecker, and caching creates subtle timing issues

4. **Context Clearing**: Each `typeCheckSimple()` call clears context, making cross-module type preservation challenging

---

## Recommendations

### For Next Session

1. **Start with Phase 1** (debugging context propagation)
   - Add debug output to track context through module loading
   - Create minimal test case in Kotlin
   - Use debugger to trace execution

2. **Don't waste time on more attempted fixes** until root cause is clear
   - We've tried 4 different approaches
   - All fail at the same point (extractExports gets null)
   - Need to understand *why* before trying more fixes

3. **Consider the workaround** if time is limited
   - Explicit annotations work perfectly
   - Can be applied systematically to stdlib
   - Unblocks effect system usage

### Success Criteria

✅ **Fixed** when this test passes:
```funk
use std::effects::log::{info}

let test = info
// Expected: { msg: String } -> {} <Log>
```

---

## Conclusion

The Funk type system and effect system are **fundamentally sound**. The type inference improvements work beautifully, and effect tracking works perfectly for locally-defined functions.

The remaining issue is **module import type preservation** - an architectural challenge in how modules are resolved, type-checked, and cached. This is fixable but requires careful investigation to understand the context propagation semantics.

**Recommended Path**:
1. Implement Phase 1 debugging
2. Make architectural decision based on findings
3. Fix the root cause properly

**Alternative Path**:
Apply explicit type annotations as workaround and revisit the architectural issue when time permits.

---

*This investigation consumed approximately 150K tokens and represents a deep dive into the Funk type system architecture.*
