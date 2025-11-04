# Quick Reference: Variant Constructor Bidirectional Type Checking

## One-Page Overview

### The Problem
```kotlin
type Result = | Ok {value: Int}, Err {error: String} |

// Currently type checks incorrectly:
val x = Ok { value = "hello" }  // Should FAIL but currently PASSES
```

### The Root Cause
- `inferBasicTypeWithExpected()` (line 1234-1522 in TypeChecker.kt) does NOT handle `VariantConstruction`
- Falls back to forward-only inference (line 1519)
- Record fields are never constrained by expected types

### The Solution
Add two cases to `inferBasicTypeWithExpected()` at line 1517:

```kotlin
// Add before the `else` at line 1517

// CASE 1: VariantConstruction
is VariantConstruction -> {
    if (expectedType is com.funk.language.ast.FunkType.VariantType) {
        val expectedDataType = expectedType.constructors[expr.constructor]
        val actualDataType = expr.value?.let { 
            inferBasicTypeWithExpected(it, expectedDataType ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh()))
        }
        expectedType
    } else {
        inferBasicType(expr)
    }
}

// CASE 2: RecordLiteral
is RecordLiteral -> {
    if (expectedType is com.funk.language.ast.FunkType.RecordType) {
        val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
        for (entry in expr.entries) {
            when (entry) {
                is RecordFieldEntry -> {
                    val expectedFieldType = expectedType.fields[entry.name]
                    fields[entry.name] = if (entry.value != null && expectedFieldType != null) {
                        inferBasicTypeWithExpected(entry.value, expectedFieldType)
                    } else if (entry.value != null) {
                        inferBasicType(entry.value)
                    } else {
                        expectedFieldType ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                    }
                }
                is RecordSpreadEntry -> {
                    val spreadType = inferBasicType(entry.expression)
                    if (spreadType is com.funk.language.ast.FunkType.RecordType) {
                        fields.putAll(spreadType.fields)
                    }
                }
            }
        }
        com.funk.language.ast.FunkType.RecordType(fields, expectedType.rowVar)
    } else {
        inferBasicType(expr)
    }
}
```

## Key Files

| File | Purpose | Line |
|------|---------|------|
| TypeChecker.kt | Main implementation | 1517 (add here) |
| FunkAST.kt | AST definitions | 1022 (VariantConstruction), 896 (RecordLiteral) |
| TypeChecker.kt | Current forward inference | 2698-2702 (Variant), 2279-2328 (Record) |

## Implementation Phases

```
Phase 1: Add VariantConstruction handling
├─ Check if expectedType is VariantType
├─ Extract expected constructor data type
├─ Recursively infer value with expected type
└─ Return expected type

Phase 2: Add RecordLiteral handling
├─ Check if expectedType is RecordType
├─ For each field, extract expected field type
├─ Recursively infer field value with expected type
└─ Return record type with expected row variable

Phase 3: Testing & Integration
├─ Add test cases
├─ Verify constraint propagation
└─ Update error messages
```

## Type Flow Example

```
Input:   Ok { value = "hello" }
Type:    VariantConstruction
Expected: VariantType(Ok -> RecordType(value: Int))

Step 1: Extract expected data type
   → RecordType(value: Int)

Step 2: Infer RecordLiteral with expected type
   → Call inferBasicTypeWithExpected(RecordLiteral, RecordType(value: Int))

Step 3: Extract expected field type
   → For "value": expect Int

Step 4: Infer StringLiteral with expected type
   → Call inferBasicTypeWithExpected(StringLiteral("hello"), IntType)
   → StringType != IntType → ADD CONSTRAINT or ERROR

Result: Type error! String doesn't match Int
```

## Before & After

### BEFORE (Forward Inference Only)
```kotlin
is VariantConstruction -> {
    val constructors = mapOf(expr.constructor to expr.value?.let { inferBasicType(it) })
    com.funk.language.ast.FunkType.VariantType(constructors, null)
}
// Result: VariantType(Ok -> RecordType(value: String))
// No validation against expected type!
```

### AFTER (Bidirectional Inference)
```kotlin
is VariantConstruction -> {
    if (expectedType is com.funk.language.ast.FunkType.VariantType) {
        val expectedDataType = expectedType.constructors[expr.constructor]
        expr.value?.let { inferBasicTypeWithExpected(it, expectedDataType) }
        expectedType  // Return expected type after validation
    } else {
        inferBasicType(expr)
    }
}
// Result: Validates against expected type, enables literal constraints!
```

## Testing

### Test 1: Literal Constraint
```funk
type Result = | Ok {value: Int} |
let x = Ok { value = 42 }    // PASS
let y = Ok { value = "hi" }  // FAIL
```

### Test 2: Type Variable Unification
```funk
type Option = | Some {value: 'a}, None |
let x = Some { value = 42 }     // 'a = Int
let y = Some { value = "hi" }   // 'a = String
```

### Test 3: Nested Records
```funk
type Person = | User {name: String, age: Int} |
let p = User { name = "Alice", age = 30 }  // PASS
let q = User { name = "Bob", age = "30" }  // FAIL
```

## Key Patterns to Follow

### Pattern 1: Literal with Expected Type (line 1236)
```kotlin
is IntLiteral -> {
    when (expectedType) {
        is com.funk.language.ast.FunkType.NatType -> 
            if (expr.value >= 0) expectedType else com.funk.language.ast.FunkType.IntType
        is com.funk.language.ast.FunkType.IntType -> expectedType
        is com.funk.language.ast.FunkType.VarType -> {
            constraints.add(com.funk.language.ast.FunkType.IntType to expectedType)
            com.funk.language.ast.FunkType.IntType
        }
        else -> {
            constraints.add(Pair(com.funk.language.ast.FunkType.IntType, expectedType))
            expectedType
        }
    }
}
```

### Pattern 2: Complex Expression with Expected Type (line 1315)
```kotlin
is LambdaExpression -> {
    if (expectedType is com.funk.language.ast.FunkType.FunctionType) {
        val expectedReturnType = expectedType.returnType
        // ... setup parameters ...
        val bodyType = inferBasicTypeWithExpected(expr.body, expectedReturnType)
        // ... handle effects ...
        com.funk.language.ast.FunkType.FunctionType(expectedType.paramType, bodyType, finalEffectRow)
    } else {
        inferBasicType(expr)
    }
}
```

## Debugging Checklist

- [ ] Verify `inferBasicTypeWithExpected()` is called (not forward inference)
- [ ] Check that expected type is extracted correctly
- [ ] Verify recursive call passes expected types to subexpressions
- [ ] Test constraint propagation: `gradle :typechecker:test -Dfunk.typecheck.debug=true`
- [ ] Check if context is properly restored in nested scopes
- [ ] Verify error messages show field-level mismatches

## Related Functions

```kotlin
// Extract type variables
TypeVar.fresh()              // Create fresh type variable
TypeVar.enterScope()         // Enter new scope
TypeVar.exitScope()          // Exit scope

// Type operations
inferBasicType(expr)         // Forward inference
inferBasicTypeWithExpected(expr, type)  // Bidirectional inference
addConstraint(type1, type2)  // Add unification constraint
isTypeCompatible(t1, t2)     // Check type compatibility
applySubstitutionToType(t, subst)  // Apply type substitutions

// Pretty printing
prettyPrintType(type)        // Format type for display
```

## Success Criteria

After implementation, these should all type check correctly:

```funk
type Result = | Ok {value: Int}, Err {error: String} |
type Option = | Some {value: 'a}, None |

// Literal constraints
let a = Ok { value = 42 }           // PASS
let b = Ok { value = "hello" }      // FAIL

// Type variable unification
let c = Some { value = 42 }         // 'a = Int
let d = Some { value = c }          // 'a = Option Int

// Nested records
type Person = | User {name: String, age: Int} |
let e = User { name = "Alice", age = 30 }  // PASS

// Record spread with constraints
let f = { ...c, extra = 5 }         // Type check against spread
```

## Files to Modify

1. **TypeChecker.kt** (line 1517)
   - Add `is VariantConstruction` case
   - Add `is RecordLiteral` case

2. **TypeCheckerComprehensiveTest.kt**
   - Add bidirectional inference tests

3. **Optional: ErrorPrettyPrinter.kt**
   - Add field-level error messages

## Estimated Effort

- Implementation: 1-2 hours
- Testing: 1-2 hours
- Integration & debugging: 1-2 hours
- Total: 3-6 hours

## Next Steps

1. Review VARIANT_CONSTRUCTOR_BIDIRECTIONAL_TYPECHECKING.md for detailed context
2. Review BIDIRECTIONAL_TYPECHECKING_CODE_SNIPPETS.md for exact code patterns
3. Implement the two cases in TypeChecker.kt line 1517
4. Add test cases
5. Run `gradle test` to verify
6. Commit with clear message explaining the bidirectional inference addition
