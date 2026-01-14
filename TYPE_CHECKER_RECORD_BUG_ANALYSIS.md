# Type Checker Bug: Record Fields Lost in Recursive Functions

## Problem Summary

When a recursive function returns a record with multiple empty array fields from a match expression, the type checker incorrectly infers only the first field.

### Failing Test Case

```funk
let count_down = {n: Int} ->
    match n is
      0 -> {yes = [], no = []}
      _ -> do
        let rest = count_down {n = n - 1}
        {yes = rest.yes, no = rest.no}  // ERROR: Field 'no' not found

count_down {n = 5}
```

**Error**: `Field 'no' not found in record type '{ yes: 'a }'`

## Root Cause Analysis

### The Issue

1. `count_down` is detected as a recursive function
2. A placeholder type `VarType('a)` is created for it (TypeChecker.kt:1158)
3. During match expression type checking:
   - First case: `0 -> {yes = [], no = []}` correctly infers `RecordType({yes: ['a], no: ['b]})`
   - This case type is added to `caseTypes` list
   - Context is restored, losing the type information (TypeChecker.kt:5995-5999)
   - Second case is type-checked
   - When accessing `rest.no`, it looks up `count_down`'s type
   - But the function's placeholder hasn't been updated yet with the record fields from the first case
   - Result: Only `{ yes: 'a }` is known, missing the `no` field

### Key Code Locations

- **Recursive function placeholder creation**: TypeChecker.kt:1155-1160
- **Match case processing**: TypeChecker.kt:5898-6000
- **Context restoration after each case**: TypeChecker.kt:5995-5999
- **RecordType unification**: TypeChecker.kt:2423-2454
- **Substitution application**: TypeChecker.kt:3895-3900

## Attempted Fixes

###Fix 1: Always Apply Substitutions (TypeChecker.kt:3895-3900)

**Original Code** (INCORRECT):
```kotlin
for ((varName, varType) in context) {
    if (!functionsBeingAnalyzed.contains(varName)) {
        context[varName] = applySubstitution(varType, substitutions)
    }
}
```

**Fixed Code**:
```kotlin
for ((varName, varType) in context) {
    context[varName] = applySubstitution(varType, substitutions)
}
```

**Result**: Partial fix - helps with substitution propagation but doesn't solve the root issue.

### Fix 2: Incremental Function Type Updates (TypeChecker.kt:5944-5962)

Added code to update recursive function types after each match case:

```kotlin
for (varName in functionsBeingAnalyzed) {
    val currentFunctionType = context[varName]
    if (currentFunctionType != null) {
        if (currentFunctionType is com.funk.language.ast.FunkType.FunctionType) {
            val currentReturnType = currentFunctionType.returnType
            if (currentReturnType is com.funk.language.ast.FunkType.VarType) {
                unifyTypes(currentReturnType, caseType)
            } else if (currentReturnType is com.funk.language.ast.FunkType.RecordType &&
                       caseType is com.funk.language.ast.FunkType.RecordType) {
                unifyTypes(currentReturnType, caseType)
            }
        }
    }
}
```

**Result**: Not fully working - needs further investigation.

### Fix 3: Preserve Recursive Function Updates (TypeChecker.kt:5986)

Modified context restoration to preserve updates to recursive functions:

```kotlin
if (!patternBindings.containsKey(varName) || functionsBeingAnalyzed.contains(varName)) {
    val updatedType = context[varName]
    if (updatedType != null) {
        updatedPreExistingVars[varName] = updatedType
    }
}
```

**Result**: Helps preserve updates but combined fix still incomplete.

## Status

- ✅ Non-recursive functions with records: PASS
- ✅ Simple match expressions with records: PASS
- ❌ Recursive functions with record returns: FAIL
- ❌ Real-world partition function: FAIL

## Next Steps

1. Verify that `functionsBeingAnalyzed` contains the function name during match case processing
2. Add logging to trace when/how the record type fields are lost
3. Consider alternative approach: collect ALL case types before type-checking later cases
4. Investigate if the issue is in how RecordType merging creates substitutions

## Related Files

- `/Users/shriyans/Projects/funk/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`
- `/Users/shriyans/Projects/funk/typechecker/src/test/kotlin/com/funk/language/typechecker/EmptyArraysInRecordTest.kt`
- `/Users/shriyans/Projects/funk/interpreter/src/test/kotlin/com/funk/language/interpreter/ArrayPatternInterpreterTest.kt`
