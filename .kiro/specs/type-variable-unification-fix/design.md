# Design Document

## Overview

This document outlines the design for fixing the type variable unification issue in the constraint propagation system. The core problem is that lambda parameter type variables ('e, 'h) are disconnected from constraint generation type variables ('a, 'b), preventing proper type inference.

## Architecture

### Current Problem Analysis

```
Lambda: {x, y} -> int::to_string {value = x + y}

Current Flow:
1. Lambda parameters: x: 'e, y: 'h
2. Binary operation x + y creates: 'i (result)
3. Qualified call creates constraints: 'a = Int, {left: 'a, right: 'b} = {left: 'a, right: 'a}
4. Constraint solver resolves: 'a -> Int, 'b -> 'a
5. But 'e and 'h remain unresolved because they're disconnected from 'a and 'b

Expected Flow:
1. Lambda parameters: x: 'e, y: 'h  
2. Binary operation x + y uses: 'e, 'h, 'i with constraints 'e = 'h = 'i
3. Qualified call forces: 'i = Int
4. Constraint solver resolves: 'e -> Int, 'h -> Int, 'i -> Int
```

### Solution Components

#### 1. Type Variable Context Unification

**Problem:** Binary operations create new type variables instead of using lambda parameter variables.

**Solution:** Modify binary operation type inference to use existing context type variables.

```kotlin
// Current (broken):
val leftType = inferBasicType(expr.left)   // Creates new 'a
val rightType = inferBasicType(expr.right) // Creates new 'b

// Fixed:
val leftType = context[leftName] ?: inferBasicType(expr.left)   // Uses 'e
val rightType = context[rightName] ?: inferBasicType(expr.right) // Uses 'h
```

#### 2. Constraint Generation Consistency

**Problem:** Qualified call constraints use different type variables than lambda parameters.

**Solution:** Ensure constraint generation traces back to original type variables.

```kotlin
// When processing int::to_string {value = x + y}:
// 1. Identify that x + y uses variables 'e, 'h, 'i
// 2. Generate constraint: 'i = Int (not 'a = Int)
// 3. Ensure binary operation constraints: 'e = 'h = 'i
```

#### 3. Enhanced Constraint Solver

**Problem:** Constraint solver doesn't handle transitive relationships properly.

**Solution:** Implement iterative constraint propagation with fixed-point resolution.

```kotlin
fun applyTransitiveConstraintPropagation(substitution: Substitution): Substitution {
    var changed = true
    while (changed) {
        changed = false
        // Look for indirect relationships and propagate concrete types
        // Continue until no more changes occur (fixed point)
    }
}
```

## Components and Interfaces

### TypeChecker Enhancements

```kotlin
class TypeChecker {
    // Enhanced binary operation handling
    private fun inferBinaryOperationType(expr: BinaryOperation): FunkType {
        // Use context type variables directly
        // Generate unification constraints between operands
        // Return result type that maintains variable relationships
    }
    
    // Enhanced qualified call handling  
    private fun handleQualifiedCall(function: Identifier, argumentType: FunkType) {
        // Trace argument type back to original lambda parameter variables
        // Generate constraints using actual expression type variables
        // Ensure constraint propagation maintains variable consistency
    }
}
```

### ConstraintSolver Enhancements

```kotlin
class ConstraintSolver {
    // Enhanced constraint solving with transitive propagation
    fun solve(constraints: List<Constraint>): Substitution {
        var substitution = solveConstraints(constraints)
        substitution = applyTransitiveConstraintPropagation(substitution, constraints)
        return substitution
    }
    
    // New transitive propagation method
    private fun applyTransitiveConstraintPropagation(
        substitution: Substitution, 
        constraints: List<Constraint>
    ): Substitution {
        // Iteratively resolve indirect type variable relationships
        // Handle record unification transitive cases
        // Propagate concrete types through all connected variables
    }
}
```

## Data Models

### Type Variable Mapping

```kotlin
data class TypeVariableMapping(
    val lambdaParameters: Map<String, TypeVar>,  // x -> 'e, y -> 'h
    val expressionResults: Map<Expression, TypeVar>, // x+y -> 'i
    val constraintVariables: Map<String, TypeVar>    // Track constraint generation variables
)
```

### Constraint Tracing

```kotlin
data class ConstraintTrace(
    val constraint: Constraint,
    val sourceExpression: Expression?,
    val targetTypeVariable: TypeVar,
    val propagationPath: List<TypeVar>
)
```

## Error Handling

### Type Variable Consistency Validation

```kotlin
class TypeVariableValidator {
    fun validateConsistency(
        lambdaContext: Map<String, FunkType>,
        constraints: List<Constraint>
    ): ValidationResult {
        // Verify that constraint type variables map to lambda parameter variables
        // Detect disconnected type variable chains
        // Report inconsistencies with detailed diagnostic information
    }
}
```

### Debug Tools

```kotlin
class ConstraintDebugger {
    fun traceTypeVariableAssignments(expression: Expression): TypeVariableTrace
    fun visualizeConstraintGraph(constraints: List<Constraint>): ConstraintGraph
    fun validateConstraintPropagation(before: Context, after: Context): PropagationReport
}
```

## Testing Strategy

### Unit Tests

1. **Type Variable Consistency Tests**
   - Test that binary operations use lambda parameter type variables
   - Verify constraint generation maintains variable consistency
   - Test transitive constraint propagation

2. **Constraint Solver Tests**
   - Test transitive relationship resolution
   - Verify fixed-point constraint propagation
   - Test complex constraint chains

3. **Integration Tests**
   - Test complete constraint propagation flow
   - Verify qualified call constraint propagation
   - Test complex nested expressions

### Test Cases

```funk
// Basic constraint propagation
let test1 = {x, y} -> int::to_string {value = x + y}
// Expected: { x: Int, y: Int } -> String

// Nested record access
let test2 = {r} -> int::to_string {value = r.x + r.y}  
// Expected: { r: { x: Int, y: Int } } -> String

// Multiple operations
let test3 = {a, b, c} -> int::to_string {value = a + b + c}
// Expected: { a: Int, b: Int, c: Int } -> String

// Mixed types
let test4 = {x, y} -> float::to_string {value = x + y}
// Expected: { x: Float, y: Float } -> String
```

## Implementation Plan

### Phase 1: Type Variable Context Unification
- Modify binary operation type inference to use context variables
- Ensure expression evaluation maintains type variable consistency
- Add debug logging for type variable assignments

### Phase 2: Constraint Generation Consistency  
- Fix qualified call constraint generation to use expression variables
- Implement type variable tracing from constraints back to lambda parameters
- Add validation for constraint generation consistency

### Phase 3: Enhanced Constraint Solver
- Implement transitive constraint propagation algorithm
- Add fixed-point resolution for indirect type variable relationships
- Enhance substitution application to handle complex type variable chains

### Phase 4: Validation and Testing
- Implement comprehensive type variable consistency validation
- Add debug tools for constraint propagation tracing
- Create extensive test suite for all constraint propagation scenarios