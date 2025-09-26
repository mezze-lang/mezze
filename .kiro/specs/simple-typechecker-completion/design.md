# Design Document

## Overview

This design document outlines the technical approach for completing the SimpleTypeChecker to achieve 100% compatibility with the original TypeChecker. The focus is on implementing the remaining features while maintaining the existing architecture and ensuring robust type inference and error reporting.

## Architecture

### Core Components

The SimpleTypeChecker maintains the existing modular architecture:

1. **TypeChecker.kt** - Main type inference engine with constraint-based solving
2. **BidirectionalTypeChecker.kt** - Specialized bidirectional inference for complex cases
3. **ConstraintSolver.kt** - Constraint resolution and unification
4. **ModuleResolver.kt** - Module system and import handling
5. **ErrorPrettyPrinter.kt** - User-friendly error message generation

### Type Inference Strategy

The design uses a hybrid approach combining:
- **Hindley-Milner** style inference for basic cases
- **Bidirectional typing** for complex polymorphic scenarios
- **Constraint-based solving** for advanced type relationships

## Components and Interfaces

### 1. Unary Operator Support

**Design Approach:**
- Extend existing binary operator infrastructure to handle unary operators
- Add unary operator validation in `validateArithmeticOperation`
- Implement type inference for `!` (boolean negation) and `-` (numeric negation)

**Key Interfaces:**
```kotlin
// Extend existing operator handling
private fun inferUnaryOperation(expr: UnaryOperation): FunkType
private fun validateUnaryOperation(operator: String, operandType: FunkType, expr: UnaryOperation)
```

### 2. Array Built-in Functions

**Design Approach:**
- Leverage existing native function type system
- Add array function signatures to `NativeFunctionTypes.kt`
- Implement polymorphic array element type handling

**Key Functions:**
- `arrayLength: { arr: Array 'a } -> Int`
- `arrayGet: { arr: Array 'a, index: Int } -> 'a`
- `arraySet: { arr: Array 'a, index: Int, value: 'a } -> Array 'a`

### 3. Arithmetic Type Rules

**Design Approach:**
- Implement clear separation between division (`/`) and fraction literals (`//`)
- Enforce strict type compatibility rules
- Prevent mixed arithmetic operations

**Type Rules:**
```
Int / Int -> Int           (integer division)
IntLiteral//IntLiteral -> Frac  (fraction literal, no spaces allowed)
Frac + Frac -> Frac        (same type operations)
Frac + Int -> ERROR        (mixed arithmetic forbidden)
Nat + Int -> Int           (Nat promotes to Int)
```

### 4. Advanced Constraint Solving

**Design Approach:**
- Enhance existing constraint solver with better cycle detection
- Improve constraint propagation for complex nested expressions
- Add timeout mechanisms for constraint solving

**Key Enhancements:**
- Constraint dependency graph analysis
- Incremental constraint solving
- Better error reporting for unsolvable constraints

### 5. Error Reporting Enhancement

**Design Approach:**
- Extend `ErrorPrettyPrinter` with more detailed context
- Add source location tracking throughout inference
- Implement error recovery strategies

**Error Categories:**
- Type mismatch errors with expected vs actual types
- Constraint solving failures with explanation
- Missing function/variable errors with suggestions
- Exhaustiveness checking errors with missing cases

### 6. Performance Optimization

**Design Approach:**
- Implement memoization for repeated type computations
- Add early termination for constraint solving
- Optimize type variable management

**Optimization Strategies:**
- Type cache for frequently accessed types
- Constraint solver optimizations
- Memory management improvements

## Data Models

### Type System Extensions

```kotlin
// Enhanced type representations
sealed class FunkType {
    // Existing types...
    
    // Enhanced constraint tracking
    data class ConstrainedType(
        val baseType: FunkType,
        val constraints: Set<TypeConstraint>
    ) : FunkType()
}

// Improved error tracking
data class TypeCheckError(
    val message: String,
    val location: SourceLocation,
    val context: TypeContext,
    val suggestions: List<String> = emptyList()
)
```

### Constraint System

```kotlin
// Enhanced constraint representation
sealed class TypeConstraint {
    data class Equality(val left: FunkType, val right: FunkType) : TypeConstraint()
    data class Subtype(val subtype: FunkType, val supertype: FunkType) : TypeConstraint()
    data class AbilityConstraint(val type: FunkType, val ability: String) : TypeConstraint()
    data class ArithmeticConstraint(val type: FunkType, val operation: String) : TypeConstraint()
}
```

## Error Handling

### Error Recovery Strategy

1. **Local Error Recovery** - Continue type checking after encountering errors
2. **Constraint Relaxation** - Provide partial solutions when full solving fails
3. **Suggestion Generation** - Offer fix suggestions based on common patterns

### Error Message Design

```
Type Error at line 15, column 8:
  Cannot mix different numeric types in arithmetic
  
  Expected: Frac
  Actual:   Int
  
  Suggestion: Convert the integer to a fraction using // syntax:
    let result = half + (2//1)
```

## Testing Strategy

### Test Categories

1. **Unit Tests** - Individual component testing
2. **Integration Tests** - Full type checking pipeline
3. **Regression Tests** - Ensure compatibility with original TypeChecker
4. **Performance Tests** - Validate optimization improvements

### Test Coverage Goals

- 100% compatibility with original TypeChecker test suite
- Edge case coverage for new features
- Performance benchmarks for large codebases
- Error message quality validation

### Fixture-Based Testing

Maintain existing fixture-based test structure:
- `valid/` - Programs that should type check successfully
- `invalid/` - Programs that should fail with specific errors
- Expected type annotations in comments

## Implementation Phases

### Phase 1: Core Feature Implementation
- Unary operators
- Array built-in functions
- Arithmetic type rules with `//` syntax

### Phase 2: Advanced Features
- Enhanced constraint solving
- Improved error reporting
- Performance optimizations

### Phase 3: Compatibility and Polish
- 100% test suite compatibility
- Documentation updates
- Migration guide creation

## Migration Considerations

### Backward Compatibility

- Maintain existing API surface
- Support both `/` and `//` for fractions during transition
- Preserve existing error message formats where possible

### Breaking Changes

- Mixed arithmetic operations now forbidden
- Stricter type checking in some edge cases
- Enhanced error messages may differ slightly

## Performance Targets

- Type checking time: < 5 seconds for typical programs
- Memory usage: Linear growth with program size
- Constraint solving: Polynomial time complexity
- Test suite execution: < 30 seconds for full suite

## Quality Assurance

### Code Quality Metrics

- Test coverage: > 90%
- Performance regression: < 10% slowdown
- Error message quality: User testing validation
- Documentation completeness: 100% API coverage

### Validation Criteria

- All original TypeChecker tests pass
- New features work as specified
- Performance meets targets
- Error messages are helpful and actionable