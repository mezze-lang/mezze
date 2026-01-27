# TypeChecker Size Reduction Plan

## Current Status

**TypeChecker.kt: 18,245 lines** ⚠️ STILL TOO BIG

While we've made architectural progress (engines extracted, delegation pattern established), **the file is still massive** because we haven't actually removed the bulk of the code.

## The Problem

We've been:
- ✅ Extracting code to engines
- ✅ Creating delegation wrappers
- ❌ **NOT removing the massive functions still in TypeChecker**

## Where The Lines Are

### 🔴 Critical: The Monster Functions

| Function | Lines | % of File | Status |
|----------|-------|-----------|--------|
| `inferBasicType` | **3,255** | **18%** | ❌ Still in TypeChecker |
| `validateAbilityConstraintsAtCallSite` | **175** | 1% | ❌ Still in TypeChecker |
| `inferBasicTypeWithExpected` | **~650** | 3.5% | ❌ Still in TypeChecker |
| `setInferredTypesWithType` | **~500** | 2.7% | ❌ Still in TypeChecker |
| `typeCheckSimple` | **~450** | 2.5% | ❌ Still in TypeChecker |

**Just these 5 functions = 5,030 lines (27.6% of the file!)**

## Action Plan to Actually Reduce Size

### Phase 1: Break Down `inferBasicType` (Target: -2,000 lines)

**Problem:** 3,255-line when-expression with 50+ cases

**Solution:** Extract each expression type case to its own function in InferenceEngine

```kotlin
// BEFORE (in TypeChecker):
private fun inferBasicType(expr: Expression): FunkType {
    return when (expr) {
        is IntLiteral -> FunkType.IntType
        is StringLiteral -> { /* 50 lines */ }
        is FunctionCall -> { /* 200 lines */ }
        is LambdaExpression -> { /* 300 lines */ }
        is MatchExpression -> { /* 250 lines */ }
        // ... 45 more cases, 2500+ more lines
    }
}

// AFTER (delegate to InferenceEngine):
private fun inferBasicType(expr: Expression): FunkType {
    return inferenceEngine.inferType(expr)
}

// In InferenceEngine:
override fun inferType(expr: Expression): FunkType {
    return when (expr) {
        is IntLiteral -> inferIntLiteral(expr)
        is StringLiteral -> inferStringLiteral(expr)
        is FunctionCall -> inferFunctionCall(expr)
        is LambdaExpression -> inferLambda(expr)
        is MatchExpression -> inferMatch(expr)
        // ...
    }
}

private fun inferIntLiteral(expr: IntLiteral) = FunkType.IntType
private fun inferStringLiteral(expr: StringLiteral): FunkType { /* extracted 50 lines */ }
private fun inferFunctionCall(expr: FunctionCall): FunkType { /* extracted 200 lines */ }
// etc.
```

**Impact:** Remove ~2,000 lines from TypeChecker, add to InferenceEngine

### Phase 2: Extract Validation Functions (Target: -1,500 lines)

**Functions to Extract:**

1. **`validateAbilityConstraintsAtCallSite`** (175 lines)
   - Move to ValidationEngine
   - Already have the interface defined

2. **`checkMatchExhaustivenessAndWildcards`** (~200 lines)
   - Pattern exhaustiveness checking
   - Move to ValidationEngine

3. **`validatePerformerImplementation`** (~150 lines)
   - Effect performer validation
   - Move to ValidationEngine

4. **`validateAbilityImplementation`** (~120 lines)
   - Ability implementation validation
   - Move to ValidationEngine

5. **Other validation functions** (~855 lines total)
   - See REFACTORING_PROGRESS.md for full list

**Impact:** Remove ~1,500 lines from TypeChecker

### Phase 3: Extract Large Helper Functions (Target: -800 lines)

1. **`setInferredTypesWithType`** (~500 lines)
   - AST traversal to set inferred types
   - Could be a separate utility class

2. **`extractPatternBindings`** (~150 lines)
   - Pattern variable extraction
   - Move to pattern matching utility

3. **`collectAbilityImplementations`** (~150 lines)
   - Collect ability impls from program
   - Move to ResolutionEngine or utility

**Impact:** Remove ~800 lines from TypeChecker

### Phase 4: Extract Inference Helpers (Target: -600 lines)

1. **`inferLambdaBidirectionally`** (~400 lines)
   - Complex lambda inference
   - Move to InferenceEngine

2. **`inferParameterTypesFromUsage`** (~200 lines)
   - Parameter type inference from body
   - Move to InferenceEngine

**Impact:** Remove ~600 lines from TypeChecker

## Summary of Reductions

| Phase | Target Lines | % of File | Difficulty |
|-------|--------------|-----------|------------|
| Phase 1: Break down `inferBasicType` | -2,000 | -11% | ⭐⭐⭐ High |
| Phase 2: Extract validation | -1,500 | -8% | ⭐⭐ Medium |
| Phase 3: Extract helpers | -800 | -4% | ⭐ Low |
| Phase 4: Extract inference | -600 | -3% | ⭐⭐ Medium |
| **TOTAL** | **-4,900** | **-27%** | |

**Result: TypeChecker would go from 18,245 → ~13,345 lines**

## Why We Haven't Done This Yet

### Challenge 1: Complex Dependencies
`inferBasicType` calls many TypeChecker private methods. Extracting it requires extracting those too.

### Challenge 2: Shared Mutable State
Functions modify TypeChecker's `context`, `constraints`, etc. Need careful state management.

### Challenge 3: Test Complexity
Each extraction needs validation that all 1,778 tests still pass.

### Challenge 4: It's Just Hard
Breaking down a 3,255-line function is legitimately difficult work.

## Practical Next Steps

### Immediate (Can Do Now)

1. **Extract simple validation functions** (Phase 2)
   - Start with self-contained validators
   - Less dependencies than inference functions
   - Immediate size reduction

2. **Extract helper utilities** (Phase 3)
   - `setInferredTypesWithType`, `extractPatternBindings`
   - Create InferenceUtilities.kt
   - Clear win for organization

### Medium Term (Requires Planning)

3. **Break down `inferBasicType` incrementally** (Phase 1)
   - Extract one expression type at a time
   - Start with simple ones (literals, identifiers)
   - Gradually move to complex ones (lambdas, matches)
   - Test after each extraction

4. **Extract remaining inference functions** (Phase 4)
   - Lambda inference
   - Pattern inference
   - After `inferBasicType` is smaller

### Long Term (Final Cleanup)

5. **Remove dead code**
   - Functions that are no longer called
   - Commented-out code
   - Debug statements

6. **Consolidate duplicates**
   - Find remaining duplicate helper functions
   - Use engine versions everywhere

## Recommended Approach

**Start with Phase 2 & 3 (low-hanging fruit):**

```kotlin
// 1. Extract validateAbilityConstraintsAtCallSite to ValidationEngine
// 2. Extract checkMatchExhaustivenessAndWildcards to ValidationEngine
// 3. Extract setInferredTypesWithType to InferenceUtilities
// 4. Extract extractPatternBindings to PatternUtilities
```

**Target: Remove ~2,300 lines in these 4 extractions**

This gives us:
- ✅ Immediate size reduction (18,245 → 15,945)
- ✅ Better organization
- ✅ Builds momentum for tackling `inferBasicType`

Then tackle `inferBasicType` piece by piece.

## Metric to Watch

**Target: TypeChecker under 10,000 lines**

Current: 18,245 lines
After Phase 2 & 3: ~15,945 lines (-13%)
After Phase 1: ~13,945 lines (-24%)
After Phase 4: ~13,345 lines (-27%)
After final cleanup: **~10,000 lines** (-45%) ✅

## Files That Need Creation

1. `engines/InferenceUtilities.kt` - AST traversal, type setting
2. `engines/PatternUtilities.kt` - Pattern matching helpers
3. `engines/ValidationUtilities.kt` - Validation helpers

## Bottom Line

**We've built the architecture. Now we need to USE it by actually MOVING the code.**

The engines exist. The interfaces exist. TypeContext exists.

**What's missing: Actually removing the 5,000+ lines of code that should be in the engines, not TypeChecker.**

---

Created: 2026-01-27
Status: TypeChecker is 18,245 lines - needs to be < 10,000
Priority: HIGH - This is the actual refactoring work
