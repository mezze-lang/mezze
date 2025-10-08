# Requirements Document

## Introduction

The constraint propagation system has a critical type variable unification issue where lambda parameter type variables are disconnected from constraint generation type variables. This prevents proper type inference in expressions like `{x, y} -> int::to_string {value = x + y}` where both `x` and `y` should be inferred as `Int`.

## Requirements

### Requirement 1: Type Variable Consistency

**User Story:** As a developer using the type checker, I want lambda parameter types to be correctly inferred through constraint propagation, so that expressions like `{x, y} -> int::to_string {value = x + y}` correctly infer both parameters as `Int`.

#### Acceptance Criteria

1. WHEN a lambda expression contains binary operations THEN the operands SHALL use the same type variables as the lambda parameters
2. WHEN constraint propagation occurs THEN type variables SHALL be consistent across all expression contexts
3. WHEN qualified calls force concrete types THEN the constraints SHALL propagate back to lambda parameters using the same type variables
4. WHEN binary operations are processed THEN operand unification SHALL use lambda parameter type variables directly

### Requirement 2: Constraint Generation Unification

**User Story:** As a type system developer, I want constraint generation to maintain type variable consistency, so that constraints properly connect lambda parameters to expression results.

#### Acceptance Criteria

1. WHEN binary operations generate constraints THEN they SHALL use lambda parameter type variables ('e, 'h) not new variables ('a, 'b)
2. WHEN qualified calls generate constraints THEN they SHALL reference the actual expression type variables
3. WHEN record unification occurs THEN field type variables SHALL map to the original lambda parameter variables
4. WHEN constraint solving completes THEN substitutions SHALL apply to lambda parameter types correctly

### Requirement 3: Transitive Constraint Resolution

**User Story:** As a constraint solver, I want to handle transitive type variable relationships, so that constraints like `'a = Int` and `'b = 'a` properly resolve to `'b = Int`.

#### Acceptance Criteria

1. WHEN constraints form transitive chains THEN the solver SHALL resolve all related type variables
2. WHEN record unification creates indirect relationships THEN transitive propagation SHALL occur
3. WHEN substitutions are applied THEN they SHALL propagate through all connected type variables
4. WHEN constraint solving completes THEN all reachable type variables SHALL be resolved to concrete types

### Requirement 4: Debug and Validation Tools

**User Story:** As a developer debugging type inference issues, I want comprehensive debug tools to trace type variable assignments, so that I can understand and fix type variable consistency problems.

#### Acceptance Criteria

1. WHEN debug mode is enabled THEN type variable assignments SHALL be logged with context information
2. WHEN constraint generation occurs THEN the mapping between expression and lambda variables SHALL be traceable
3. WHEN constraint solving runs THEN transitive relationships SHALL be visible in debug output
4. WHEN type inference completes THEN the final type variable mappings SHALL be clearly displayed