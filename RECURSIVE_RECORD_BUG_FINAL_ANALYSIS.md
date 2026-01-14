# Recursive Record Field Access Bug - Final Analysis

## Summary

When a recursive function returns a record with multiple fields, accessing those fields inside the recursive case fails with "Field not found" errors. Only the first accessed field is recognized.

## Excellent Minimal Test Case (by user)

```funk
use std::collections::array

let foo = {n: Int} ->
  match n is
    0 -> {x = [], y = []}
    _ -> do
      let k = foo {n = n - 1}
      let x = array::of {x = n, xs = k.x}  -- ✓ Works
      let y = array::of {x = n, xs = k.y}  -- ✗ FAILS: Field 'y' not found in record type '{ x: 'a }'
      {x, y}
```

## Root Cause

### Debug Trace Reveals

1. **Recursive function remains a VarType placeholder:**
   ```
   DEBUG MATCH v7: Checking foo, type = VarType(variable=TypeVar(name='h)), isFunctionType = false
   ```
   - Recursive functions stay as VarType placeholders throughout match expression type-checking
   - They only get unified to FunctionType AFTER all cases are processed

2. **Field access on result creates single-field constraints:**
   ```
   DEBUG FIELD ACCESS: k.x
     receiverType = 'a
     context[k] = 'a
     VarType branch - creating constraint for single field x
     Adding constraint 'a = { x: 'a }
   ```
   - When `k = foo {n = n - 1}`, k gets the VarType placeholder
   - Accessing `k.x` creates constraint: `'a = { x: 'a }` (SINGLE field!)

3. **Constraint solved before second field access:**
   ```
   DEBUG FIELD ACCESS: k.y
     resolvedReceiverType = { x: ['a] }
     RecordType branch, fields = [x]
     ERROR: Field 'y' not found
   ```
   - Between `k.x` and `k.y`, the constraint is solved
   - `k` is now typed as `{ x: ['a] }` (missing `y` field)
   - Accessing `k.y` fails because RecordType doesn't have that field

### The Core Problem

The type checker uses a **constraint-based** approach where:
1. VarTypes are created as placeholders
2. Field access on VarTypes creates **single-field RecordType constraints**
3. Constraints are solved **eagerly** (between field accesses)
4. Once resolved to a RecordType, accessing missing fields fails

For non-recursive functions, this works because the full RecordType is known upfront. For recursive functions, the placeholder VarType doesn't have the complete type information.

## Attempted Fixes

### Fix Attempt 1: Update function return type after each match case
- **Location:** TypeChecker.kt:5957-5986 (after case type inference)
- **Approach:** Update recursive function's return type with accumulated case types
- **Result:** FAILED - function type is still a VarType, not a FunctionType

### Fix Attempt 2: Update function type BEFORE processing each case
- **Location:** TypeChecker.kt:5908-5929
- **Approach:** Update function type before type-checking subsequent cases
- **Result:** FAILED - function is still a VarType placeholder, cannot update return type

### Why Fixes Failed

Recursive functions are represented as VarType placeholders during analysis:
```kotlin
// Line 1158: Recursive function gets VarType placeholder
val placeholderType = com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
context[item.variableName] = placeholderType
```

This placeholder remains throughout match expression type-checking. It only gets unified with a FunctionType AFTER all analysis is complete.

## The Real Fix Needed

The fundamental issue is the interaction between:
1. **Recursive function placeholders** (VarTypes)
2. **Field access constraint generation** (single-field RecordTypes)
3. **Eager constraint solving** (between field accesses)

### Potential Solutions

#### Option 1: Delay Constraint Solving
- Don't solve constraints until the entire match case is type-checked
- Allows multiple field access constraints to accumulate
- Constraint solver can then merge them: `'a = {x: 'b}` + `'a = {y: 'c}` → `'a = {x: 'b, y: 'c}`
- **Challenge:** Major refactoring of when/how constraints are solved

#### Option 2: Accumulative Field Access on VarTypes
- When accessing a field on a VarType that already has a constraint, ADD to it
- Instead of: `'a = {x: 'b}` then error on `y`
- Do: `'a = {x: 'b}` then update to `'a = {x: 'b, y: 'c}`
- **Challenge:** Requires tracking which VarTypes have accumulating field constraints

#### Option 3: Special Handling for Recursive Function Results
- Track that certain VarTypes are results of recursive calls
- Allow field access to consult the match case types to know available fields
- **Challenge:** Requires threading through additional context about recursion

## Impact

**Failing Tests:**
- ✗ `minimal recursive function rebuilding record` - accesses both `rest.yes` and `rest.no`
- ✗ `simple recursive function with record return` - same pattern
- ✗ `exact partition function` - real-world use case
- ✗ `user example - recursive with two field accesses` - user's test case

**Passing Tests:**
- ✓ `minimal recursive function with record return` - returns `rest` directly (no field access)
- ✓ `minimal recursive function accessing single field` - only accesses ONE field
- ✓ `simple record with two empty arrays` - non-recursive

**Pattern:** Tests PASS if they:
- Don't access fields on recursive results, OR
- Only access a SINGLE field

Tests FAIL if they access MULTIPLE fields on recursive results.

## Files Modified

1. **TypeChecker.kt** (multiple attempted fixes)
   - Lines 5908-5929: Attempted pre-case function type update
   - Lines 5957-5986: Removed (attempted post-case update)
   - Lines 6319-6341: Added debug output for field access
   - Lines 6359-6370: Added debug for VarType field access constraints

2. **EmptyArraysInRecordTest.kt**
   - Added comprehensive test cases isolating the issue
   - Line 222: User's excellent minimal example

## Recommendation

This bug requires a **fundamental change** to how the type checker handles recursive functions and/or constraint solving. The fixes attempted so far work within the existing architecture but cannot solve the core problem.

The most promising approach is **Option 1: Delay Constraint Solving** - accumulate all constraints from a match case before solving them. This would allow multiple field accesses to create multiple constraints that get merged by the solver.

## Related Issues

This is fundamentally a problem with **recursive polymorphic record types**. The type checker's constraint-based approach works well for simple cases but breaks down when:
1. A value has a polymorphic type (VarType placeholder)
2. Multiple structural constraints are added incrementally (field accesses)
3. Constraints are solved eagerly between additions

The same issue would likely occur with other structural types (tuples, arrays) if used similarly in recursive contexts.
