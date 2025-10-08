# Implementation Plan

## Phase 1: Qualified Call Resolution (Priority: High)

- [x] 1. Implement qualified call detection in function call handler
  - Add `isQualifiedCall()` method to detect `module::function` patterns
  - Parse qualified call expressions to extract module and function names
  - _Requirements: 1.1, 1.2, 1.3_

- [x] 1.1 Create module type registry for concrete type mappings
  - Implement `ModuleTypeRegistry` with predefined concrete function types
  - Add mappings for `int::add`, `int::subtract`, `int::multiply`, `int::divide`
  - Add mappings for `float::add`, `float::subtract`, `float::multiply`, `float::divide`
  - Add mappings for `string::concat`, `string::length`, etc.
  - _Requirements: 1.1, 1.2, 1.4_

- [x] 1.2 Implement qualified call resolver
  - Create `handleQualifiedCall()` method in TypeChecker
  - Route qualified calls to concrete type resolution
  - Skip ability constraint generation for concrete module calls
  - Handle unknown qualified calls gracefully
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 1.3 Add qualified call parsing utilities
  - Implement `parseQualifiedCall()` to extract module and function names
  - Handle qualified call validation and error cases
  - Support nested qualified calls like `int::add {left = int::multiply {...}, right = c}`
  - _Requirements: 1.5_

- [x] 1.4 Test qualified call resolution
  - Run `IntArithmeticConstraintTest` to verify fixes
  - Ensure all qualified call tests pass with concrete type inference
  - Verify no ability constraints are generated for qualified calls
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

## Phase 2: Operator Refinement (Priority: High)

- [ ] 2. Enhance operator handling for concrete vs polymorphic cases
  - Modify existing operator handling in `inferTypeWithConstraints`
  - Distinguish between concrete operand cases (Int + Int) and polymorphic cases ('a + 'a)
  - Preserve concrete type resolution while maintaining polymorphic ability constraints
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 2.1 Implement concrete arithmetic detection
  - Create `isConcreteArithmetic()` method to detect concrete operand types
  - Handle cases where both operands are concrete types (Int, Float, etc.)
  - Return concrete result types without generating ability constraints
  - _Requirements: 2.3_

- [ ] 2.2 Refine polymorphic arithmetic handling
  - Ensure polymorphic cases continue to generate ability constraints
  - Preserve existing `{ x, y } -> x + y` → `{ x: 'a, y: 'a } with NumAdd 'a -> 'a` inference
  - Handle mixed concrete/polymorphic scenarios appropriately
  - _Requirements: 2.1, 2.2, 2.4_

- [ ] 2.3 Test operator refinement
  - Verify `AddFunctionDebugTest.testAddFunctionTypeInference` still passes
  - Run `ArithmeticConstraintTest` to ensure concrete/polymorphic distinction works
  - Test mixed scenarios with both concrete and polymorphic operations
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

## Phase 3: Effect System Integration (Priority: Medium)

- [ ] 3. Integrate constraint-based inference with effect handling
  - Ensure effect types are preserved during constraint generation
  - Handle `perform` expressions in constraint context
  - Maintain effect row information during constraint solving
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 3.1 Enhance effect expression constraint handling
  - Modify constraint generation for effect-aware expressions
  - Preserve effect row types during constraint collection
  - Handle effect propagation in constraint-based inference
  - _Requirements: 3.1, 3.2_

- [ ] 3.2 Fix perform expression constraint generation
  - Ensure `perform` expressions work with constraint-based inference
  - Handle effect handler resolution in constraint context
  - Maintain proper effect type checking with constraints
  - _Requirements: 3.2, 3.3_

- [ ] 3.3 Test effect system integration
  - Run effect-related tests to verify constraint compatibility
  - Fix `ComprehensiveEffectRowTest` failures
  - Ensure effect handler validation works with constraints
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

## Phase 4: Do Expression Enhancement (Priority: Medium)

- [ ] 4. Improve do expression constraint handling
  - Enhance constraint propagation through do expression statements
  - Handle let bindings within do blocks properly
  - Maintain proper variable scoping for constraint variables
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

- [ ] 4.1 Implement do expression constraint propagation
  - Modify `handleDoExpressionConstraints()` for proper constraint flow
  - Handle let statements with constraint collection
  - Ensure result expression gets accumulated constraints
  - _Requirements: 4.1, 4.2_

- [ ] 4.2 Fix nested do expression handling
  - Handle nested do expressions with proper constraint scoping
  - Ensure constraint variables don't leak between scopes
  - Maintain proper type inference in nested contexts
  - _Requirements: 4.3_

- [ ] 4.3 Test do expression constraint handling
  - Fix `DoExpressionBasicTest` failures
  - Run `DoExpressionConstraintTest` to verify nested handling
  - Ensure do expressions work correctly in lambda contexts
  - _Requirements: 4.1, 4.2, 4.3, 4.4_

## Phase 5: Error Reporting and Fixtures (Priority: Low)

- [ ] 5. Update error reporting for constraint-based system
  - Enhance error messages for constraint violations
  - Provide clear messages for ability constraint failures
  - Update qualified call error reporting
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 5.1 Implement enhanced constraint error messages
  - Create `ConstraintErrorMessages` utility class
  - Add specific error messages for different constraint types
  - Ensure error messages are clear and actionable
  - _Requirements: 5.1, 5.2, 5.3_

- [ ] 5.2 Fix fixture-based test compatibility
  - Update fixture test expectations for constraint-based inference
  - Ensure `FixtureBasedTypeCheckerTest` passes with new system
  - Handle polymorphic vs concrete type expectations in fixtures
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 5.3 Comprehensive test validation
  - Run full test suite to ensure < 10 failing tests
  - Verify > 95% test pass rate achievement
  - Ensure no regressions in core constraint-based functionality
  - _Requirements: 7.1, 7.2, 7.3, 7.4_

## Validation and Testing

- [ ] 6. Continuous validation throughout implementation
  - After each phase, run relevant test suites to verify fixes
  - Ensure `AddFunctionDebugTest.testAddFunctionTypeInference` continues to pass
  - Monitor overall test pass rate and failing test count
  - Document any remaining edge cases or limitations

- [ ] 6.1 Phase 1 Validation: Qualified Call Tests
  - `IntArithmeticConstraintTest` - all tests should pass
  - Qualified call resolution should work for int, float, string modules
  - No ability constraints should be generated for qualified calls

- [ ] 6.2 Phase 2 Validation: Operator Tests  
  - `ArithmeticConstraintTest` - concrete vs polymorphic distinction
  - `AddFunctionDebugTest` - polymorphic inference preservation
  - Mixed concrete/polymorphic scenarios should work correctly

- [ ] 6.3 Phase 3 Validation: Effect Tests
  - `ComprehensiveEffectRowTest` - effect system integration
  - `EffectHandlerUsageValidationTest` - effect handler compatibility
  - Effect types should be preserved with constraint-based inference

- [ ] 6.4 Phase 4 Validation: Do Expression Tests
  - `DoExpressionBasicTest` - basic do expression functionality
  - `DoExpressionConstraintTest` - nested do expression handling
  - Do expressions should work correctly in constraint context

- [ ] 6.5 Phase 5 Validation: Overall System Health
  - `ErrorReportingTest` - error message compatibility
  - `FixtureBasedTypeCheckerTest` - fixture compatibility
  - Overall test suite should have < 10 failing tests and > 95% pass rate

## Phase 6: CLI and Test Path Consistency (Priority: High)

- [ ] 7. Fix CLI constraint display inconsistency
  - Investigate why CLI shows `{ x: 'a, y: 'a } -> 'a` instead of `{ x: 'a, y: 'a } with NumAdd 'a -> 'a`
  - Ensure bidirectional type checker properly generates ability constraints
  - Fix constraint generation in `inferLambdaBidirectionally` method
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [ ] 7.1 Debug constraint generation in bidirectional inference
  - Add comprehensive constraint analysis in `analyzeForAbilityConstraints`
  - Ensure arithmetic operations in lambda bodies generate proper constraints
  - Fix constraint deduplication and type variable mapping
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [ ] 7.2 Fix AddFunctionDebugTest regression
  - Ensure test passes with correct constraint display
  - Debug why test is failing after previous fixes
  - Verify constraint generation works consistently across test and CLI paths
  - _Requirements: 8.5_

- [ ] 7.3 Test CLI and unit test consistency
  - Verify same expressions produce same types in CLI and tests
  - Ensure constraint generation is path-independent
  - Add integration tests for CLI constraint display
  - _Requirements: 8.1, 8.2, 8.3_

## Phase 7: Array Type Inference Fixes (Priority: Medium)

- [ ] 8. Fix ArrayListTypeCheckerTest failures
  - Debug array type inference issues with constraint system
  - Ensure array element type unification works correctly
  - Fix empty array type inference with generic constraints
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [ ] 8.1 Implement array constraint handling
  - Add constraint generation for array literal expressions
  - Handle array element type unification in constraint context
  - Ensure nested array types work with constraint system
  - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [ ] 8.2 Fix array type pretty printing
  - Ensure array types display correctly with constraint system
  - Handle generic array type display in CLI and tests
  - Fix array type annotation parsing and display
  - _Requirements: 10.5_

- [ ] 8.3 Test array type inference
  - Run `ArrayListTypeCheckerTest` to verify all tests pass
  - Test array type inference in various contexts
  - Ensure array types work with polymorphic and concrete inference
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

## Consolidated Tasks from Previous Constraint Work

The following tasks were completed in previous constraint propagation work and should be verified to still work:

- [x] Enhanced TypeChecker with qualified call detection
- [x] Implemented basic concrete type forcing for qualified calls
- [x] Integrated constraint propagation with existing constraint solver
- [x] Implemented constraint propagation for pipe operator expressions
- [x] Added support for direct qualified call expressions
- [x] Implemented constraint propagation for do expressions
- [x] Extended qualified call support to string module functions
- [x] Added support for int module arithmetic functions
- [x] Implemented comprehensive error handling for qualified calls
- [x] Created comprehensive test suite for constraint propagation
- [x] Updated CLI to use enhanced TypeChecker
- [x] Fixed type variable unification in constraint propagation
- [x] Enhanced constraint solver for transitive relationships
- [x] Unified binary operation constraint generation

## Remaining Tasks from Previous Work

- [ ] 9. Implement type variable consistency validation
  - Add validation to ensure constraint generation uses consistent type variables
  - Implement type variable mapping verification between lambda context and expression context
  - Add debug tools to trace type variable assignments and relationships
  - Create comprehensive test cases for type variable consistency across constraint propagation
  - _Requirements: 8.2, 8.3_

- [ ] 10. Optimize constraint propagation performance
  - Profile constraint propagation overhead in type checking
  - Optimize qualified call detection to avoid unnecessary work
  - Cache qualified call type requirements for better performance
  - Add early termination for constraint propagation when possible
  - _Requirements: Performance and reliability requirements_