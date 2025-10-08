# Design Document

## Overview

This design addresses the systematic syntax errors in the Funk standard library modules that prevent qualified calls from working correctly. The core issue is that most stdlib modules use incorrect function definition syntax, missing the required `=` operator before function bodies. The solution involves identifying all affected modules, correcting their syntax, and validating the fixes through comprehensive testing.

## Architecture

### Problem Analysis

The qualified call system works correctly - the issue is purely syntactic in the stdlib modules:

1. **Working Example (int.funk):**
   ```funk
   pub let to_string = { value: Int } -> String =
       __native_int_to_string { value = value }
   ```

2. **Broken Example (string.funk):**
   ```funk
   pub let length = { str: String } -> Int
       __native_string_length { str = str }  # Missing '='
   ```

### Module Categories

Based on investigation, stdlib modules fall into these categories:

1. **Correct Syntax:** `int.funk` - serves as the template
2. **Missing Equals:** `string.funk`, `boolean.funk`, `float.funk` - need `=` added
3. **Unknown Status:** Other modules in collections, core, effects, math - need assessment

## Components and Interfaces

### Syntax Correction Engine

**Purpose:** Systematically identify and fix syntax errors in stdlib modules

**Interface:**
- Input: Stdlib module file path
- Output: Corrected module content
- Validation: Parse test to ensure syntax correctness

**Implementation Strategy:**
1. **Pattern Detection:** Identify lines with `pub let name = { params } -> ReturnType` followed by function body without `=`
2. **Correction Application:** Insert `=` between type annotation and function body
3. **Validation:** Parse corrected content to ensure syntax correctness

### Module Assessment Tool

**Purpose:** Scan all stdlib modules to identify syntax issues

**Interface:**
- Input: Stdlib directory structure
- Output: Report of modules needing fixes
- Categories: Correct, needs fixing, parse errors

### Testing Framework

**Purpose:** Validate that syntax fixes enable qualified calls

**Interface:**
- Input: Module name and function name
- Output: Qualified call test result
- Validation: Type checking and resolution success

## Data Models

### Syntax Error Pattern

```kotlin
data class SyntaxError(
    val file: String,
    val lineNumber: Int,
    val pattern: String,
    val correction: String
)
```

### Module Status

```kotlin
enum class ModuleStatus {
    CORRECT,           // Already has correct syntax
    NEEDS_EQUALS,      // Missing = in function definitions
    PARSE_ERROR,       // Has other syntax issues
    UNKNOWN            // Not yet assessed
}
```

### Fix Report

```kotlin
data class FixReport(
    val module: String,
    val originalStatus: ModuleStatus,
    val fixesApplied: List<SyntaxError>,
    val finalStatus: ModuleStatus,
    val testResults: List<QualifiedCallTest>
)
```

## Error Handling

### Syntax Error Recovery

1. **Backup Strategy:** Create `.backup` files before making changes
2. **Rollback Capability:** Restore from backup if fixes fail validation
3. **Incremental Fixing:** Fix one module at a time to isolate issues

### Validation Failures

1. **Parse Errors:** If corrected syntax still doesn't parse, flag for manual review
2. **Type Errors:** If qualified calls fail after syntax fix, investigate type issues
3. **Missing Functions:** If expected functions aren't found, check function names

### Module Resolution Issues

1. **Path Problems:** Ensure module resolver can find corrected files
2. **Import Conflicts:** Check for naming conflicts after fixes
3. **Dependency Issues:** Verify inter-module dependencies still work

## Testing Strategy

### Unit Testing

1. **Syntax Correction Tests:**
   - Test pattern detection accuracy
   - Verify correction application
   - Validate edge cases (multi-line functions, complex types)

2. **Module Parsing Tests:**
   - Test each corrected module parses successfully
   - Verify function definitions are recognized
   - Check type annotations are preserved

### Integration Testing

1. **Qualified Call Tests:**
   - Test `string::length` resolves correctly
   - Test `boolean::to_string` works with arguments
   - Test `float::add` performs arithmetic
   - Test multiple qualified calls in same file

2. **Module Resolution Tests:**
   - Verify module paths resolve correctly
   - Test function lookup within modules
   - Validate type checking of qualified calls

### Regression Testing

1. **Existing Functionality:**
   - Ensure `int::to_string` continues working
   - Verify all currently working qualified calls remain functional
   - Test non-qualified function calls still work

2. **Parser Stability:**
   - Ensure comment parsing works correctly
   - Verify other language features unaffected
   - Test complex expressions with qualified calls

## Implementation Phases

### Phase 1: Assessment and Planning
- Scan all stdlib modules to categorize their status
- Create comprehensive inventory of syntax issues
- Establish baseline tests for currently working functionality

### Phase 2: Core Type Modules
- Fix string.funk, boolean.funk, float.funk syntax
- Validate each fix with parsing and qualified call tests
- Ensure int.funk remains unaffected

### Phase 3: Extended Modules
- Assess and fix collections, core, effects, math modules
- Handle more complex syntax patterns if discovered
- Validate inter-module dependencies

### Phase 4: Comprehensive Testing
- Test all qualified call combinations
- Validate complete stdlib functionality
- Performance testing of module resolution

### Phase 5: Documentation and Cleanup
- Document syntax patterns and fixes applied
- Remove backup files after validation
- Update any relevant documentation