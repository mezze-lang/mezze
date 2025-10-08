# Constraint System Regression Fixes

## Introduction

After implementing the comprehensive constraint-based type system, we successfully achieved polymorphic type inference with ability constraints (e.g., `{ x, y } -> x + y` correctly infers as `{ x: 'a, y: 'a } with NumAdd 'a -> 'a`). However, this introduced 69 failing tests that need systematic fixes while preserving our core achievements.

## Requirements

### Requirement 1: Qualified Function Call Type Resolution

**User Story:** As a developer using qualified function calls like `int::add`, I want these to resolve to concrete types rather than polymorphic types with ability constraints.

#### Acceptance Criteria

1. WHEN I use `int::add {left = x, right = y}` THEN the system SHALL infer `x` and `y` as `Int` type
2. WHEN I use `float::multiply {left = a, right = b}` THEN the system SHALL infer `a` and `b` as `Float` type  
3. WHEN I use qualified calls THEN the system SHALL NOT generate ability constraints
4. WHEN I use qualified calls THEN the return type SHALL be the concrete type of the module
5. WHEN I nest qualified calls like `int::add {left = int::multiply {left = a, right = b}, right = c}` THEN all parameters SHALL be inferred as `Int`

### Requirement 2: Polymorphic Operator Preservation

**User Story:** As a developer using polymorphic operators, I want the constraint-based system to continue working correctly for unqualified operators.

#### Acceptance Criteria

1. WHEN I use `{x, y} -> x + y` THEN the system SHALL generate ability constraints
2. WHEN I use `{x, y} -> x + y` THEN the system SHALL infer `{ x: 'a, y: 'a } with NumAdd 'a -> 'a`
3. WHEN I use concrete types like `1 + 2` THEN the system SHALL resolve to `Int`
4. WHEN I use mixed scenarios THEN polymorphic and concrete inference SHALL coexist

### Requirement 3: Effect System Integration

**User Story:** As a developer using effects, I want the constraint-based system to work seamlessly with effect handling.

#### Acceptance Criteria

1. WHEN I use effects in constraint-based inference THEN effect types SHALL be preserved
2. WHEN I use `perform` expressions THEN constraint generation SHALL work correctly
3. WHEN I use effect handlers THEN constraint solving SHALL not interfere with effect resolution
4. WHEN I use do expressions with effects THEN constraints SHALL propagate correctly

### Requirement 4: Do Expression Constraint Handling

**User Story:** As a developer using do expressions, I want constraint-based inference to work correctly within do blocks.

#### Acceptance Criteria

1. WHEN I use do expressions in lambdas THEN constraint generation SHALL work correctly
2. WHEN I have nested do expressions THEN constraint propagation SHALL be accurate
3. WHEN I use let statements in do blocks THEN variable types SHALL be inferred correctly
4. WHEN I use do expressions with effects THEN both constraints and effects SHALL be handled

### Requirement 5: Error Reporting Compatibility

**User Story:** As a developer, I want error reporting to work correctly with the new constraint-based system.

#### Acceptance Criteria

1. WHEN type checking fails THEN error messages SHALL be clear and accurate
2. WHEN constraint solving fails THEN error reporting SHALL indicate the constraint violation
3. WHEN ability constraints are violated THEN error messages SHALL mention the missing ability
4. WHEN qualified calls fail THEN error messages SHALL be specific to the qualified function

### Requirement 6: Fixture-Based Test Compatibility

**User Story:** As a developer, I want existing fixture-based tests to continue passing with the new constraint system.

#### Acceptance Criteria

1. WHEN running fixture tests for polymorphic arithmetic THEN all tests SHALL pass
2. WHEN running fixture tests for simple functions THEN type inference SHALL be correct
3. WHEN running fixture tests for numeric functions THEN ability constraints SHALL be handled properly
4. WHEN running comprehensive fixture tests THEN no regressions SHALL occur

### Requirement 7: Arithmetic Constraint Test Fixes

**User Story:** As a developer, I want arithmetic constraint tests to work correctly with both polymorphic and concrete type scenarios.

#### Acceptance Criteria

1. WHEN testing concrete arithmetic operations THEN types SHALL resolve to concrete types
2. WHEN testing polymorphic arithmetic operations THEN ability constraints SHALL be generated
3. WHEN testing mixed arithmetic scenarios THEN both concrete and polymorphic inference SHALL work
4. WHEN testing constraint propagation THEN type unification SHALL be accurate

### Requirement 8: CLI and Test Path Consistency

**User Story:** As a developer, I want the constraint system to generate the same ability constraints regardless of whether I'm using the CLI, running tests, or using the type checker directly, so that I get consistent type information.

#### Acceptance Criteria

1. WHEN I type check `{x, y} -> x + y` through the CLI THEN the system SHALL display `{ x: 'a, y: 'a } with NumAdd 'a -> 'a`
2. WHEN I type check the same expression through unit tests THEN the system SHALL return the same type with ability constraints
3. WHEN I use the TypeChecker class directly THEN the system SHALL generate identical constraint information
4. WHEN I check polymorphic arithmetic functions THEN the system SHALL consistently generate NumAdd, NumSub, NumMul, and NumDiv constraints as appropriate
5. WHEN the AddFunctionDebugTest runs THEN it SHALL pass with the correct constraint display

### Requirement 9: Bidirectional Type Checker Integration

**User Story:** As a developer, I want the bidirectional type checker to properly integrate with the constraint system, so that polymorphic functions get the correct ability constraints.

#### Acceptance Criteria

1. WHEN the bidirectional type checker infers a lambda type THEN it SHALL analyze the lambda body for arithmetic operations
2. WHEN arithmetic operations are found in lambda bodies THEN the system SHALL generate appropriate ability constraints
3. WHEN parameters are involved in arithmetic operations THEN the system SHALL create constraints on the parameter type variables
4. WHEN multiple arithmetic operations use the same type variable THEN the system SHALL deduplicate constraints appropriately
5. WHEN the lambda uses concrete types THEN the system SHALL not generate unnecessary polymorphic constraints

### Requirement 10: Array Type Inference Fixes

**User Story:** As a developer, I want array type inference to work correctly with the new constraint system.

#### Acceptance Criteria

1. WHEN I create arrays with explicit type annotations THEN the system SHALL infer the correct array type
2. WHEN I create empty arrays THEN the system SHALL infer appropriate generic array types
3. WHEN I create arrays with mixed expressions THEN the system SHALL unify element types correctly
4. WHEN I use nested arrays THEN the system SHALL handle multi-dimensional array types
5. WHEN ArrayListTypeCheckerTest runs THEN all tests SHALL pass