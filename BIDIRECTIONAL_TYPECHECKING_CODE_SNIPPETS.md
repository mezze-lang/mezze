# Bidirectional Type Checking Code Snippets

## Current Code Locations

### 1. VariantConstruction Forward Inference (TypeChecker.kt, line 2698-2702)

**Current Implementation:**
```kotlin
is VariantConstruction -> {
    val constructors = mapOf(expr.constructor to expr.value?.let { inferBasicType(it) })
    // Create closed variant by default (no row variable) per user preference
    com.funk.language.ast.FunkType.VariantType(constructors, null)
}
```

**Problem:** Uses only forward inference, no access to expected type.

**Location for Modification:** Line 2698 in `inferBasicType()` OR add new case to `inferBasicTypeWithExpected()` at line 1517.

---

### 2. Bidirectional Inference Entry Point (TypeChecker.kt, line 1234-1522)

**Function Signature:**
```kotlin
private fun inferBasicTypeWithExpected(
    expr: Expression, 
    expectedType: com.funk.language.ast.FunkType
): com.funk.language.ast.FunkType {
    return when (expr) {
        // ... handles IntLiteral, FloatLiteral, BinaryOperation, UnaryOperation, 
        //     LambdaExpression, FunctionCall, DoExpression ...
        
        else -> {
            // For other expressions, fall back to normal inference
            inferBasicType(expr)  // LINE 1519 - This is where VariantConstruction falls back
        }
    }
}
```

**Key Observation:** The `else` branch at line 1519 is where unhandled expression types (including VariantConstruction) fall back to forward inference.

---

### 3. IntLiteral Bidirectional Inference Example (TypeChecker.kt, line 1236-1262)

This is the pattern to follow for bidirectional type checking:

```kotlin
is IntLiteral -> {
    when (expectedType) {
        is com.funk.language.ast.FunkType.NatType -> {
            // Allow non-negative Int literals to be constrained to Nat
            if (expr.value >= 0) {
                expectedType
            } else {
                // Instead of throwing, return Int type as fallback
                com.funk.language.ast.FunkType.IntType
            }
        }
        is com.funk.language.ast.FunkType.IntType -> expectedType
        is com.funk.language.ast.FunkType.VarType -> {
            // Type variable - use constraint-based inference
            val inferredType = com.funk.language.ast.FunkType.IntType
            constraints.add(inferredType to expectedType)
            inferredType
        }
        else -> {
            // Instead of throwing, add constraint and continue
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("Warning: Cannot use Int literal where ${prettyPrintType(expectedType)} expected")
            }
            constraints.add(Pair(com.funk.language.ast.FunkType.IntType, expectedType))
            expectedType
        }
    }
}
```

**Key Patterns:**
1. Check expected type with `when` statement
2. Handle concrete types (return expected type if valid)
3. Handle type variables (add constraint)
4. Handle mismatches (add constraint instead of throwing)
5. Use `constraints.add()` for type variable unification

---

### 4. LambdaExpression Bidirectional Inference Example (TypeChecker.kt, line 1315-1387)

Shows how to handle complex expressions with expected types:

```kotlin
is LambdaExpression -> {
    if (expectedType is com.funk.language.ast.FunkType.FunctionType) {
        // Extract expected return type and pass it to the lambda body
        val expectedReturnType = expectedType.returnType
        val expectedEffects = expectedType.effects
        
        // Enter new scope for lambda parameters
        val originalContext = context.toMap()
        val scopeLevel = com.funk.language.ast.TypeVar.enterScope()
        val previousLambdaContext = currentLambdaContext
        currentLambdaContext = expr
        
        try {
            // Set up parameters based on expected parameter type
            when (val params = expr.parameters) {
                is UntypedParameters -> {
                    // Use expected parameter types from the function type
                    val expectedParamType = expectedType.paramType
                    if (expectedParamType is com.funk.language.ast.FunkType.RecordType) {
                        val expectedParams = expectedParamType.fields
                        params.names.forEachIndexed { index, name ->
                            val expectedParamTypeForName = 
                                expectedParams[name] ?: 
                                com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                            context[name] = expectedParamTypeForName
                        }
                    }
                }
                is TypedParameters -> {
                    // Use explicitly typed parameters
                    val annotatedType = convertTypeAnnotation(params.recordType)
                    if (annotatedType is com.funk.language.ast.FunkType.RecordType) {
                        annotatedType.fields.forEach { (name, type) ->
                            context[name] = type
                        }
                    }
                }
            }
            
            // Infer the body with expected return type (THIS IS THE KEY!)
            val bodyType = inferBasicTypeWithExpected(expr.body, expectedReturnType)
            
            // Collect effects from lambda body
            val collectedEffects = mutableSetOf<String>()
            collectEffectsFromExpression(expr.body, collectedEffects)
            
            // Combine expected effects with collected effects
            val allEffects = (expectedEffects?.effects ?: emptySet()) + collectedEffects
            val finalEffectRow = if (allEffects.isNotEmpty()) {
                com.funk.language.ast.FunkType.EffectRow(allEffects)
            } else {
                null
            }

            // Return the expected function type structure with collected effects
            com.funk.language.ast.FunkType.FunctionType(
                expectedType.paramType,
                bodyType,
                finalEffectRow
            )
        } finally {
            // Exit scope and restore original context
            com.funk.language.ast.TypeVar.exitScope()
            context.clear()
            context.putAll(originalContext)
            currentLambdaContext = previousLambdaContext
        }
    } else {
        // No expected function type, fall back to normal inference
        inferBasicType(expr)
    }
}
```

**Key Patterns:**
1. Check if expected type has the right structure (FunctionType)
2. Extract sub-expectations from the expected type
3. Manage context scope for parameters
4. Recursively call `inferBasicTypeWithExpected()` with extracted expectations
5. Restore context in finally block

---

### 5. RecordLiteral Forward Inference (TypeChecker.kt, line 2279-2328)

**Current Implementation:**
```kotlin
is RecordLiteral -> {
    val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
    var spreadFields: Map<String, com.funk.language.ast.FunkType>? = null
    var spreadRowVar: com.funk.language.ast.RowVar? = null
    var hasMultipleSpreads = false

    for (entry in expr.entries) {
        when (entry) {
            is RecordSpreadEntry -> {
                // Only one spread is allowed
                if (spreadFields != null) {
                    hasMultipleSpreads = true
                    throw SpreadSyntaxError.MultipleSpreadsNotAllowed
                }

                // Handle spread: get fields from the spread record
                val spreadType = inferBasicType(entry.expression)
                if (spreadType is com.funk.language.ast.FunkType.RecordType) {
                    spreadFields = spreadType.fields
                    spreadRowVar = spreadType.rowVar
                    // Add all fields from the spread record
                    fields.putAll(spreadType.fields)
                } else {
                    throw SpreadSyntaxError.NonRecordSpread(prettyPrintType(spreadType))
                }
            }
            is RecordFieldEntry -> {
                // STRICT SEMANTICS: Only allow updating existing fields from spread
                if (spreadFields != null && !spreadFields.containsKey(entry.name)) {
                    throw SpreadSyntaxError.FieldAdditionNotAllowed(entry.name, spreadFields.keys.toList())
                }

                val fieldType = entry.value?.let { inferBasicType(it) }
                    ?: com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                fields[entry.name] = fieldType
            }
        }
    }

    // Preserve open/closed status from spread record
    val rowVar = if (spreadFields != null) {
        // If spreading, preserve the original record's open/closed status
        spreadRowVar
    } else {
        // If not spreading, check if the literal itself has "..."
        if (expr.restField == "...") com.funk.language.ast.RowVar.fresh() else null
    }

    com.funk.language.ast.FunkType.RecordType(fields, rowVar)
}
```

**Problem:** Uses only forward inference, no access to expected type. Each field is inferred independently without knowing the expected field types.

---

## Implementation Template for VariantConstruction

### Step 1: Add to inferBasicTypeWithExpected() at line 1517

```kotlin
is VariantConstruction -> {
    // Check if expectedType is a VariantType
    if (expectedType is com.funk.language.ast.FunkType.VariantType) {
        // Get the expected data type for this constructor
        val expectedDataType = expectedType.constructors[expr.constructor]
        
        // Type check the value (if present) with expected type
        val actualDataType = when {
            expr.value != null && expectedDataType != null -> {
                // BIDIRECTIONAL: Use expected type to constrain the value
                inferBasicTypeWithExpected(expr.value, expectedDataType)
            }
            expr.value != null -> {
                // No expected type for the data, infer normally
                inferBasicType(expr.value)
            }
            expectedDataType != null -> {
                // No value provided but expected type has data
                // Could be a type error - constructor expects data but got none
                expectedDataType
            }
            else -> null
        }
        
        // Validate that the constructor exists and matches expected type
        if (expectedDataType != null && expr.value == null) {
            // Constructor expects data but we have none - could be an error
            if (System.getProperty("funk.typecheck.debug") == "true") {
                println("Warning: Constructor ${expr.constructor} expects data but got none")
            }
        }
        
        // Return expected type (constructor application is valid)
        expectedType
    } else {
        // No expected type, fall back to forward inference
        inferBasicType(expr)
    }
}
```

### Step 2: Add to inferBasicTypeWithExpected() at line 1517

```kotlin
is RecordLiteral -> {
    if (expectedType is com.funk.language.ast.FunkType.RecordType) {
        // Bidirectional inference for record fields
        val fields = mutableMapOf<String, com.funk.language.ast.FunkType>()
        var spreadFields: Map<String, com.funk.language.ast.FunkType>? = null
        var spreadRowVar: com.funk.language.ast.RowVar? = null

        for (entry in expr.entries) {
            when (entry) {
                is RecordSpreadEntry -> {
                    // Handle spread with expected type
                    val spreadType = inferBasicType(entry.expression)
                    if (spreadType is com.funk.language.ast.FunkType.RecordType) {
                        spreadFields = spreadType.fields
                        spreadRowVar = spreadType.rowVar
                        fields.putAll(spreadType.fields)
                    } else {
                        throw SpreadSyntaxError.NonRecordSpread(prettyPrintType(spreadType))
                    }
                }
                is RecordFieldEntry -> {
                    // BIDIRECTIONAL: Get expected type for this field
                    val expectedFieldType = expectedType.fields[entry.name]
                    
                    val fieldType = when {
                        entry.value != null && expectedFieldType != null -> {
                            // Use bidirectional inference with expected field type
                            inferBasicTypeWithExpected(entry.value, expectedFieldType)
                        }
                        entry.value != null -> {
                            // No expected type for this field, infer normally
                            inferBasicType(entry.value)
                        }
                        expectedFieldType != null -> {
                            // Shorthand {x} with expected type - use expected
                            expectedFieldType
                        }
                        else -> {
                            // No expected type and no value - fresh type variable
                            com.funk.language.ast.FunkType.VarType(com.funk.language.ast.TypeVar.fresh())
                        }
                    }
                    fields[entry.name] = fieldType
                }
            }
        }

        // Return record type with expected row variable
        com.funk.language.ast.FunkType.RecordType(fields, expectedType.rowVar)
    } else {
        // No expected record type, fall back to forward inference
        inferBasicType(expr)
    }
}
```

---

## Key Helper Functions Already Available

### 1. Type Compatibility Check
```kotlin
private fun isTypeCompatible(type1: com.funk.language.ast.FunkType, type2: com.funk.language.ast.FunkType): Boolean
```
Use to validate constructor arguments match expected types.

### 2. Constraint Addition
```kotlin
private fun addConstraint(type1: com.funk.language.ast.FunkType, type2: com.funk.language.ast.FunkType)
```
Use to add type variable unification constraints.

### 3. Pretty Print Type
```kotlin
private fun prettyPrintType(type: com.funk.language.ast.FunkType): String
```
Use in debug output and error messages.

### 4. Type Variable Management
```kotlin
com.funk.language.ast.TypeVar.fresh()  // Generate fresh type variable
com.funk.language.ast.TypeVar.enterScope()  // Enter new scope
com.funk.language.ast.TypeVar.exitScope()  // Exit scope
```
Use for context management in nested type checking.

---

## Testing Strategy

### Test Case 1: Constructor with literal field
```funk
type Result = | Ok {value: Int}, Err {error: String} |

// Should type check successfully
let x = Ok { value = 42 }

// Should fail - string instead of int
let y = Ok { value = "hello" }
```

### Test Case 2: Constructor with type variable
```funk
type Option = | Some {value: 'a}, None |

// Should unify 'a with Int
let x = Some { value = 42 }

// Should unify 'a with String
let y = Some { value = "hello" }
```

### Test Case 3: Nested record constructor
```funk
type Person = | User {name: String, age: Int} |

// Should constrain nested fields
let person = User { name = "Alice", age = 30 }

// Should fail - wrong field type
let invalid = User { name = "Bob", age = "twenty" }
```

---

## Integration Points

1. **Constraint Solving** (ConstraintSolver.kt): Update to handle record field constraints
2. **Error Reporting** (ErrorPrettyPrinter.kt): Add field-level error messages for constructors
3. **Tests** (TypeCheckerComprehensiveTest.kt): Add bidirectional inference test cases
