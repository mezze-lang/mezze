# Recursive Record Field Access Bug - FIXED

## Problem Summary

When a recursive function returned a record with multiple fields, accessing those fields inside the recursive case failed with "Field not found" errors. Only the first accessed field was recognized.

### Example of Bug

```funk
let foo = {n: Int} ->
  match n is
    0 -> {x = [], y = []}
    _ -> do
      let k = foo {n = n - 1}
      let x = array::of {x = n, xs = k.x}  -- ✓ Works
      let y = array::of {x = n, xs = k.y}  -- ✗ FAILED: Field 'y' not found
      {x, y}
```

**Error**: `Field 'y' not found in record type '{ x: 'a }'`

## Root Cause

The type checker's constraint-based approach had the following issue:

1. **Recursive functions stay as VarType placeholders** during match expression type-checking
2. **Field access on VarType creates single-field constraints**:
   - Accessing `k.x` creates constraint: `'a = { x: 'b }` (only ONE field)
3. **Constraints are solved eagerly** (between field accesses)
4. **Second field access fails** because the type is now `{ x: 'b }` (missing `y` field)

## The Fix

Implemented a **field accumulator** approach that tracks ALL fields accessed on each VarType before creating constraints.

### Implementation

Added two tracking structures to TypeChecker.kt:

```kotlin
// Track which fields are accessed on each VarType
private val varTypeFieldAccumulator = mutableMapOf<TypeVar, MutableMap<String, FunkType>>()

// Track identifier names to their TypeVar for accumulator lookup
private val identifierToTypeVar = mutableMapOf<String, TypeVar>()
```

### How It Works

1. **VarType Field Access** (first time accessing `k.x`):
   - Track identifier `k` → TypeVar mapping
   - Add field `x` to the accumulator for this TypeVar
   - Create constraint with ALL accumulated fields: `'a = { x: 'b }`

2. **RecordType Field Access** (accessing `k.y` after constraint solving):
   - Type is now `{ x: ['a] }` (RecordType)
   - Check if identifier `k` is in the tracking map
   - If yes, add field `y` to the accumulator
   - Update constraint to: `'a = { x: 'b, y: 'c }`

### Code Changes

**File**: `/Users/shriyans/Projects/funk/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`

1. **Line 381-384**: Added field accumulator and identifier tracking maps

2. **Lines 6318-6334**: Extract identifier name and infer type

3. **Lines 6337-6373**: Modified RecordType branch to:
   - Check if identifier is tracked
   - If field not found but identifier tracked, add to accumulator
   - Create updated RecordType constraint with all fields

4. **Lines 6374-6397**: Modified VarType branch to:
   - Track identifier → TypeVar mapping
   - Accumulate fields in the map
   - Create RecordType constraint with ALL accumulated fields

## Test Results

All previously failing tests now pass:

- ✅ `user example - recursive with two field accesses`
- ✅ `minimal recursive function rebuilding record`
- ✅ `minimal recursive function rebuilding record reversed`
- ✅ `simple recursive function with record return`
- ✅ `exact partition function from interpreter test`
- ✅ `minimal recursive function accessing single field`
- ✅ `minimal recursive function with record return`

## Impact

This fix enables recursive functions to return records with multiple fields that can all be accessed in the recursive case. This is essential for common patterns like:

- Partition functions that return `{yes = [...], no = [...]}`
- Accumulator patterns with multiple lists
- Any recursive data structure building that uses records

## Technical Notes

The fix works by delaying the "finalization" of RecordType constraints for VarTypes. Instead of creating a constraint with just the currently accessed field, we accumulate all fields and update the constraint each time a new field is accessed.

This approach:
- Preserves the existing constraint-based type inference architecture
- Doesn't require major refactoring of constraint solving
- Works seamlessly with existing type unification
- Has minimal performance impact (just maintains two lookup maps)

## Related Files

- TypeChecker.kt:381-384, 6318-6397 (implementation)
- EmptyArraysInRecordTest.kt (test cases)
- RECURSIVE_RECORD_BUG_FINAL_ANALYSIS.md (original analysis)
