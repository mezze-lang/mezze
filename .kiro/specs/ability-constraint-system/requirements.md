# Requirements Document

## Introduction

The current Funk type system performs basic type unification for polymorphic functions but lacks ability constraints to properly express type class-like constraints. For example, a polymorphic addition function currently shows type `{ x: 'a, y: 'a } -> 'a` but should show `{ x: 'a, y: 'a } with NumAdd 'a -> 'a` to indicate that the type variable `'a` must support addition operations. This spec defines the requirements for implementing a comprehensive ability constraint system.

## Requirements

### Requirement 1: Ability Constraint Declaration

**User Story:** As a developer defining polymorphic functions, I want to declare ability constraints on type variables so that the type system can enforce proper usage of operations.

#### Acceptance Criteria

1. WHEN I define a function using arithmetic operations on polymorphic types THEN the system SHALL automatically infer appropriate ability constraints
2. WHEN I use the `+` operator on type variable `'a` THEN the system SHALL add constraint `NumAdd 'a`
3. WHEN I use comparison operators on type variable `'a` THEN the system SHALL add constraint `Eq 'a` or `Ord 'a` as appropriate
4. WHEN I use multiple operations on the same type variable THEN the system SHALL combine all required constraints
5. WHEN I define explicit ability constraints THEN the system SHALL validate that all operations are consistent with declared abilities

### Requirement 2: Constraint Inference and Propagation

**User Story:** As a developer writing complex polymorphic code, I want the type system to automatically infer and propagate ability constraints so that I don't need to manually specify every constraint.

#### Acceptance Criteria

1. WHEN I call a function with ability constraints THEN the system SHALL propagate those constraints to the calling context
2. WHEN I compose functions with different ability requirements THEN the system SHALL merge constraints appropriately
3. WHEN I use higher-order functions THEN the system SHALL properly track constraints through function parameters
4. WHEN constraint propagation creates conflicts THEN the system SHALL report clear error messages
5. WHEN I have nested polymorphic calls THEN the system SHALL maintain constraint consistency throughout the call chain

### Requirement 3: Built-in Ability Definitions

**User Story:** As a developer using standard operations, I want built-in types to have predefined abilities so that common operations work seamlessly with the constraint system.

#### Acceptance Criteria

1. WHEN I use `Int` type THEN it SHALL have abilities `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `Eq`, `Ord`
2. WHEN I use `Frac` type THEN it SHALL have abilities `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `Eq`, `Ord`
3. WHEN I use `Bool` type THEN it SHALL have abilities `Eq`, `LogicalAnd`, `LogicalOr`, `LogicalNot`
4. WHEN I use `String` type THEN it SHALL have abilities `Eq`, `Ord`, `StringConcat`
5. WHEN I define custom types THEN I SHALL be able to declare which abilities they implement

### Requirement 4: Type Display with Constraints

**User Story:** As a developer debugging type issues, I want to see ability constraints in type signatures so that I can understand what operations are available on polymorphic types.

#### Acceptance Criteria

1. WHEN I check the type of a polymorphic function THEN the system SHALL display constraints in the format `{ x: 'a, y: 'a } with NumAdd 'a -> 'a`
2. WHEN a function has multiple constraints THEN the system SHALL display them clearly like `'a with NumAdd 'a, Eq 'a`
3. WHEN constraints are complex THEN the system SHALL format them readably with proper grouping
4. WHEN I hover over a polymorphic expression THEN the IDE SHALL show inferred constraints
5. WHEN constraints are satisfied by concrete types THEN the system SHALL show the resolved concrete type

### Requirement 5: Constraint Validation and Error Reporting

**User Story:** As a developer making type errors, I want clear error messages about constraint violations so that I can quickly understand and fix the issues.

#### Acceptance Criteria

1. WHEN I try to use an operation on a type that doesn't support it THEN the system SHALL report which ability is missing
2. WHEN I pass a type to a function requiring specific abilities THEN the system SHALL validate the type has those abilities
3. WHEN constraint solving fails THEN the system SHALL explain which constraints couldn't be satisfied
4. WHEN I have conflicting constraints THEN the system SHALL identify the source of the conflict
5. WHEN I need to add an ability implementation THEN the system SHALL suggest how to resolve the constraint

### Requirement 6: Arithmetic Operation Constraints

**User Story:** As a developer using arithmetic operations, I want the type system to properly track which types support which arithmetic operations so that I can write safe polymorphic numeric code.

#### Acceptance Criteria

1. WHEN I use `+` operator THEN the system SHALL require `NumAdd` ability on operand types
2. WHEN I use `-` operator THEN the system SHALL require `NumSub` ability on operand types
3. WHEN I use `*` operator THEN the system SHALL require `NumMul` ability on operand types
4. WHEN I use `/` operator THEN the system SHALL require `NumDiv` ability on operand types
5. WHEN I mix types with different numeric abilities THEN the system SHALL report appropriate constraint violations

### Requirement 7: Comparison Operation Constraints

**User Story:** As a developer using comparison operations, I want the type system to track equality and ordering constraints so that I can write safe polymorphic comparison code.

#### Acceptance Criteria

1. WHEN I use `==` or `!=` operators THEN the system SHALL require `Eq` ability on operand types
2. WHEN I use `<`, `>`, `<=`, `>=` operators THEN the system SHALL require `Ord` ability on operand types
3. WHEN I use comparison in polymorphic contexts THEN the system SHALL propagate appropriate constraints
4. WHEN I compare types without proper abilities THEN the system SHALL report missing constraint errors
5. WHEN I use comparison results in further operations THEN the system SHALL maintain constraint consistency

### Requirement 8: Complex Expression Type Inference

**User Story:** As a developer writing complex expressions with mixed operations, I want the type system to correctly infer concrete types from polymorphic operations so that my code type checks accurately.

#### Acceptance Criteria

1. WHEN I write `{x, y} -> do let sum = x + y int::to_string {value = sum}` THEN the system SHALL infer type `{x: Int, y: Int} -> String`
2. WHEN I use arithmetic operations followed by qualified function calls THEN the system SHALL resolve polymorphic types to concrete types
3. WHEN I have nested do expressions with mixed operations THEN the system SHALL maintain type consistency throughout
4. WHEN I use let bindings in complex expressions THEN the system SHALL properly propagate inferred types
5. WHEN polymorphic operations are constrained by subsequent usage THEN the system SHALL resolve to the most specific valid type

### Requirement 9: Constraint Solver Integration

**User Story:** As a developer using complex type inference, I want the constraint solver to handle ability constraints efficiently so that type checking remains fast and accurate.

#### Acceptance Criteria

1. WHEN the constraint solver encounters ability constraints THEN it SHALL integrate them with existing unification
2. WHEN solving constraints with abilities THEN the system SHALL maintain polynomial time complexity
3. WHEN constraints are unsolvable THEN the solver SHALL terminate gracefully with helpful errors
4. WHEN multiple solutions exist THEN the solver SHALL choose the most general valid solution
5. WHEN constraint solving involves recursive types THEN the system SHALL handle them correctly with abilities

### Requirement 10: Module Resolution with Qualified Syntax

**User Story:** As a developer using functions from different modules, I want to call qualified functions with proper type resolution so that I can use module-scoped functions with ability constraints.

#### Acceptance Criteria

1. WHEN I call a qualified function like `int::to_string` THEN the system SHALL resolve the function from the correct module
2. WHEN I use qualified functions in polymorphic contexts THEN the system SHALL properly infer concrete types
3. WHEN I chain operations with qualified calls THEN the system SHALL propagate types correctly through the chain
4. WHEN a qualified function has ability constraints THEN the system SHALL validate them at the call site
5. WHEN module resolution fails THEN the system SHALL report clear errors about missing modules or functions

### Requirement 11: Backward Compatibility

**User Story:** As a developer with existing Funk code, I want the ability constraint system to work with my current code so that I don't need to rewrite everything.

#### Acceptance Criteria

1. WHEN I have existing polymorphic functions THEN they SHALL continue to work with inferred constraints
2. WHEN I use concrete types in operations THEN the system SHALL automatically satisfy constraints
3. WHEN I have existing type annotations THEN they SHALL be compatible with the new constraint system
4. WHEN I gradually adopt explicit constraints THEN the system SHALL support mixed usage patterns
5. WHEN migrating to the new system THEN existing code SHALL not break due to constraint additions