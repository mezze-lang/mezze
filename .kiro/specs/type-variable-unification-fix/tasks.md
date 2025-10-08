# Implementation Plan

- [x] 1. Analyze type variable disconnection root cause
  - Trace type variable assignments from lambda parameters through binary operations
  - Identify where new type variables are created instead of reusing context variables
  - Document the complete type variable flow for expressions like `{x, y} -> int::to_string {value = x + y}`
  - Create detailed mapping of current vs expected type variable assignments
  - _Requirements: 1.1, 2.1_

- [x] 2. Fix binary operation type variable consistency
  - Modify `inferBasicType` for `BinaryOperation` to use lambda parameter type variables directly
  - Ensure `x + y` uses type variables `'e` and `'h` from lambda parameters, not new `'a` and `'b`
  - Implement proper type variable lookup from context before creating new variables
  - Add constraint generation for operand unification: `'e = 'h = 'i` where `'i` is result
  - _Requirements: 1.1, 2.1, 2.2_

- [x] 3. Enhance qualified call constraint generation
  - Fix `handleQualifiedCall` to trace argument types back to original lambda parameter variables
  - Ensure constraints like `'i = Int` use the actual expression result variable, not new variables
  - Implement type variable mapping from qualified call arguments to lambda parameters
  - Add debug logging to trace constraint generation variable assignments
  - _Requirements: 1.3, 2.2, 4.2_

- [ ] 4. Implement transitive constraint propagation in ConstraintSolver
  - Add `applyTransitiveConstraintPropagation` method to handle indirect type variable relationships
  - Implement fixed-point algorithm to resolve constraint chains like `'a = Int, 'b = 'a => 'b = Int`
  - Handle record unification cases where field types create transitive relationships
  - Add comprehensive debug logging for constraint resolution steps
  - _Requirements: 3.1, 3.2, 4.3_

- [ ] 5. Create type variable consistency validation
  - Implement `TypeVariableValidator` class to detect disconnected type variable chains
  - Add validation that constraint generation uses lambda parameter type variables
  - Create diagnostic tools to identify type variable consistency issues
  - Add validation checks in debug mode to catch type variable disconnection early
  - _Requirements: 4.1, 4.2_

- [ ] 6. Add comprehensive debug tools for type variable tracing
  - Implement `ConstraintDebugger` class with type variable assignment tracing
  - Add debug output for type variable mappings between lambda context and expression context
  - Create visualization tools for constraint graphs and type variable relationships
  - Add detailed logging for constraint propagation steps and substitution application
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ] 7. Enhance constraint solver substitution application
  - Fix `Substitution.apply` to handle transitive type variable relationships properly
  - Ensure substitution composition propagates through all connected type variables
  - Implement iterative substitution application until fixed point is reached
  - Add validation that final substitutions are applied to lambda parameter types
  - _Requirements: 3.3, 3.4_

- [ ] 8. Create comprehensive test suite for type variable unification
  - Write unit tests for binary operation type variable consistency
  - Create integration tests for complete constraint propagation flow
  - Add test cases for complex nested expressions and multiple qualified calls
  - Implement regression tests for all identified type variable disconnection scenarios
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 3.1, 3.2_

- [x] 9. Validate fix with target expressions
  - Test `{x, y} -> int::to_string {value = x + y}` produces `{ x: Int, y: Int } -> String`
  - Test `{r} -> int::to_string {value = r.x + r.y}` produces `{ r: { x: Int, y: Int } } -> String`
  - Test multiple operations: `{a, b, c} -> int::to_string {value = a + b + c}`
  - Test mixed types: `{x, y} -> float::to_string {value = x + y}`
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 10. Performance optimization and cleanup
  - Profile type variable unification performance impact
  - Optimize constraint generation to avoid unnecessary type variable creation
  - Clean up debug logging and add performance monitoring
  - Add early termination for constraint propagation when possible
  - _Requirements: 3.4_