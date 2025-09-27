# Record Spread Syntax Semantics Requirements

## Introduction

This spec defines the exact semantics for record spread syntax in Funk, clarifying what operations should be allowed and what should be restricted.

## Requirements

### Requirement 1: Basic Spread Functionality

**User Story:** As a developer, I want to create a new record based on an existing record with some fields modified, so that I can work with immutable data structures efficiently.

#### Acceptance Criteria

1. WHEN I use `{...existingRecord, field = newValue}` THEN the system SHALL create a new record with all fields from existingRecord and the specified field updated to newValue
2. WHEN the specified field already exists in the existing record THEN the system SHALL override it with the new value
3. WHEN I spread a record THEN the original record SHALL remain unchanged (immutability)

### Requirement 2: Field Addition Restrictions

**User Story:** As a language designer, I want to enforce strict type safety and prevent any field additions, so that record types remain predictable and the spread syntax is simple to understand.

#### Acceptance Criteria

1. WHEN I use spread syntax with a field that doesn't exist in the original record THEN the system SHALL reject this with a type error
2. WHEN I use spread syntax THEN the system SHALL only allow overriding existing fields from the source record
3. WHEN I attempt to add new fields using spread syntax THEN the system SHALL reject this regardless of whether the record type is open or closed

### Requirement 3: Single Spread Restriction

**User Story:** As a developer, I want spread syntax to be simple and predictable with only one spread allowed, so that I can reason about the resulting record structure easily and avoid complex merge semantics.

#### Acceptance Criteria

1. WHEN I use multiple spread expressions in a single record literal THEN the system SHALL reject this with a syntax error
2. WHEN I use spread syntax THEN exactly one spread expression SHALL be allowed per record literal
3. WHEN I use spread syntax THEN it SHALL appear before any field overrides
4. WHEN I attempt to use `{...record1, ...record2}` THEN the system SHALL reject this as invalid syntax

### Requirement 4: Type Safety

**User Story:** As a developer, I want spread syntax to be type-safe, so that I can catch errors at compile time rather than runtime.

#### Acceptance Criteria

1. WHEN I spread a non-record value THEN the system SHALL reject this with a type error
2. WHEN I spread a record THEN the resulting record type SHALL be statically determinable
3. WHEN I override a field with an incompatible type THEN the system SHALL reject this with a type error

## Examples

### Valid Usage
```funk
let person = {name = "Alice", age = 30}
let updated = {...person, age = 31}  // Override existing field
```

### Invalid Usage
```funk
let person = {name = "Alice", age = 30}
let other = {city = "NYC", country = "USA"}

let invalid1 = {...person, city = "NYC"}    // Error: adding new field 'city'
let invalid2 = {...person, ...other}        // Error: multiple spreads not allowed
let invalid3 = {city = "NYC", ...person}    // Error: spread must come first
let invalid4 = {...person, name = "Bob", ...other}  // Error: multiple spreads
```