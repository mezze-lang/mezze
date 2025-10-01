# Requirements Document

## Introduction

The current Funk standard library contains numerous syntax errors that prevent it from being parsed and executed correctly. The Option and Result types, along with other stdlib modules, use incorrect syntax for variant types, match expressions, type variables, and function definitions. This needs to be fixed to align with the actual Funk language grammar and make the stdlib functional.

## Requirements

### Requirement 1: Fix Variant Type Syntax

**User Story:** As a Funk developer, I want Option and Result types to use correct variant syntax, so that they can be parsed and used in my programs.

#### Acceptance Criteria

1. WHEN defining variant types THEN the system SHALL use the correct syntax `type Name = | Constructor1, Constructor2 { field: Type } |`
2. WHEN defining Option type THEN the system SHALL use `type Option 'a = | Some { value: 'a }, None |`
3. WHEN defining Result type THEN the system SHALL use `type Result 'a 'e = | Ok { value: 'a }, Err { error: 'e } |`
4. WHEN parsing variant types THEN the system SHALL successfully parse without syntax errors

### Requirement 2: Fix Match Expression Syntax

**User Story:** As a Funk developer, I want match expressions in the stdlib to use correct syntax, so that pattern matching works properly.

#### Acceptance Criteria

1. WHEN writing match expressions THEN the system SHALL use the syntax `match expr is pattern -> result`
2. WHEN matching variant constructors THEN the system SHALL use patterns like `Some { value } -> value`
3. WHEN matching multiple patterns THEN the system SHALL use `pattern1 | pattern2 -> result`
4. WHEN parsing match expressions THEN the system SHALL successfully parse without syntax errors

### Requirement 3: Fix Function Definition Syntax

**User Story:** As a Funk developer, I want stdlib functions to use correct Funk syntax, so that they can be called and executed properly.

#### Acceptance Criteria

1. WHEN defining functions THEN the system SHALL use the syntax `let name = { params } -> body`
2. WHEN defining typed functions THEN the system SHALL use `let name: { param: Type } -> ReturnType = { param } -> body`
3. WHEN defining polymorphic functions THEN the system SHALL use type variables like `'a`, `'b`
4. WHEN parsing function definitions THEN the system SHALL successfully parse without syntax errors

### Requirement 4: Fix Type Variable Syntax

**User Story:** As a Funk developer, I want type variables in the stdlib to use correct syntax, so that polymorphic types work properly.

#### Acceptance Criteria

1. WHEN using type variables THEN the system SHALL use the syntax `'a`, `'b`, `'c`
2. WHEN defining polymorphic types THEN the system SHALL use `type Name 'a = ...`
3. WHEN using type variables in functions THEN the system SHALL maintain consistency across the signature
4. WHEN parsing type variables THEN the system SHALL successfully parse without syntax errors

### Requirement 5: Update Native Function Integration

**User Story:** As a Funk developer, I want native functions to integrate properly with the corrected stdlib syntax, so that built-in operations work seamlessly.

#### Acceptance Criteria

1. WHEN calling native functions THEN the system SHALL use correct parameter passing syntax
2. WHEN native functions return values THEN the system SHALL properly construct variant types
3. WHEN integrating with abilities THEN the system SHALL use correct ability syntax
4. WHEN executing native operations THEN the system SHALL work with the corrected stdlib types

### Requirement 6: Maintain Backward Compatibility

**User Story:** As a Funk developer, I want existing code that depends on stdlib to continue working, so that my programs don't break.

#### Acceptance Criteria

1. WHEN updating stdlib syntax THEN the system SHALL maintain the same public API
2. WHEN changing internal implementation THEN the system SHALL preserve external behavior
3. WHEN fixing syntax errors THEN the system SHALL not break existing functionality
4. WHEN running tests THEN the system SHALL pass all existing stdlib tests