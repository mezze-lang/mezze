# Implementation Plan

- [ ] 1. Update AST with InterpolatedStringLiteral node
  - Replace StringLiteral with InterpolatedStringLiteral in FunkAST.kt
  - Add StringPart sealed interface with StringTextPart and StringInterpolationPart
  - Update all references to StringLiteral throughout codebase
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 3.1, 3.2, 3.3_

- [ ] 2. Enhance ANTLR grammar for multi-line and interpolation syntax
  - Modify String lexer rule to accept newlines and interpolation syntax
  - Add fragment rules for StringContent, StringChar, EscapeSequence, Interpolation
  - Handle nested parentheses in interpolation expressions
  - Test lexer with various string formats
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3_

- [ ] 3. Implement unified string parsing logic
  - Update parseStringLiteral to always create InterpolatedStringLiteral
  - Add parseInterpolatedString method to handle all string types
  - Implement findMatchingParen for nested parentheses
  - Add processEscapeSequences with \$ support
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.6_

- [ ] 4. Add type checking for InterpolatedStringLiteral
  - Implement checkInterpolatedString in TypeChecker
  - Add hasShowInstance method for Show ability resolution
  - Verify Show instances exist for all interpolated expression types
  - Add proper error reporting with source locations
  - _Requirements: 2.4, 4.1, 4.2, 4.3, 4.4_

- [ ] 5. Update Show ability to use to_string method
  - Modify Show ability definition to use to_string instead of show
  - Update all existing Show implementations in stdlib
  - Add Show String implementation (identity function)
  - Test Show ability resolution with new method name
  - _Requirements: 2.4, 4.1, 4.2_

- [ ] 6. Implement performance-optimized interpreter nodes
  - Create InterpolatedStringNode using StringBuilder
  - Add StringTextNode and StringInterpolationNode
  - Implement callToString method for Show ability resolution
  - Update ASTToTruffleConverter for new AST node
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 7. Add comprehensive error handling
  - Handle unclosed interpolation expressions with proper locations
  - Add type checking errors for missing Show instances
  - Implement runtime error propagation from interpolated expressions
  - Test error reporting with complex nested expressions
  - _Requirements: 2.6, 4.1, 4.2, 4.3, 4.4_

- [ ] 8. Create comprehensive test suite
  - Add lexer tests for multi-line strings and interpolation syntax
  - Create parser tests for InterpolatedStringLiteral generation
  - Implement type checker tests for Show ability resolution
  - Add interpreter tests for StringBuilder performance
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [ ] 9. Performance validation and optimization
  - Benchmark StringBuilder approach vs concatenation
  - Test memory allocation patterns
  - Validate performance with large multi-line strings
  - Compare against manual concatenation performance
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [ ] 10. Integration testing and backward compatibility
  - Test all existing string usage continues to work
  - Verify mixed old/new syntax compatibility
  - Add end-to-end tests with complex interpolation
  - Test custom Show instances with interpolation
  - _Requirements: 3.1, 3.2, 3.3, 3.4_