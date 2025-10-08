# Qualified Call Support Implementation Tasks

## 1. Core Infrastructure Setup

- [ ] 1.1 Add qualified call detection function to TypeChecker
  - Implement `isQualifiedCall(identifier: String): Boolean`
  - Add unit tests for `::` syntax detection
  - Handle edge cases (empty parts, multiple `::`, etc.)
  - _Requirements: 1.1, 1.3_

- [ ] 1.2 Create module name mapping system
  - Define mapping from short names to full module paths
  - Support `int` -> `std::types::int` mapping
  - Support `float`, `string`, `boolean` mappings
  - Make mapping extensible for future modules
  - _Requirements: 2.1, 2.2_

- [ ] 1.3 Implement basic qualified call handler
  - Create `handleQualifiedCall(qualifiedName: String, arguments: Expression): FunkType`
  - Parse qualified name into module and function parts
  - Add error handling for malformed syntax
  - _Requirements: 1.1, 1.2, 5.4_

## 2. Module Resolution Integration

- [ ] 2.1 Integrate with existing ModuleResolver
  - Use ModuleResolver to resolve full module paths
  - Handle module resolution failures gracefully
  - Cache resolved modules for performance
  - _Requirements: 2.1, 2.3_

- [ ] 2.2 Implement function lookup in modules
  - Look up function in module exports
  - Validate that target is actually a function
  - Handle function not found errors
  - _Requirements: 2.4, 5.2_

- [ ] 2.3 Add comprehensive error handling
  - Module not found errors with clear messages
  - Function not found errors with module context
  - Invalid qualified call format errors
  - Module resolver unavailable errors
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

## 3. Type System Integration

- [ ] 3.1 Implement function type application
  - Apply resolved function type to arguments
  - Add constraint between argument and parameter types
  - Return function's return type
  - _Requirements: 3.1, 3.4_

- [ ] 3.2 Create constraint propagation system
  - Implement `applyQualifiedCallConstraints` function
  - Add specific constraints for `int::to_string`
  - Add specific constraints for `float::to_string`
  - Add specific constraints for `string::length`
  - _Requirements: 3.2, 3.3_

- [ ] 3.3 Integrate with existing constraint solver
  - Ensure constraints are added to global constraint list
  - Verify constraint solver handles new constraints correctly
  - Test constraint propagation through `do` expressions
  - _Requirements: 3.2, 3.3_

## 4. Standard Library Support

- [ ] 4.1 Add support for int module functions
  - Support `int::to_string` with proper constraints
  - Test with various Int expressions
  - Verify constraint propagation works
  - _Requirements: 4.1_

- [ ] 4.2 Add support for float module functions
  - Support `float::to_string` with proper constraints
  - Test with various Float expressions
  - Verify constraint propagation works
  - _Requirements: 4.2_

- [ ] 4.3 Add support for string module functions
  - Support `string::length` with proper constraints
  - Test with various String expressions
  - Verify constraint propagation works
  - _Requirements: 4.3_

- [ ] 4.4 Add support for boolean module functions
  - Support `boolean::to_string` with proper constraints
  - Test with various Boolean expressions
  - Verify constraint propagation works
  - _Requirements: 4.4_

## 5. Testing and Validation

- [ ] 5.1 Create unit tests for qualified call detection
  - Test valid qualified call formats
  - Test invalid qualified call formats
  - Test edge cases and malformed input
  - _Requirements: 1.1, 1.3_

- [ ] 5.2 Create unit tests for module resolution
  - Test successful module resolution
  - Test module not found scenarios
  - Test function not found scenarios
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 5.3 Create integration tests for constraint propagation
  - Test simple qualified calls work correctly
  - Test constraint propagation through `do` expressions
  - Test constraint propagation through `let` bindings
  - Test complex nested expressions
  - _Requirements: 3.1, 3.2, 3.3_

- [ ] 5.4 Create comprehensive stdlib integration tests
  - Test all supported stdlib qualified calls
  - Test mixed qualified calls in same expression
  - Test qualified calls with arithmetic operations
  - Test qualified calls with record operations
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

## 6. Error Handling and Diagnostics

- [ ] 6.1 Implement comprehensive error messages
  - Clear messages for module not found
  - Clear messages for function not found
  - Clear messages for type mismatches
  - Clear messages for malformed syntax
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 6.2 Add error recovery mechanisms
  - Graceful handling of resolution failures
  - Fallback to fresh type variables when needed
  - Continue type checking after qualified call errors
  - _Requirements: 5.1, 5.2, 5.3_

- [ ] 6.3 Create error handling tests
  - Test all error scenarios produce correct messages
  - Test error recovery doesn't break subsequent checking
  - Test error messages are user-friendly
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

## 7. Documentation and Examples

- [ ] 7.1 Create qualified call usage examples
  - Basic qualified call examples
  - Constraint propagation examples
  - Complex expression examples
  - Error case examples

- [ ] 7.2 Update type checker documentation
  - Document new qualified call functionality
  - Document integration with module system
  - Document constraint propagation behavior

- [ ] 7.3 Create developer guide for extending qualified calls
  - How to add new module mappings
  - How to add new constraint patterns
  - How to test new qualified call features