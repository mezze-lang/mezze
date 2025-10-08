# Implementation Plan

- [x] 1. Implement core ability constraint data structures
  - Create AbilityConstraint class in ConstraintSolver.kt extending the existing Constraint sealed class
  - Create Ability data class to represent ability definitions with operations and super-abilities
  - Create AbilityRegistry class for managing built-in and user-defined abilities
  - Add ConstraintContext class for tracking ability constraints during inference
  - Write unit tests for ability constraint data structures
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 2. Register built-in abilities for primitive types
  - Implement BuiltinAbilities object with NumAdd, NumSub, NumMul, NumDiv, Eq, Ord abilities
  - Add ability registration for Int, Frac, Bool, String types in AbilityRegistry
  - Implement hasAbility method to check if a concrete type supports an ability
  - Implement getRequiredAbilities method to map operators to required abilities
  - Create comprehensive test suite for built-in ability lookups
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 3. Enhance constraint solver with ability validation
  - Extend ConstraintSolver.solve method to handle AbilityConstraint instances
  - Implement validateAbilityConstraints method to check ability requirements against resolved types
  - Add ability constraint error reporting with clear messages about missing abilities
  - Integrate ability validation with existing unification algorithm
  - Write tests for constraint solving with ability constraints
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [x] 4. Generate ability constraints for arithmetic operations
  - Modify synthesizeBinaryOperatorType in TypeChecker.kt to generate ability constraints
  - Add constraint generation for +, -, *, / operators requiring NumAdd, NumSub, NumMul, NumDiv
  - Implement constraint generation for type variables in arithmetic expressions
  - Add validation for concrete types in arithmetic operations
  - Create test cases for arithmetic constraint generation and validation
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 5. Generate ability constraints for comparison operations
  - Add constraint generation for ==, != operators requiring Eq ability
  - Add constraint generation for <, >, <=, >= operators requiring Ord ability
  - Implement constraint propagation for comparison operations in polymorphic contexts
  - Add validation for comparison operations on concrete types
  - Write comprehensive tests for comparison constraint generation
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 6. Enhance type display with constraint information
  - Extend TypePrinter in ast/TypePrinter.kt to display ability constraints
  - Implement printTypeWithConstraints method for constraint-aware type display
  - Add constraint formatting for function types like "{x: 'a, y: 'a} -> 'a with NumAdd 'a"
  - Update type inference output to show constraints in CLI type checking
  - Create tests for constraint display formatting and readability
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [x] 7. Implement constraint inference and propagation
  - Add constraint propagation through function calls and let-polymorphism
  - Implement TypeScheme enhancement to include ability constraints
  - Add constraint instantiation for polymorphic function calls
  - Implement constraint merging for composed functions with different requirements
  - Write tests for complex constraint propagation scenarios
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 8. Integrate qualified function calls with ability constraints
  - Enhance ModuleResolver to handle ability constraints in exported functions
  - Implement constraint propagation for qualified calls like int::to_string
  - Add support for TypeScheme with constraints in module exports
  - Implement qualified call resolution with constraint validation
  - Create tests for module resolution with ability constraints
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [x] 9. Implement complex expression type inference
  - Add support for constraint resolution in do expressions with mixed operations
  - Implement type inference for expressions like "{x, y} -> do let sum = x + y int::to_string {value = sum}"
  - Add constraint propagation through let bindings in complex expressions
  - Implement polymorphic type resolution to concrete types based on usage context
  - Write comprehensive tests for complex expression scenarios
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 10. Enhance error reporting for ability constraint violations
  - Implement AbilityError classes for missing abilities and conflicting constraints
  - Add detailed error messages showing which ability is missing for which operation
  - Implement suggestion generation for common ability constraint errors
  - Add source location tracking for ability constraint errors
  - Create comprehensive error reporting test suite
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 11. Ensure backward compatibility with existing code
  - Test existing polymorphic functions to ensure they work with inferred constraints
  - Validate that concrete type operations continue to work without changes
  - Ensure existing type annotations are compatible with the new constraint system
  - Test mixed usage patterns of explicit and inferred constraints
  - Run full regression test suite to ensure no breaking changes
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

- [x] 12. Optimize constraint solving performance
  - Implement incremental constraint solving with early termination for unsolvable constraints
  - Add constraint dependency analysis to avoid redundant constraint checking
  - Optimize ability lookups with caching for frequently accessed concrete types
  - Implement constraint solving timeout mechanisms for complex scenarios
  - Create performance benchmarks and validate polynomial time complexity
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [x] 13. Fix qualified call constraint propagation
  - Implement proper constraint generation for qualified calls like `int::to_string`
  - Add type unification from qualified call parameter types to force concrete types
  - Ensure qualified calls propagate type constraints back to calling context
  - Fix module resolution to properly resolve `int` module and its functions
  - Add tests for qualified call constraint propagation scenarios
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [x] 14. Ensure int module is in scope by default
  - Add automatic import of core modules (int, string, etc.) in type checker
  - Implement built-in module registry for standard library modules
  - Add `int::to_string`, `int::from_string` and other core int functions to module scope
  - Ensure CLI automatically has access to standard library modules
  - Create tests to verify standard modules are available without explicit imports
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [x] 15. Fix constraint unification for concrete type forcing
  - Implement proper unification when qualified calls require specific types
  - Add constraint solving that forces polymorphic variables to concrete types
  - Fix type inference to properly handle `int::to_string {value = sum}` forcing sum to Int
  - Ensure return type inference works correctly for qualified calls
  - Add comprehensive tests for type forcing through qualified calls
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 16. Implement qualified identifier resolution in type checker
  - Add qualified name parsing and resolution in identifier synthesis
  - Implement module lookup for qualified identifiers like `int::to_string`
  - Add proper error handling for undefined modules and missing exports
  - Integrate qualified resolution with existing module resolver
  - Add tests for qualified identifier type checking and error cases
  - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [x] 17. Fix constraint propagation for qualified calls with arithmetic expressions
  - Ensure that `{x, y} -> int::to_string {value = x + y}` properly infers both x and y as Int
  - Fix constraint solver to handle transitive unification of related type variables
  - Enhance substitution composition to resolve constraint chains like 'a -> 'b, 'b -> Int => 'a -> Int
  - Add specific logic for qualified calls to propagate concrete type constraints back to operands
  - Test that record access like `{r} -> int::to_string {value = r.x + r.y}` infers r as {x: Int, y: Int}
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_