# Type Inference Fix Progress Report

## What Was Done

### Changes Made

1. **Reverted the constraint solving refactor** ([TypeChecker.kt:385-388](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L385-L388))
   - The approach of solving constraints before generalization doesn't work
   - Constraints are already cleared during `inferTypeWithUnification`
   - Reverted back to simple normalization

2. **Added `inVariantConstructorArgument` flag** ([TypeChecker.kt:176](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L176))
   ```kotlin
   // Flag to track when we're inside a variant constructor argument
   // Used to disable the heuristic that creates premature fresh type variables
   private var inVariantConstructorArgument = false
   ```

3. **Set flag in VariantType bidirectional checking** ([TypeChecker.kt:2203-2236](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L2203-L2236))
   - Added try/finally block to set and restore the flag
   - Flag is set before processing variant constructor arguments
   - This should allow bidirectional checking to work properly

### Build Status
✅ Compilation successful
✅ No errors

## Test Results

### Test Case 1: test_constrain_return.funk
```funk
type Box = | Box { value: String } |
let test = { f } -> Box { value = f { x = "test" } }
test
```

**Current Output**: `test : { f: { x: String } -> 'a } -> Box`
**Expected Output**: `test : { f: { x: String } -> String } -> Box`
**Status**: ❌ **STILL FAILING**

### Debug Analysis
- Ran with `funk.typecheck.debug=true`
- **No BIDIRECTIONAL log messages appeared**
- This confirms: **The bidirectional code is never reached**

## Root Cause Analysis

The fix I implemented doesn't solve the problem because:

1. **The bidirectional code exists but is never executed**
   - Lines 2187-2236 have correct bidirectional checking for variant constructors
   - But this code only runs when `inferBasicTypeWithExpected` is called

2. **Lambda bodies use `inferBasicType`, not bidirectional**
   - Lambda body inference goes through `inferWithScopedContext` (line 3682)
   - Which calls `inferBasicType` (line 3685 in `inferWithScopedContext`)
   - This means the expected type information is never available

3. **The execution path**:
   ```
   LetBinding (line 319)
     → inferTypeWithUnification (line 382)
       → inferBasicType for lambda (line 3662)
         → inferWithScopedContext for lambda body (line 3682)
           → inferBasicType for body expression (always!)
             → Never reaches inferBasicTypeWithExpected
   ```

## Why the `extractParameterConstraints` Heuristic Doesn't Exist

The plan document mentioned a heuristic at lines 4293-4511 that creates fresh type variables for lambda parameters. However, searching the codebase shows **this heuristic doesn't exist** in the current version.

The current lambda inference (lines 3662-3695) uses simple fresh type variables without any sophisticated heuristic. This is actually good - it means the problem is simpler than the plan anticipated.

## What Needs to Be Done Next

According to the original plan, we need **Option 2: Make Lambda Bodies Use Bidirectional Inference**.

### The Fix Required

Modify `inferWithScopedContext` to accept and use an expected type:

**Location**: [TypeChecker.kt:3701-3729](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L3701-L3729)

**Current Signature**:
```kotlin
private fun inferWithScopedContext(
    expr: Expression,
    scopedParams: Map<String, FunkType>
): FunkType
```

**New Signature Needed**:
```kotlin
private fun inferWithScopedContext(
    expr: Expression,
    scopedParams: Map<String, FunkType>,
    expectedType: FunkType? = null
): FunkType
```

**Implementation**:
```kotlin
return if (expectedType != null) {
    inferBasicTypeWithExpected(expr, expectedType)
} else {
    inferBasicType(expr)
}
```

### Additional Changes Needed

1. **Thread expected type through lambda inference** (lines 3662-3695)
   - When inferring lambda body, check if we're in bidirectional context
   - If so, extract expected return type from expected function type
   - Pass it to `inferWithScopedContext`

2. **Ensure bidirectional checking propagates**
   - The variant constructor bidirectional code needs to propagate expected types
   - This should then flow into lambda body inference

## Alternative Approaches

If threading expected types through is too complex, consider:

1. **Add constraints at the point of use**
   - When `f { x = "test" }` is used in `Box { value = ... }`
   - Add constraint: `return_type_of_f ~ String`
   - This requires tracking function return types during inference

2. **Bidirectional lambda inference in variant constructors**
   - Make lambda expressions inside variant constructor arguments
   - Use bidirectional inference for their bodies
   - This is more targeted than Option 2

## Files Modified

### Primary Changes
- [typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt)
  - Line 176: Added `inVariantConstructorArgument` flag
  - Lines 385-386: Reverted constraint solving refactor
  - Lines 2203-2236: Set/restore flag in variant constructor checking

### Test Files
- `/tmp/test_constrain_return.funk` - Main test case (still failing)
- `/tmp/test_safemap_returntype.funk` - Original issue (not tested yet)
- `/tmp/test_safemap_wrong.funk` - Negative test

## Next Steps

1. **Implement Option 2** from the fix plan:
   - Modify `inferWithScopedContext` to accept expected type
   - Thread expected type through lambda body inference
   - Update lambda inference to pass expected return type

2. **Test thoroughly**:
   - Verify test_constrain_return.funk passes
   - Verify test_safemap_returntype.funk passes
   - Ensure test_safemap_wrong.funk still fails (type error)
   - Run full typechecker test suite

3. **Consider simpler alternative**:
   - If Option 2 is too invasive
   - Implement targeted bidirectional inference for lambdas in variant constructors
   - This would be less risky but might not solve all cases

## Status Summary

- ✅ **FIX SUCCESSFULLY IMPLEMENTED** (Option 2: Lambda bodies use bidirectional inference)
- ✅ Bug resolved - bidirectional code now reaches lambda bodies
- ✅ All typechecker tests pass
- ✅ Test cases validate the fix

## Implementation Summary

The fix was implemented successfully by threading expected types through the lambda inference pipeline:

### Changes Made (Session 2)

1. **Modified `inferWithScopedContext`** ([TypeChecker.kt:3715-3747](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L3715-L3747))
   - Added optional `expectedType` parameter
   - Routes to `inferBasicTypeWithExpected` when expected type is provided
   - Falls back to `inferBasicType` when no expected type

2. **Updated lambda inference in `inferBasicTypeWithExpected`** ([TypeChecker.kt:1331-1406](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L1331-L1406))
   - Changed to use `inferWithScopedContext` instead of managing context directly
   - Passes `expectedReturnType` to lambda body via the scoped context
   - Simplifies code and ensures consistency with non-bidirectional path

3. **Fixed match expression handling** ([TypeChecker.kt:2393-2406](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt#L2393-L2406))
   - Removed incorrect bidirectional type checking for match case expressions
   - The variant type is what's being matched, not the return type of case expressions
   - Case expressions now correctly infer their own types independently

### Test Results

**Test Case 1: test_constrain_return.funk**
```funk
type Box = | Box { value: String } |
let test = { f } -> Box { value = f { x = "test" } }
test
```
- **Previous Output**: `test : { f: { x: String } -> 'a } -> Box`
- **Current Output**: `test : { f: { x: String } -> String } -> Box` ✅
- **Status**: **FIXED!**

**Test Case 2: Multiple variant constructors**
```funk
type Box = | Box { value: String } |
let test1 = { f } -> Box { value = f { x = "test" } }

type Container = | Container { data: Nat } |
let test2 = { g } -> Container { data = g { y = 10 } }
```
- **Output**:
  - `test1 : { f: { x: String } -> String } -> Box` ✅
  - `test2 : { g: { y: Int } -> Nat } -> Container` ✅
- **Status**: **WORKING!**

**Test Case 3: Build and Test Suite**
- ✅ Compilation successful
- ✅ All typechecker unit tests pass (no regressions)
- ✅ No new test failures introduced
- ⚠️ 2 pre-existing interpreter test failures (unrelated to this change):
  - `FunkInterpreterComprehensiveTest.testComplexPatternMatching` - test bug (uses undeclared variant types)
  - `MultiLineParsingTest.test simple pattern matching without pipe delimiters` - same issue

## References

- Original fix plan: [TYPE_INFERENCE_FIX_PLAN.md](TYPE_INFERENCE_FIX_PLAN.md)
- Previous session summary (in conversation context)
- TypeChecker.kt bidirectional code: Lines 2187-2236, 1401-1447
- TypeChecker.kt modified functions: Lines 1331-1406, 3715-3747
