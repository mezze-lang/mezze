# Implementation Plan

Convert the feature design into a series of prompts for a code-generation LLM that will implement each step in a test-driven manner. Prioritize best practices, incremental progress, and early testing, ensuring no big jumps in complexity at any stage. Make sure that each prompt builds on the previous prompts, and ends with wiring things together. There should be no hanging or orphaned code that isn't integrated into a previous step. Focus ONLY on tasks that involve writing, modifying, or testing code.

- [x] 1. Add parser-level validation for spread syntax restrictions
  - Modify `visitRecordLiteral()` in `FunkParserImpl.kt` to validate spread count and positioning
  - Add validation that only one spread expression is allowed per record literal
  - Add validation that spread expressions must appear before field entries
  - Implement specific error messages for multiple spreads and misplaced spreads
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 2. Create comprehensive parser tests for spread restrictions
  - Write unit tests for single spread with field overrides (should pass)
  - Write unit tests for multiple spreads (should fail with specific error)
  - Write unit tests for spread after field entries (should fail with specific error)
  - Write unit tests for empty record literals and edge cases
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 3. Add type checker validation for field existence restrictions
  - Modify `synthesizeRecord()` in `TypeChecker.kt` to validate field existence
  - Add validation that spread syntax can only override existing fields from source record
  - Implement field existence checking for both open and closed record types
  - Generate specific error messages for field addition attempts
  - _Requirements: 2.1, 2.2, 2.3_

- [x] 4. Create comprehensive type checker tests for field restrictions
  - Write unit tests for field override with compatible types (should pass)
  - Write unit tests for field addition attempts (should fail with specific error)
  - Write unit tests for spread of non-record types (should fail)
  - Write unit tests for type compatibility validation in spread operations
  - _Requirements: 2.1, 2.2, 2.3, 4.1, 4.2, 4.3_

- [x] 5. Update error handling and messages across all components
  - Create specific error types for spread syntax violations
  - Update error messages to be clear and actionable
  - Ensure consistent error reporting across parser and type checker
  - Add source location tracking for precise error reporting
  - _Requirements: 4.1, 4.2, 4.3_

- [ ] 6. Create integration tests for end-to-end spread functionality
  - Write integration tests that combine parsing and type checking
  - Test complex scenarios with nested records and spread operations
  - Verify error messages are accurate and helpful
  - Test edge cases and boundary conditions
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4_

- [ ] 7. Update existing tests to match new restrictive semantics
  - Identify existing tests that use field additions or multiple spreads
  - Update tests to use valid restrictive spread syntax
  - Remove or modify tests that relied on permissive behavior
  - Ensure all existing functionality still works within new restrictions
  - _Requirements: 1.1, 1.2, 1.3_

- [ ] 8. Update documentation and examples
  - Update code examples to demonstrate correct restrictive usage
  - Add documentation explaining the spread syntax restrictions
  - Provide migration examples for converting permissive to restrictive syntax
  - Update error message documentation
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4_