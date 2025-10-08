# Requirements Document

## Introduction

This spec addresses critical issues in the Funk language implementation that need immediate attention. The issues include: undefined variable type checking errors, stdlib module loading problems, parser regressions, and incorrect operator implementations.

## Requirements

### Requirement 1: Undefined Variable Type Checking

**User Story:** As a developer, I want undefined variables to throw proper type errors instead of creating fresh type variables, so that I can catch typos and missing imports early.

#### Acceptance Criteria

1. WHEN a variable is referenced but not defined in scope THEN the typechecker SHALL report a clear "undefined variable" error
2. WHEN a qualified name like `int::to_string` is used but the module is not loaded THEN the typechecker SHALL report a "module not found" or "undefined qualified name" error
3. WHEN an undefined variable is encountered THEN the typechecker SHALL NOT create a fresh type variable as a fallback
4. WHEN stdlib functions are used without proper imports THEN the typechecker SHALL report appropriate import errors

### Requirement 2: Standard Library Module Loading

**User Story:** As a developer, I want the standard library to load correctly so that I can use built-in functions like `int::to_string` without errors.

#### Acceptance Criteria

1. WHEN the typechecker processes a file THEN it SHALL automatically load the standard library modules
2. WHEN `int::to_string` is used THEN it SHALL be recognized as a valid function from the stdlib
3. WHEN stdlib modules fail to load THEN the system SHALL provide clear error messages about what went wrong
4. WHEN the CLI processes a file THEN it SHALL ensure stdlib is available before type checking

### Requirement 3: Parser Regression Fix

**User Story:** As a developer, I want the parser to work correctly without syntax errors on valid stdlib files, so that the language remains functional.

#### Acceptance Criteria

1. WHEN parsing stdlib files THEN the parser SHALL not produce syntax errors on valid syntax
2. WHEN the parser encounters function signatures with named parameters THEN it SHALL parse them correctly
3. WHEN the parser processes operator definitions THEN it SHALL handle the syntax properly
4. WHEN parsing fails THEN the error messages SHALL be clear and actionable

### Requirement 4: Correct Operator Implementation

**User Story:** As a developer, I want operators like `not` to be implemented correctly as functions that take named arguments, so that the language semantics are consistent.

#### Acceptance Criteria

1. WHEN the `not` operator is defined THEN it SHALL take a Boolean value using named argument syntax `{ value: Boolean }`
2. WHEN the `!=` operator is implemented THEN it SHALL use the correct syntax: `{ left: 'a, right: 'a } -> Boolean with Eq 'a`
3. WHEN operators are inlined in the typechecker/interpreter THEN they SHALL maintain the same function signature semantics
4. WHEN operators are used THEN they SHALL work with the named argument calling convention

### Requirement 5: Stdlib Integration Strategy

**User Story:** As a developer, I want the option to inline stdlib operators in the typechecker and interpreter for performance, while maintaining correct syntax parsing.

#### Acceptance Criteria

1. WHEN stdlib operators are inlined THEN the typechecker SHALL recognize them without parsing the stdlib files
2. WHEN stdlib operators are inlined THEN the interpreter SHALL have native implementations available
3. WHEN inlining is used THEN it SHALL be possible to fall back to parsing stdlib files if needed
4. WHEN operators are inlined THEN their type signatures SHALL match the stdlib definitions exactly