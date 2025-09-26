# Requirements Document

## Introduction

The SimpleTypeChecker has achieved excellent progress as a drop-in replacement for the original TypeChecker, with 90.6% test pass rate (346 out of 382 tests passing). This spec focuses on completing the remaining features to achieve 100% compatibility and full production readiness.

## Requirements

### Requirement 1: Unary Operator Support

**User Story:** As a developer using the Funk language, I want unary operators (!, unary -) to work correctly so that I can write boolean negation and numeric negation expressions.

#### Acceptance Criteria

1. WHEN I use the unary negation operator (!) on a boolean expression THEN the system SHALL return a boolean type
2. WHEN I use the unary minus operator (-) on a numeric expression THEN the system SHALL return the same numeric type
3. WHEN I use unary operators in complex expressions THEN the system SHALL properly propagate type constraints
4. WHEN I use nested unary operations THEN the system SHALL handle them correctly
5. IF I use a unary operator on an incompatible type THEN the system SHALL report a clear type error

### Requirement 2: Array Built-in Functions

**User Story:** As a developer working with arrays, I want built-in array functions (arrayLength, arrayGet, arraySet, etc.) to be properly type-checked so that I can safely manipulate arrays.

#### Acceptance Criteria

1. WHEN I use arrayLength function THEN the system SHALL infer it returns IntType
2. WHEN I use arrayGet function THEN the system SHALL infer it returns the array's element type
3. WHEN I use arraySet function THEN the system SHALL verify type compatibility and return the array type
4. WHEN I use array functions with wrong argument types THEN the system SHALL report appropriate type errors
5. WHEN I chain array operations THEN the system SHALL properly propagate types through the chain

### Requirement 3: Advanced Constraint Solving

**User Story:** As a developer writing complex expressions, I want the type checker to handle advanced constraint propagation so that complex type inference scenarios work correctly.

#### Acceptance Criteria

1. WHEN I write complex nested expressions THEN the system SHALL solve all type constraints correctly
2. WHEN I use higher-order functions with complex parameter patterns THEN the system SHALL infer types accurately
3. WHEN I have circular type dependencies THEN the system SHALL either resolve them or report clear errors
4. WHEN I use polymorphic functions in complex contexts THEN the system SHALL maintain type safety
5. IF constraint solving fails THEN the system SHALL provide helpful error messages

### Requirement 4: Arithmetic Type Rules

**User Story:** As a developer using numeric operations, I want clear and consistent arithmetic type rules so that I can predict the types of my expressions.

#### Acceptance Criteria

1. WHEN I divide two integers (Int / Int) THEN the system SHALL return Int type (integer division)
2. WHEN I write a fraction literal using // syntax with integer literals only (like 5//6, no spaces allowed) THEN the system SHALL infer Frac type
3. WHEN I attempt mixed arithmetic (Frac + Int, Int + Frac, etc.) THEN the system SHALL report a type error
4. WHEN I use arithmetic operators on same types (Int + Int, Frac + Frac) THEN the system SHALL return the same type
5. WHEN I use Nat with Int operations THEN the system SHALL allow promotion from Nat to Int
6. WHEN I use // with variables or expressions (not integer literals) THEN the system SHALL report a type error

### Requirement 5: Edge Case Handling

**User Story:** As a developer, I want the type checker to handle edge cases gracefully so that I get predictable behavior in unusual scenarios.

#### Acceptance Criteria

1. WHEN I have empty arrays in complex contexts THEN the system SHALL handle type variable resolution correctly
2. WHEN I have deeply nested record structures THEN the system SHALL maintain performance and correctness
3. WHEN I have very large expressions THEN the system SHALL not crash or hang
4. WHEN I have malformed input THEN the system SHALL report clear, actionable error messages
5. WHEN I have complex type inference scenarios THEN the system SHALL resolve them correctly or report clear errors

### Requirement 6: Error Reporting Enhancement

**User Story:** As a developer debugging type errors, I want clear and helpful error messages so that I can quickly identify and fix type issues.

#### Acceptance Criteria

1. WHEN a type error occurs THEN the system SHALL provide the exact location of the error
2. WHEN types don't match THEN the system SHALL show both expected and actual types clearly
3. WHEN constraint solving fails THEN the system SHALL explain which constraints couldn't be satisfied
4. WHEN I have complex expressions with errors THEN the system SHALL point to the specific problematic sub-expression
5. WHEN multiple errors exist THEN the system SHALL report them in a logical order

### Requirement 7: Performance Optimization

**User Story:** As a developer working on large codebases, I want the type checker to be fast so that I can get quick feedback during development.

#### Acceptance Criteria

1. WHEN I type-check large programs THEN the system SHALL complete in reasonable time (< 5 seconds for typical programs)
2. WHEN I have deeply nested structures THEN the system SHALL not exhibit exponential slowdown
3. WHEN I run the same type checking multiple times THEN the system SHALL maintain consistent performance
4. WHEN I have many type variables THEN the system SHALL efficiently manage constraint solving
5. WHEN memory usage grows THEN the system SHALL not leak memory or grow unboundedly

### Requirement 8: Test Suite Completion

**User Story:** As a maintainer of the type checker, I want 100% of the original tests to pass so that I can be confident in the drop-in replacement.

#### Acceptance Criteria

1. WHEN I run all original TypeChecker tests THEN the system SHALL pass 100% of them
2. WHEN I run comprehensive test suites THEN the system SHALL demonstrate identical behavior to the original
3. WHEN I test edge cases THEN the system SHALL handle them as well as the original TypeChecker
4. WHEN I run performance benchmarks THEN the system SHALL meet or exceed original performance
5. WHEN I test error scenarios THEN the system SHALL provide equivalent or better error messages

### Requirement 9: Documentation and Migration Guide

**User Story:** As a developer migrating from TypeChecker to SimpleTypeChecker, I want clear documentation so that I can understand the differences and migration path.

#### Acceptance Criteria

1. WHEN I read the documentation THEN I SHALL understand exactly what features are supported
2. WHEN I need to migrate existing code THEN I SHALL have a clear step-by-step guide
3. WHEN I encounter differences THEN I SHALL have documentation explaining the rationale
4. WHEN I need to troubleshoot issues THEN I SHALL have debugging guides available
5. WHEN I want to contribute THEN I SHALL have clear development setup instructions