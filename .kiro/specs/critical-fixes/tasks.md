# Implementation Plan

- [x] 1. Fix typechecker undefined variable handling
  - Modify BidirectionalTypeChecker to detect undefined variables instead of creating fresh type variables
  - Add specific error types for undefined variables and qualified names
  - Update variable resolution logic to check scopes properly
  - _Requirements: 1.1, 1.2, 1.3_

- [x] 2. Implement proper qualified name resolution
  - Add logic to resolve qualified names like `int::to_string` 
  - Implement module-aware variable lookup
  - Add error reporting for undefined modules and qualified names
  - _Requirements: 1.2, 2.2_

- [x] 3. Fix stdlib module loading in CLI
  - Update CLI Main.kt to ensure stdlib is loaded before type checking
  - Add error handling for stdlib loading failures
  - Implement fallback mechanism for when stdlib files can't be parsed
  - _Requirements: 2.1, 2.3, 2.4_

- [x] 4. Enhance ModuleResolver for stdlib integration
  - Update ModuleResolver to handle stdlib modules automatically
  - Add support for inlined stdlib functions as fallback
  - Implement proper error reporting for module loading issues
  - _Requirements: 2.1, 2.3, 5.1, 5.2_

- [x] 5. Fix parser grammar for stdlib syntax
  - Review and fix ANTLR grammar rules causing parse errors in stdlib files
  - Fix named parameter parsing in function signatures
  - Improve error recovery for malformed syntax
  - _Requirements: 3.1, 3.2, 3.4_

- [x] 6. Correct operator implementations in stdlib
  - Fix `not` function to use proper named argument syntax: `{ value: Boolean }`
  - Fix `!=` operator implementation with correct type constraints
  - Ensure all operators use consistent named argument patterns
  - _Requirements: 4.1, 4.2, 4.4_

- [x] 7. Implement stdlib inlining in typechecker
  - Add inlined operator implementations to NativeFunctionTypes
  - Ensure inlined signatures match stdlib file definitions exactly
  - Add mechanism to prefer inlined implementations over parsed ones
  - _Requirements: 5.1, 5.3, 5.4_

- [x] 8. Implement stdlib inlining in interpreter
  - Add inlined operator implementations to NativeFunctions
  - Ensure interpreter can execute operators without parsing stdlib
  - Maintain compatibility with existing operator usage
  - _Requirements: 5.2, 5.3, 5.4_

- [x] 9. Add comprehensive error reporting tests
  - Write tests for undefined variable error reporting
  - Add tests for qualified name resolution errors
  - Test stdlib loading error scenarios
  - _Requirements: 1.1, 1.2, 2.3_

- [x] 10. Add parser regression tests
  - Create tests for stdlib syntax patterns that were failing
  - Add tests for operator definition parsing
  - Ensure previously working syntax continues to work
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 11. Validate stdlib integration end-to-end
  - Test that `int::to_string` works correctly in user code
  - Verify all stdlib operators work with proper type checking
  - Test fallback behavior when stdlib files have issues
  - _Requirements: 2.2, 4.3, 5.3_

- [x] 12. Update CLI to handle stdlib errors gracefully
  - Improve error messages when stdlib loading fails
  - Add command-line options for debugging stdlib issues
  - Ensure CLI exits with appropriate error codes
  - _Requirements: 2.3, 2.4, 3.4_