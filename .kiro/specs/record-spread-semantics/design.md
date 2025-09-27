# Record Spread Syntax Design

## Overview

This design document outlines the technical approach to implement restrictive record spread syntax in Funk, ensuring only existing field overrides are allowed with single spread expressions.

## Architecture

The implementation will enforce restrictions at multiple levels:

1. **Grammar Level**: Syntax validation for spread positioning and count
2. **Parser Level**: AST construction with spread validation
3. **Type Checker Level**: Field existence validation and type safety
4. **Interpreter Level**: Runtime execution of validated spread operations

## Components and Interfaces

### 1. Grammar Modifications

**Current State**: The grammar already supports spread syntax but doesn't enforce restrictions.

**Required Changes**: Add validation rules to ensure:
- Only one spread expression per record literal
- Spread expressions appear before field entries
- Proper error messages for violations

### 2. Parser Enhancements

**Current State**: Parser creates `RecordSpreadEntry` and `RecordFieldEntry` objects.

**Required Changes**:
- Add validation in `visitRecordLiteral()` to check spread count and positioning
- Throw parse errors for multiple spreads or misplaced spreads
- Ensure spread entries come before field entries in the AST

### 3. Type Checker Restrictions

**Current State**: Type checker validates spread expressions are records but allows field additions.

**Required Changes**:
- In `synthesizeRecord()` and related methods, validate that all field entries in spread syntax correspond to existing fields in the spread source
- Add field existence checking for closed records
- Generate specific error messages for field addition attempts
- Maintain existing type compatibility checking

### 4. Interpreter Consistency

**Current State**: Interpreter supports the full spread functionality.

**Required Changes**: 
- Minimal changes needed since type checker will prevent invalid operations
- Ensure error handling aligns with new restrictions
- Update error messages to be consistent with restrictive semantics

## Data Models

### AST Structure (No Changes Required)

The existing AST structure is sufficient:

```kotlin
sealed class RecordLiteralEntry
data class RecordFieldEntry(val name: String, val value: Expression?) : RecordLiteralEntry()
data class RecordSpreadEntry(val expression: Expression) : RecordLiteralEntry()
```

### Validation State

New validation structures needed:

```kotlin
data class SpreadValidationResult(
    val spreadCount: Int,
    val spreadPosition: Int?,
    val firstFieldPosition: Int?,
    val isValid: Boolean,
    val errorMessage: String?
)
```

## Error Handling

### New Error Types

1. **Multiple Spread Error**: "Only one spread expression allowed per record literal"
2. **Spread Positioning Error**: "Spread expression must appear before field assignments"
3. **Field Addition Error**: "Cannot add new field 'fieldName' to record type. Only existing fields can be overridden"
4. **Invalid Spread Target Error**: "Cannot spread non-record value"

### Error Location Tracking

Enhance error reporting with precise source locations for spread-related errors.

## Testing Strategy

### Unit Tests Required

1. **Parser Tests**:
   - Single spread with field overrides (valid)
   - Multiple spreads (invalid)
   - Spread after fields (invalid)
   - Empty spread expressions

2. **Type Checker Tests**:
   - Field override with compatible types (valid)
   - Field addition attempts (invalid)
   - Spread of non-record types (invalid)
   - Type compatibility validation

3. **Integration Tests**:
   - End-to-end spread operations
   - Error message accuracy
   - Complex nested record scenarios

### Test Cases

```funk
// Valid cases
let person = {name = "Alice", age = 30}
let updated = {...person, age = 31}

// Invalid cases - should be rejected
let invalid1 = {...person, city = "NYC"}        // New field
let invalid2 = {...person, ...other}            // Multiple spreads  
let invalid3 = {age = 25, ...person}           // Spread not first
```

## Implementation Plan

### Phase 1: Parser Validation
- Add spread count and position validation
- Implement proper error messages
- Update grammar if needed for better error reporting

### Phase 2: Type Checker Restrictions  
- Add field existence validation
- Implement restrictive field checking
- Update error messages for clarity

### Phase 3: Testing and Refinement
- Comprehensive test suite
- Error message improvements
- Documentation updates

## Migration Strategy

### Backward Compatibility

**Breaking Changes**: The current implementation allows field additions and multiple spreads, which will become compile-time errors.

**Migration Path**:
1. Identify existing code using field additions
2. Refactor to use explicit record construction instead of spread for new fields
3. Replace multiple spreads with sequential record updates

### Example Migration

```funk
// Before (currently works, will break)
let extended = {...base, newField = "value"}

// After (recommended approach)
let extended = {
  name = base.name,
  age = base.age, 
  newField = "value"
}
```

## Performance Considerations

### Compile-Time Impact
- Additional validation steps in parser and type checker
- Minimal performance impact due to early validation

### Runtime Impact
- No runtime performance changes
- Validation happens at compile-time only

## Security Considerations

- No security implications
- Improved type safety reduces potential runtime errors
- Clearer semantics reduce developer confusion