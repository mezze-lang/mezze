# Type Inference Fix Plan: Premature Generalization Bug

## Problem Statement

Functions that return values constrained by variant constructor field types incorrectly infer polymorphic return types instead of concrete types.

### Test Case
```funk
type Box = | Box { value: String } |

let test = { f } -> Box { value = f { x = "test" } }
```

**Current (Incorrect)**: `test : { f: { x: String } -> 'a } -> Box`
**Expected (Correct)**: `test : { f: { x: String } -> String } -> Box`

The return type of `f` should be inferred as `String` because it's used in `Box { value = ... }` where `value` expects `String`.

## Root Cause Analysis

### The Generalization Timing Problem

The type checker has a fundamental ordering issue in LetBinding processing ([TypeChecker.kt:319-429](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt)):

1. **Line 382**: `inferTypeWithUnification(item.expression)` infers the type
   - During inference, `extractParameterConstraints` (line 4410) creates fresh type variables
   - For parameter `f`, it creates: `f: { x: String } -> 'b` where `'b = TypeVar.fresh()`

2. **Line 387-388**: Constraints are solved and substitutions applied
   - However, `inferTypeWithUnification` already solved and cleared constraints internally (line 786)
   - The constraints added during lambda body inference are gone

3. **Line 394**: `getFreeTypeVariables(normalizedType)` finds `'b` is still free

4. **Line 405**: `generalize(normalizedType)` creates polymorphic type `∀b. { f: { x: String } -> b } -> Box`

### Why Bidirectional Checking Doesn't Help

Even though we added bidirectional checking for variant constructors:
- [Line 2187-2220](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt): Variant constructor bidirectional code
- [Line 1401-1447](typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt): FunctionCall bidirectional code

**The bidirectional code is never reached** because:
1. Lambda body `Box { value = f { x = "test" } }` is inferred via `inferBasicType` (line 3632)
2. Which calls `inferWithScopedContext` (line 3652)
3. Which calls `inferBasicType` again (line 3685)
4. **Never enters `inferBasicTypeWithExpected`** where our bidirectional code lives

### The Heuristic Problem

The heuristic `extractParameterConstraints` (line 4293-4511):
- Runs BEFORE type inference
- Pre-analyzes lambda body to extract parameter type constraints
- Creates fresh type variables for unknown types
- These fresh variables "poison" the inference before bidirectional info is available

## Solution Plan

### Option 1: Disable Heuristic for Variant Constructor Arguments (Recommended)

**Complexity**: Medium
**Risk**: Low (scoped change)
**Files**: `TypeChecker.kt`

#### Steps:

1. **Add context tracking for variant constructor checking**
   - Location: TypeChecker class (add field ~line 170)
   - Add: `private var inVariantConstructorArgument = false`

2. **Set flag when entering variant constructor argument inference**
   - Location: VariantType case in FunctionCall (~line 2187)
   - Before calling `inferBasicTypeWithExpected`, set: `inVariantConstructorArgument = true`
   - After completion, reset: `inVariantConstructorArgument = false`

3. **Skip heuristic when flag is set**
   - Location: Lambda inference in `inferBasicType` (~line 3632)
   - Check: `if (!inVariantConstructorArgument)` before calling `extractParameterConstraints`
   - If skipped, use simple fresh type variables without heuristic analysis

4. **Ensure bidirectional checking works in variant case**
   - Location: Already done at line 2187-2220
   - Verify the `inferBasicTypeWithExpected` calls properly propagate expected types

#### Implementation Details:

```kotlin
// Step 1: Add to TypeChecker class
private var inVariantConstructorArgument = false

// Step 2: In VariantType case (~line 2187)
if (expectedDataType is com.funk.language.ast.FunkType.RecordType &&
    expr.arguments is RecordLiteral) {

    val argumentRecord = expr.arguments as RecordLiteral
    val previousFlag = inVariantConstructorArgument
    inVariantConstructorArgument = true
    try {
        for (entry in argumentRecord.getFieldEntries()) {
            // ... existing bidirectional checking code
        }
    } finally {
        inVariantConstructorArgument = previousFlag
    }
}

// Step 3: In Lambda case (~line 3632)
is LambdaExpression -> {
    val paramTypes = when (val params = expr.parameters) {
        is UntypedParameters -> {
            // Skip heuristic when in variant constructor
            if (!inVariantConstructorArgument) {
                val heuristicConstraints = extractParameterConstraints(
                    params.names,
                    expr.body
                )
                // Use heuristic-derived types
                params.names.associateWith { name ->
                    heuristicConstraints[name] ?:
                    com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                }
            } else {
                // Use simple fresh variables without heuristic
                params.names.associateWith {
                    com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                }
            }
        }
        // ... rest of code
    }
}
```

#### Testing:

1. Test the original case:
   ```funk
   type Box = | Box { value: String } |
   let test = { f } -> Box { value = f { x = "test" } }
   ```
   Should infer: `{ f: { x: String } -> String } -> Box`

2. Test safeMap:
   ```funk
   type Result = | Success { value: String }, Failure { error: String } |
   let safeMap = { f, result } -> match result is
       Success { value } -> Success { value = f { x = value } }
       Failure { error } -> Failure { error = error }
   ```
   Should infer: `{ f: { x: String } -> String, result: Result } -> Result`

3. Verify heuristic still works for non-variant cases:
   ```funk
   let test = { f } -> f { x = 5 }
   ```
   Should still infer: `{ f: { x: Int } -> 'a } -> 'a`

---

### Option 2: Make Lambda Bodies Use Bidirectional Inference

**Complexity**: High
**Risk**: Medium (affects all lambda inference)
**Files**: `TypeChecker.kt`

#### Steps:

1. **Thread expected type through `inferWithScopedContext`**
   - Location: ~line 3673
   - Change signature: `inferWithScopedContext(expr: Expression, scopedParams: Map<String, FunkType>, expectedType: FunkType? = null)`
   - If `expectedType` is provided, call `inferBasicTypeWithExpected` instead of `inferBasicType`

2. **Pass expected type from variant constructor**
   - Location: VariantType case (~line 2187)
   - When calling bidirectional inference for field values, the expected type is already known

3. **Update lambda inference to accept expected type**
   - Location: Lambda in `inferBasicType` (~line 3632)
   - Check if we're in a context with an expected function type
   - If so, extract expected return type and pass to body inference

#### Notes:
- More invasive change
- Requires careful handling of all lambda inference paths
- May break existing code that relies on current inference behavior

---

### Option 3: Solve Constraints After All Inference (Major Refactor)

**Complexity**: Very High
**Risk**: High (architectural change)
**Files**: `TypeChecker.kt`, potentially others

#### Concept:

Restructure type checking to use a **constraint accumulation** phase followed by a **solving phase**:

1. **Phase 1: Inference with Constraint Accumulation**
   - Infer all expressions
   - Accumulate ALL constraints without solving
   - Don't clear constraints after each expression

2. **Phase 2: Global Constraint Solving**
   - After all inference is complete
   - Solve ALL accumulated constraints at once
   - Generate substitution map

3. **Phase 3: Application & Generalization**
   - Apply substitutions to all inferred types
   - THEN perform let-polymorphism generalization
   - Only free variables that survived constraint solving get quantified

#### Why This is Hard:

- Requires threading constraint list through entire type checker
- Current code solves constraints eagerly (line 786 clears them)
- Many places assume constraints are solved immediately
- Would need to track constraint scopes for proper error messages

---

## Recommendation

**Use Option 1: Disable Heuristic for Variant Constructor Arguments**

### Rationale:
1. **Scoped change**: Only affects variant constructor argument checking
2. **Low risk**: Doesn't change core inference algorithm
3. **Solves the specific problem**: The heuristic is the root cause
4. **Maintains existing behavior**: Heuristic still works for other cases
5. **Easy to test**: Can validate fix without extensive regression testing

### Expected Outcome:
- Variant constructor arguments will use bidirectional checking properly
- Fresh type variables will get constrained by expected types
- Let-polymorphism will only generalize truly free variables
- Original test case will pass

---

## Implementation Checklist

- [ ] Step 1: Add `inVariantConstructorArgument` flag to TypeChecker class
- [ ] Step 2: Set flag in VariantType case before bidirectional checking
- [ ] Step 3: Check flag in Lambda inference and skip heuristic if set
- [ ] Step 4: Build and test with `/tmp/test_constrain_return.funk`
- [ ] Step 5: Test with `/tmp/test_safemap_returntype.funk`
- [ ] Step 6: Run full typechecker test suite: `gradle :typechecker:test`
- [ ] Step 7: Verify heuristic still works for non-variant cases
- [ ] Step 8: Clean up debug logging added during investigation

---

## Alternative Quick Fix (If Option 1 Doesn't Work)

If Option 1 doesn't fully solve the issue, try this minimal change:

**In `extractParameterConstraints` (line 4410)**:
- Check if the parameter is used in a variant constructor call
- If yes, don't create a fresh return type variable
- Instead, leave it unconstrained and let unification determine it

This is less elegant but might work with minimal code changes.

---

## Files Modified

### Primary:
- `typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`

### Test Files:
- `/tmp/test_constrain_return.funk` (main test case)
- `/tmp/test_safemap_returntype.funk` (original issue)
- `/tmp/test_safemap_wrong.funk` (negative test - should still fail)

---

## Debug Commands

```bash
# Test main case
funk-dev check /tmp/test_constrain_return.funk

# Test with debug output
JAVA_OPTS="-Dfunk.typecheck.debug=true" funk-dev check /tmp/test_constrain_return.funk 2>&1 | grep -E "(BIDIRECTIONAL|constraint|generalize)"

# Run tests
gradle :typechecker:test --tests "TypeCheckerComprehensiveTest"

# Build
gradle :typechecker:compileKotlin :cli:jar
```

---

## Success Criteria

1. `/tmp/test_constrain_return.funk` infers `test : { f: { x: String } -> String } -> Box`
2. `/tmp/test_safemap_returntype.funk` infers `safeMap : { f: { x: String } -> String, result: Result } -> Result`
3. All existing typechecker tests pass
4. Heuristic still works for non-variant constructor cases

---

## Notes for Next Session

- The constraint solving refactor (Option 1) at lines 385-388 should be **reverted** - it doesn't help because constraints are already cleared
- The bidirectional code at lines 2187-2220 and 1401-1447 is **correct** - it just needs to be reached
- The key insight: **disable the heuristic** when bidirectional information is available
- Start fresh with Option 1 as outlined above
