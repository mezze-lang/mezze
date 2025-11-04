# Bidirectional Type Checking for Variant Constructors - Implementation Guide

## Quick Summary

This directory now contains comprehensive documentation for understanding and implementing bidirectional type checking for variant constructor applications in the Funk TypeChecker.

## Files Created

### 1. VARIANT_CONSTRUCTOR_BIDIRECTIONAL_TYPECHECKING.md (11 KB)
**Overview document explaining the current type checking architecture and implementation opportunities**

Key sections:
- Current type checking flow for VariantConstruction (forward inference only)
- Bidirectional inference framework and its limitations
- Problem analysis with concrete examples
- Implementation opportunities with pseudo-code examples
- Related code patterns already in the codebase
- Implementation checklist

**Start here** if you want to understand the big picture.

### 2. BIDIRECTIONAL_TYPECHECKING_CODE_SNIPPETS.md (7 KB)
**Detailed code reference with exact line numbers and ready-to-implement templates**

Key sections:
- Current code locations with exact line numbers
- Code snippets from the TypeChecker showing bidirectional inference patterns
- Step-by-step implementation templates for VariantConstruction and RecordLiteral
- Helper functions available in the codebase
- Test case examples
- Integration points with other components

**Use this** when you're ready to write the code.

### 3. BIDIRECTIONAL_TYPECHECKING_SUMMARY.md (this file)
**Navigation and quick reference guide**

---

## Problem Statement

The Funk TypeChecker currently uses **forward (bottom-up) type inference** for variant constructor applications:

```funk
type Result = | Ok {value: Int}, Err {error: String} |

let x = Ok { value = "hello" }  // Currently PASSES but should FAIL!
```

**Why it passes:** The type of `Ok { value = "hello" }` is inferred as `VariantType({"Ok": RecordType({"value": StringType})})`. The type checker never validates this against the expected type `Result`.

**Root cause:** `VariantConstruction` and `RecordLiteral` expressions are not handled in the `inferBasicTypeWithExpected()` function, so they always fall back to forward inference without considering the expected type.

---

## Solution Architecture

Implement **bidirectional type checking** by adding two new cases to `inferBasicTypeWithExpected()`:

### For VariantConstruction (line 1517, TypeChecker.kt):
1. Check if expected type is a VariantType
2. Extract expected data type for the constructor
3. Recursively check the value expression with the expected data type
4. Return the expected type

### For RecordLiteral (line 1517, TypeChecker.kt):
1. Check if expected type is a RecordType
2. For each field entry, extract the expected field type
3. Recursively check the field value with the expected field type
4. Return the record type with expected row variable

---

## Key Code Locations

### Main Type Checker File
- **File:** `/Users/shriyans/Projects/funk/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`
- **Lines:** 1-7861 total

### Critical Functions
1. **`inferBasicTypeWithExpected()` (line 1234-1522)**
   - Current bidirectional inference entry point
   - Falls back to forward inference at line 1519
   - This is where VariantConstruction and RecordLiteral should be added

2. **`inferBasicType()` (line 1527+)**
   - Forward inference function
   - VariantConstruction handling: line 2698-2702
   - RecordLiteral handling: line 2279-2328

3. **Pattern examples to follow:**
   - IntLiteral bidirectional: line 1236-1262
   - LambdaExpression bidirectional: line 1315-1387

### AST Definitions
- **File:** `/Users/shriyans/Projects/funk/ast/src/main/kotlin/com/funk/language/ast/FunkAST.kt`
- **VariantConstruction:** line 1022-1027
- **VariantType:** line 343-350
- **RecordLiteral:** line 896-926
- **RecordType:** line 333-340

---

## Implementation Steps

### Phase 1: Add VariantConstruction bidirectional handling
1. Add case for `VariantConstruction` to `inferBasicTypeWithExpected()` at line 1517
2. Follow the pattern from IntLiteral (line 1236-1262)
3. Extract expected constructor data type and recursively infer with expected type

### Phase 2: Add RecordLiteral bidirectional handling
1. Add case for `RecordLiteral` to `inferBasicTypeWithExpected()` at line 1517
2. Iterate fields and extract expected field types
3. Recursively infer each field value with expected field type

### Phase 3: Testing & Integration
1. Add test cases for bidirectional constructor inference
2. Verify constraint propagation works correctly
3. Update error messages to show field-level mismatches

---

## Expected Outcomes

After implementation:

```funk
type Result = | Ok {value: Int}, Err {error: String} |

// Will be type checked against Result, so:
let x = Ok { value = 42 }           // PASS: literal 42 matches Int
let y = Ok { value = "hello" }      // FAIL: string doesn't match Int
let z = Ok { value = x }            // Type check depends on x's type

// Will also enable type variable constraints:
type Option = | Some {value: 'a}, None |

let a = Some { value = 42 }         // Infers 'a = Int
let b = Some { value = "hello" }    // Infers 'a = String
let c = Some { value = z }          // Infers 'a from z's type
```

---

## Architecture Diagram

```
Expression Type Checking Flow
=============================

Input: VariantConstruction("Ok", RecordLiteral({value: StringLiteral("hello")}))
       with Expected Type: VariantType({"Ok": RecordType({"value": IntType})})

       ↓

inferBasicTypeWithExpected() (line 1234)
       ↓
    [New Code: Check if expr is VariantConstruction]
       ↓
    [New Code: Extract expected data type from VariantType]
       ↓
    [New Code: Call inferBasicTypeWithExpected(RecordLiteral, RecordType({"value": IntType}))]
       ↓
    [New Code: Check if expr is RecordLiteral]
       ↓
    [New Code: Extract expected field type for "value" = IntType]
       ↓
    [New Code: Call inferBasicTypeWithExpected(StringLiteral("hello"), IntType)]
       ↓
    [New Code: StringLiteral doesn't match IntType → add constraint or error]
       ↓
Result: Type error reported to user
```

---

## Constraint Propagation Example

When inferring `Ok { value = 42 }` with expected type `Result`:

1. **Expected type lookup:** Extract `{value: Int}` from constructor definition
2. **Constraint generation:** Infer `42` with expected `Int`
3. **Literal constraining:** IntLiteral(42) matches IntType
4. **Field unification:** Record field `value` unified as `Int`
5. **Result:** Type of expression is `Ok {value: Int}` as expected

---

## Related Test Files

To add tests for this feature:

1. **TypeCheckerComprehensiveTest.kt** - Main test suite
   - Add tests for constructor bidirectional inference
   - Add tests for record field type constraints

2. **Test fixtures directory:** `typechecker/src/test/resources/fixtures/`
   - Add `.funk` files for various constructor scenarios
   - Add both valid (should pass) and invalid (should fail) examples

---

## Future Extensions

After basic bidirectional inference is working:

1. **Spread records with expected types** - Handle `{ ...base, field = value }` with expected types
2. **Partial application** - Support constructor partial application with type constraints
3. **Error messages** - Provide field-level error reporting for constructor type mismatches
4. **Performance** - Optimize constraint propagation for deeply nested records

---

## References

### Hindley-Milner Type Inference Background
- This implementation follows the Hindley-Milner system with extensions for effects
- Bidirectional type checking is a key technique in modern functional languages

### Related Funk Language Features
- **Effects System:** Algebraic effects are tracked separately from types
- **Ability Constraints:** Built-in abilities (Eq, Ord, NumAdd, etc.) add additional constraints
- **Type Variables:** Fresh type variables are generated per scope for polymorphism

---

## Questions & Debugging

### If you need to understand how:
- **Type variables are generated:** See `TypeVar.fresh()` in FunkAST.kt (line 515-526)
- **Constraints are solved:** See `ConstraintSolver.kt`
- **Effects are collected:** Search for `collectEffectsFromExpression()` in TypeChecker.kt
- **Error messages are formatted:** See `ErrorPrettyPrinter.kt`

### If tests fail:
1. Enable debug output: `gradle :typechecker:test -Dfunk.typecheck.debug=true`
2. Check that constraints are being added correctly
3. Verify that `inferBasicTypeWithExpected()` is being called (not falling back to forward inference)
4. Look for context management issues in nested scopes

---

## Implementation Checklist

- [ ] Read VARIANT_CONSTRUCTOR_BIDIRECTIONAL_TYPECHECKING.md for context
- [ ] Read BIDIRECTIONAL_TYPECHECKING_CODE_SNIPPETS.md for code details
- [ ] Add VariantConstruction case to inferBasicTypeWithExpected()
- [ ] Add RecordLiteral case to inferBasicTypeWithExpected()
- [ ] Add test cases for bidirectional inference
- [ ] Test with literal constraints (42 → Int)
- [ ] Test with type variable unification (42 → 'a)
- [ ] Test with field type mismatches (should error)
- [ ] Test with nested records
- [ ] Update documentation/comments
- [ ] Run full test suite: `gradle test`
- [ ] Commit changes with clear message

---

## Getting Started

1. Start with **VARIANT_CONSTRUCTOR_BIDIRECTIONAL_TYPECHECKING.md** to understand the architecture
2. Review the specific line numbers and code patterns in **BIDIRECTIONAL_TYPECHECKING_CODE_SNIPPETS.md**
3. Implement the two new cases in `inferBasicTypeWithExpected()`
4. Add test cases from the examples in the code snippets file
5. Run tests and debug using the guidance in this file

Good luck with the implementation!
