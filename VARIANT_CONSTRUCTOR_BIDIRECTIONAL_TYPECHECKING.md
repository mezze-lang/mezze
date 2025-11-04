# Variant Constructor Type Checking Analysis

## Overview
This document describes how variant constructor applications (like `Success { value = ... }`) are type checked in the Funk TypeChecker, and identifies the current limitations and opportunities for adding bidirectional type checking.

## Key Files and Locations

1. **AST Definition**: `/Users/shriyans/Projects/funk/ast/src/main/kotlin/com/funk/language/ast/FunkAST.kt`
   - `VariantConstruction` expression (line 1022-1027)
   - `VariantType` type (line 343-350)
   - `RecordLiteral` expression (line 896-926)
   - `RecordType` type (line 333-340)

2. **TypeChecker**: `/Users/shriyans/Projects/funk/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt`
   - Main type checking entry point and bidirectional inference framework

## Current Architecture

### Expression AST Structure

```kotlin
// From FunkAST.kt (line 1022)
@Serializable
data class VariantConstruction(
    val constructor: String,
    val value: Expression? = null,  // The data being wrapped (typically a RecordLiteral)
    override val inferredType: FunkType? = null,
    override val sourceLocation: SourceLocation? = null
) : Expression

// Example: Success { value = 42 }
// constructor = "Success"
// value = RecordLiteral { entries = [RecordFieldEntry("value", IntLiteral(42))] }
```

### Type System

```kotlin
// Variant types represent sum types (line 343)
@Serializable
data class VariantType(
    val constructors: Map<String, FunkType?>,  // Constructor name -> data type (null for no data)
    val rowVar: RowVar? = null  // null = closed, non-null = open
) : FunkType

// Record types represent product types (line 333)
@Serializable
data class RecordType(
    val fields: Map<String, FunkType>,
    val rowVar: RowVar? = null
) : FunkType
```

## Current Type Checking Flow

### 1. Basic Type Inference for VariantConstruction (line 2698-2702)

Located in `inferBasicType()` function:

```kotlin
is VariantConstruction -> {
    val constructors = mapOf(expr.constructor to expr.value?.let { inferBasicType(it) })
    // Create closed variant by default (no row variable) per user preference
    com.funk.language.ast.FunkType.VariantType(constructors, null)
}
```

**Current Behavior:**
- Infers the type of the variant value expression (if present)
- Creates a minimal variant type containing only the constructed variant
- Uses **forward inference** - infers type from bottom-up without considering expected type

### 2. Bidirectional Inference Framework (line 1234-1522)

Function: `inferBasicTypeWithExpected(expr, expectedType)`

This function implements bidirectional type checking for:
- Literal expressions (IntLiteral, FloatLiteral, etc.)
- Lambda expressions (with expected function type)
- Function calls (effect call detection)
- Do expressions (effect handling)

**CRITICAL LIMITATION:** VariantConstruction is NOT handled in `inferBasicTypeWithExpected()`!

Falls back to forward inference for VariantConstruction (line 1519):
```kotlin
else -> {
    // For other expressions, fall back to normal inference
    inferBasicType(expr)
}
```

### 3. Record Literal Type Inference (line 2279-2328)

Current behavior in `inferBasicType()`:
```kotlin
is RecordLiteral -> {
    val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
    // ... processes each field entry ...
    for (entry in expr.entries) {
        when (entry) {
            is RecordFieldEntry -> {
                val fieldType = entry.value?.let { inferBasicType(it) }
                    ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                fields[entry.name] = fieldType
            }
            // ... handles spread entries ...
        }
    }
    com.funk.language.ast.FunkType.RecordType(fields, rowVar)
}
```

**LIMITATION:** Record literals are also NOT handled in `inferBasicTypeWithExpected()`!

This means:
- Record arguments to constructors are inferred without knowing the expected record type
- Field expressions cannot be constrained by the expected field types
- Type variables in field expressions are not unified with the expected types

## Problem: Lack of Bidirectional Type Checking for Constructor Arguments

### Example Problem

```funk
type Result = | Ok {value: Int}, Err {error: String} |

let x = Ok { value = "hello" }  // Should fail!
```

Current inference:
1. Parse: `VariantConstruction("Ok", RecordLiteral([RecordFieldEntry("value", StringLiteral("hello"))]))`
2. Type check `StringLiteral("hello")` → `StringType`
3. Type check `RecordLiteral` → `RecordType({"value": StringType})`
4. Type check `VariantConstruction` → `VariantType({"Ok": RecordType({"value": StringType})})`
5. No error is raised because we never checked against the expected type `RecordType({"value": IntType})`

### Root Cause

The type checker does not:
1. Look up the constructor's expected parameter type from the variant definition
2. Use that expected type to constrain field expressions bidirectionally
3. Unify the inferred record type with the expected record type

## Opportunities for Bidirectional Type Checking

### 1. Add VariantConstruction handling to `inferBasicTypeWithExpected()`

**Location:** `/Users/shriyans/Projects/funk/typechecker/src/main/kotlin/com/funk/language/typechecker/TypeChecker.kt` line 1517

**Implementation Strategy:**

```kotlin
is VariantConstruction -> {
    // Check if expectedType is a VariantType
    if (expectedType is com.funk.language.ast.FunkType.VariantType) {
        // Get the expected data type for this constructor
        val expectedDataType = expectedType.constructors[expr.constructor]
        
        // If constructor has data, infer with expected type
        val actualDataType = if (expr.value != null && expectedDataType != null) {
            // THIS IS THE KEY: Use bidirectional inference for the value
            inferBasicTypeWithExpected(expr.value, expectedDataType)
        } else if (expr.value != null) {
            // No expected type for the data, infer normally
            inferBasicType(expr.value)
        } else {
            expectedDataType  // No data provided, use expected type
        }
        
        // Return expected type (we now know it's compatible)
        expectedType
    } else {
        // No expected type, fall back to forward inference
        inferBasicType(expr)
    }
}
```

### 2. Add RecordLiteral handling to `inferBasicTypeWithExpected()`

**Location:** Same section, line 1517

**Implementation Strategy:**

```kotlin
is RecordLiteral -> {
    if (expectedType is com.funk.language.ast.FunkType.RecordType) {
        // Bidirectional inference for record fields
        val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
        
        for (entry in expr.entries) {
            when (entry) {
                is RecordFieldEntry -> {
                    val expectedFieldType = expectedType.fields[entry.name]
                    val fieldType = if (entry.value != null && expectedFieldType != null) {
                        // Use bidirectional inference with expected field type
                        inferBasicTypeWithExpected(entry.value, expectedFieldType)
                    } else if (entry.value != null) {
                        // No expected type for this field
                        inferBasicType(entry.value)
                    } else {
                        // Shorthand {x} - need to infer from context
                        expectedFieldType ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                    }
                    fields[entry.name] = fieldType
                }
                // Handle spread entries...
            }
        }
        
        // Unify with expected type
        com.funk.language.ast.FunkType.RecordType(fields, expectedType.rowVar)
    } else {
        // No expected type, fall back to forward inference
        inferBasicType(expr)
    }
}
```

### 3. Unification Strategy

**Key Principle:** When we have an expected type for a variant constructor or record:

1. **Extract expected parameter type** from the variant/record definition
2. **Constrain inner expressions** using `inferBasicTypeWithExpected()` with the expected types
3. **Validate compatibility** by checking the inferred type against expected
4. **Propagate constraints** for type variables in field expressions

**Constraint Flow Example:**

```
Success { value = ?? }  (expected: Ok {value: Int})
    ↓
Extract expected data type: {value: Int}
    ↓
Constrain field value: value = ?? (expected Int)
    ↓
This allows literal 42 to be constrained to Int
This rejects literal "hello" as incompatible
```

## Implementation Checklist

To add proper bidirectional type checking for variant constructors:

1. [ ] Modify `inferBasicTypeWithExpected()` to handle `VariantConstruction`
2. [ ] Modify `inferBasicTypeWithExpected()` to handle `RecordLiteral`
3. [ ] Implement expected-type lookup for variant constructors
4. [ ] Add field-level constraint propagation in record literals
5. [ ] Update constraint resolution to handle record field mismatches
6. [ ] Add test cases for:
   - Literal constraints in constructor fields
   - Type variable unification across constructor arguments
   - Field type mismatches
   - Spread record type checking
7. [ ] Update error messages to show field-level type mismatches

## Related Code Patterns to Follow

The TypeChecker already implements bidirectional inference for:

1. **Literals with expected types** (line 1237-1280):
   - `IntLiteral` with `NatType` expected
   - `IntLiteral` with `FracType` expected
   - Shows pattern of constraint generation vs. error throwing

2. **Lambda expressions with expected types** (line 1315-1387):
   - Extracts expected parameter types
   - Uses expected return type for body inference
   - Collects effects alongside type inference

3. **Do expressions with expected types** (line 1421-1515):
   - Handles both direct function types and pure result types
   - Shows context management pattern for bidirectional inference

## Summary

The Funk TypeChecker currently lacks bidirectional type checking for variant constructors and their record arguments. This is a significant gap because:

1. **Constructor arguments are not validated** against the variant definition
2. **Field expressions cannot be constrained** by expected types
3. **Type inference is bottom-up only**, missing opportunities for type refinement

Adding bidirectional type checking to `inferBasicTypeWithExpected()` would:
- Enable proper type validation for constructor applications
- Allow literals and type variables to be constrained from the expected type
- Provide better error messages for type mismatches
- Enable more polymorphic type inference in constructors

The implementation should follow established patterns in the codebase for handling expected types in literals and lambda expressions.
