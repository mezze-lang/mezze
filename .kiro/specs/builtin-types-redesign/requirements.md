# Requirements Document

## Introduction

This specification defines a redesign of the Funk language's type system and standard library to move core types, abilities, and operators from the stdlib into the language runtime as built-in constructs. This will eliminate circular dependencies, improve ergonomics, and follow established patterns from other functional languages.

## Requirements

### Requirement 1: Built-in Core Types

**User Story:** As a Funk developer, I want core types like Option, Result, List, and Map to be available without imports, so that I can write code without worrying about module dependencies.

#### Acceptance Criteria

1. WHEN I write Funk code THEN Option, Result, List, Map, and other core types SHALL be automatically available in scope
2. WHEN I use Option 'a THEN the type SHALL be recognized without any import statements
3. WHEN I construct Some { val = x } or None THEN the constructors SHALL be available without imports
4. WHEN I use list syntax [1, 2, 3] THEN it SHALL create a List type without requiring imports
5. WHEN I use map syntax { key1 = val1, key2 = val2 } THEN it SHALL create a Map type without requiring imports

### Requirement 2: Built-in Basic Operators

**User Story:** As a Funk developer, I want arithmetic and comparison operators to work as language primitives, so that I don't need to import operator modules or worry about operator precedence conflicts.

#### Acceptance Criteria

1. WHEN I use +, -, *, /, % operators THEN they SHALL work as built-in language constructs
2. WHEN I use ==, !=, <, >, <=, >= operators THEN they SHALL work without imports
3. WHEN I use && and || operators THEN they SHALL work as built-in boolean operators
4. WHEN I use operators THEN they SHALL have proper precedence and associativity built into the parser
5. WHEN I use operators on different types THEN the type system SHALL resolve the correct implementation through abilities

### Requirement 3: Built-in Core Abilities

**User Story:** As a Funk developer, I want fundamental abilities like Eq, Ord, Show, and numeric abilities to be part of the language core, so that they're always available for type constraints.

#### Acceptance Criteria

1. WHEN I write a function with Eq 'a constraint THEN the Eq ability SHALL be available without imports
2. WHEN I write a function with Ord 'a constraint THEN the Ord ability SHALL be available without imports
3. WHEN I write a function with Show 'a constraint THEN the Show ability SHALL be available without imports
4. WHEN I write numeric functions THEN NumAdd, NumSub, NumMul, NumDiv, NumMod abilities SHALL be built-in
5. WHEN I use ability constraints THEN the type system SHALL automatically resolve implementations

### Requirement 4: Stdlib Functions Only

**User Story:** As a Funk developer, I want the stdlib to contain only utility functions that operate on built-in types, so that the module system is simpler and more predictable.

#### Acceptance Criteria

1. WHEN I import array module THEN it SHALL contain only functions like map, filter, fold that operate on List 'a
2. WHEN I import string module THEN it SHALL contain only functions like split, join, trim that operate on String
3. WHEN I import option module THEN it SHALL contain only utility functions like map, flatMap, getOrElse
4. WHEN I import modules THEN they SHALL NOT define new types, only functions on existing built-in types
5. WHEN I use qualified calls like array::map THEN they SHALL work on the built-in List type

### Requirement 5: Automatic Prelude

**User Story:** As a Funk developer, I want essential types and functions to be automatically available, so that I can write idiomatic code without boilerplate imports.

#### Acceptance Criteria

1. WHEN I start writing Funk code THEN core types SHALL be automatically in scope
2. WHEN I start writing Funk code THEN basic operators SHALL work immediately
3. WHEN I start writing Funk code THEN core abilities SHALL be available for constraints
4. WHEN I write simple programs THEN I SHALL NOT need any import statements for basic functionality
5. WHEN I use advanced stdlib functions THEN I SHALL only need to import specific modules

### Requirement 6: Backward Compatibility

**User Story:** As a Funk developer with existing code, I want the redesign to maintain compatibility with existing syntax, so that my code continues to work with minimal changes.

#### Acceptance Criteria

1. WHEN I have existing Option usage THEN the syntax SHALL remain the same (Some { val = x }, None)
2. WHEN I have existing list operations THEN they SHALL continue to work with the same syntax
3. WHEN I have existing operator usage THEN it SHALL continue to work without changes
4. WHEN I have existing ability constraints THEN they SHALL continue to work
5. WHEN I migrate to the new system THEN only import statements SHALL need to be removed

### Requirement 7: Performance and Efficiency

**User Story:** As a Funk developer, I want built-in types to be efficiently implemented, so that my programs run fast without runtime overhead from module loading.

#### Acceptance Criteria

1. WHEN I use built-in types THEN they SHALL have native runtime representations
2. WHEN I use built-in operators THEN they SHALL compile to efficient native operations
3. WHEN I start a program THEN there SHALL be no module loading overhead for core functionality
4. WHEN I use type checking THEN built-in types SHALL be resolved faster than module-based types
5. WHEN I use abilities THEN constraint resolution SHALL be optimized for built-in abilities

### Requirement 8: Clear Module Boundaries

**User Story:** As a Funk developer, I want clear separation between built-in language features and stdlib modules, so that I understand what's always available versus what needs to be imported.

#### Acceptance Criteria

1. WHEN I read documentation THEN it SHALL clearly distinguish built-in types from stdlib functions
2. WHEN I get type errors THEN they SHALL clearly indicate whether something is built-in or requires an import
3. WHEN I use IDE features THEN built-in types SHALL be highlighted differently from stdlib functions
4. WHEN I write code THEN I SHALL have clear guidance on what requires imports
5. WHEN I learn Funk THEN the distinction between built-ins and stdlib SHALL be obvious