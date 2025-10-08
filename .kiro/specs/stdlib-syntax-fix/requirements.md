# Requirements Document

## Introduction

The Funk standard library modules contain systematic syntax errors that prevent them from being parsed correctly. While the qualified call functionality works perfectly (as demonstrated with `int::to_string`), most other stdlib modules fail to parse due to missing `=` operators in function definitions. This prevents qualified calls like `string::length`, `boolean::to_string`, and `float::add` from working, severely limiting the language's usability.

## Requirements

### Requirement 1: Fix Function Definition Syntax

**User Story:** As a developer, I want all stdlib modules to use correct Funk syntax, so that I can use qualified calls to access any stdlib function.

#### Acceptance Criteria

1. WHEN a stdlib module contains a function definition THEN it SHALL use the correct syntax `pub let name = { params } -> ReturnType = body`
2. WHEN a function definition spans multiple lines THEN the `=` SHALL appear before the function body
3. WHEN a function has type annotations THEN the syntax SHALL be `pub let name = { params } -> ReturnType = body`
4. WHEN a function body is a single expression THEN it SHALL follow the `=` on the same or next line

### Requirement 2: Validate All Type Modules

**User Story:** As a developer, I want all basic type modules (int, string, boolean, float) to be syntactically correct, so that I can perform operations on all primitive types.

#### Acceptance Criteria

1. WHEN the string module is parsed THEN it SHALL parse without syntax errors
2. WHEN the boolean module is parsed THEN it SHALL parse without syntax errors  
3. WHEN the float module is parsed THEN it SHALL parse without syntax errors
4. WHEN the int module is parsed THEN it SHALL continue to parse without syntax errors
5. WHEN any type module is loaded via qualified calls THEN it SHALL be accessible

### Requirement 3: Preserve Existing Functionality

**User Story:** As a developer, I want existing working qualified calls to continue working, so that I don't lose functionality during the syntax fix.

#### Acceptance Criteria

1. WHEN `int::to_string` is called THEN it SHALL continue to work as before
2. WHEN `int::add` is called THEN it SHALL continue to work as before
3. WHEN any currently working qualified call is used THEN it SHALL maintain the same behavior
4. WHEN the syntax is fixed THEN no existing functionality SHALL be broken

### Requirement 4: Enable Comprehensive Qualified Call Testing

**User Story:** As a developer, I want to test qualified calls across all stdlib modules, so that I can verify the entire qualified call system works correctly.

#### Acceptance Criteria

1. WHEN `string::length` is called THEN it SHALL resolve and type-check correctly
2. WHEN `boolean::to_string` is called THEN it SHALL resolve and type-check correctly
3. WHEN `float::add` is called THEN it SHALL resolve and type-check correctly
4. WHEN qualified calls are made with arguments THEN they SHALL type-check and execute correctly
5. WHEN multiple qualified calls are used in the same file THEN they SHALL all work correctly

### Requirement 5: Maintain Module Structure

**User Story:** As a developer, I want the stdlib module organization to remain the same, so that import paths and module resolution continue to work.

#### Acceptance Criteria

1. WHEN modules are fixed THEN they SHALL remain in the same file locations
2. WHEN module paths are resolved THEN they SHALL use the same `std::types::*` pattern
3. WHEN function names are accessed THEN they SHALL maintain the same identifiers
4. WHEN the module resolver looks for files THEN it SHALL find them in the expected locations