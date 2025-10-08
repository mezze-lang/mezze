# Qualified Call Support Requirements

## Introduction

This feature implements qualified call syntax (e.g., `int::to_string`, `string::length`) to enable direct access to module functions with proper type inference and constraint propagation. This addresses the core issue where constraint propagation fails through `do` expressions when using module functions.

## Requirements

### Requirement 1: Basic Qualified Call Syntax

**User Story:** As a developer, I want to call module functions using qualified syntax, so that I can access stdlib functions directly without imports.

#### Acceptance Criteria

1. WHEN I write `int::to_string {value = x}` THEN the system SHALL recognize it as a qualified call
2. WHEN I use qualified call syntax THEN the system SHALL parse the module and function name correctly
3. WHEN I use invalid qualified call format THEN the system SHALL provide clear error messages

### Requirement 2: Module Resolution

**User Story:** As a developer, I want qualified calls to resolve to actual module functions, so that I get proper type checking and functionality.

#### Acceptance Criteria

1. WHEN I use `int::to_string` THEN the system SHALL resolve it to `std::types::int::to_string`
2. WHEN I use `string::length` THEN the system SHALL resolve it to `std::types::string::length`
3. WHEN I use an invalid module name THEN the system SHALL report a module not found error
4. WHEN I use an invalid function name THEN the system SHALL report a function not found error

### Requirement 3: Type Inference and Constraint Propagation

**User Story:** As a developer, I want qualified calls to properly infer types and propagate constraints, so that type checking works correctly in complex expressions.

#### Acceptance Criteria

1. WHEN I write `{x, y} -> int::to_string {value = x + y}` THEN the system SHALL infer `{ x: Int, y: Int } -> String`
2. WHEN I use qualified calls in `do` expressions THEN constraints SHALL propagate through `let` bindings
3. WHEN I write `do { let sum = x + y; int::to_string {value = sum} }` THEN `x` and `y` SHALL be constrained to `Int`
4. WHEN constraint propagation fails THEN the system SHALL provide meaningful error messages

### Requirement 4: Standard Library Integration

**User Story:** As a developer, I want qualified calls to work with all stdlib modules, so that I have consistent access to built-in functionality.

#### Acceptance Criteria

1. WHEN I use `int::to_string` THEN it SHALL work with Int values
2. WHEN I use `float::to_string` THEN it SHALL work with Float values
3. WHEN I use `string::length` THEN it SHALL work with String values
4. WHEN I use `boolean::to_string` THEN it SHALL work with Boolean values
5. WHEN new stdlib modules are added THEN qualified calls SHALL work automatically

### Requirement 5: Error Handling and Diagnostics

**User Story:** As a developer, I want clear error messages for qualified call issues, so that I can debug problems quickly.

#### Acceptance Criteria

1. WHEN module resolution fails THEN the system SHALL report which module was not found
2. WHEN function resolution fails THEN the system SHALL report which function was not found in which module
3. WHEN type constraints fail THEN the system SHALL show the expected vs actual types
4. WHEN qualified call syntax is malformed THEN the system SHALL suggest correct syntax