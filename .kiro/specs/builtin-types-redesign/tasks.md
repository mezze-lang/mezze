# Implementation Plan

- [x] 1. Define built-in type system in AST
  - Add built-in type definitions to AST module for Option, Result, List, Map, Set
  - Define built-in ability interfaces in AST (Eq, Ord, Show, NumAdd, etc.)
  - Create automatic ability implementation mappings for primitive types
  - Update type printer to handle built-in types specially
  - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.2, 3.3_

- [x] 2. Update parser for built-in operators and syntax
  - Modify grammar to parse operators as special constructs rather than function calls
  - Add operator precedence and associativity rules to parser
  - Update list literal parsing to create built-in List types
  - Add special handling for Option and Result constructor syntax
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 1.4, 1.5_

- [x] 3. Implement built-in types in type checker
  - Add built-in types to global type environment automatically
  - Implement operator type checking through ability resolution
  - Add automatic ability implementations for built-in types
  - Remove dependency on stdlib modules for core types
  - _Requirements: 5.1, 5.2, 5.3, 3.4, 3.5_

- [x] 4. Update module resolver to skip core modules
  - Modify module loading to not load core type definitions
  - Update stdlib module resolution to only load function definitions
  - Add prelude loading that makes built-ins available globally
  - Remove circular dependency handling for core modules
  - _Requirements: 5.4, 4.4, 8.1, 8.2_

- [x] 5. Implement native runtime representations
  - Add efficient native implementations for Option, Result, List in interpreter
  - Implement built-in operator evaluation in interpreter nodes
  - Add optimized ability dispatch for built-in types
  - Create native constructors for Some, None, Ok, Err, etc.
  - _Requirements: 7.1, 7.2, 7.3, 1.1, 1.2, 1.3_

- [x] 5.1. Update native function implementations for consistency
  - Rename all `__native_*` function parameters to use short, consistent naming conventions
  - Update binary operations to use `l` and `r` parameters
  - Change `value` parameters to `val` throughout native functions
  - Update collection parameters to use `xs` (lists), `m` (maps), `s` (sets)
  - Use `f` for functions, `p` for predicates, `i` for indices, `x` for elements
  - Ensure all native functions follow the new short parameter naming standards
  - _Requirements: 8.3, 8.4_

- [x] 6. Restructure stdlib to functions-only
  - Move all type definitions out of stdlib modules
  - Rewrite stdlib modules to contain only function definitions
  - Update stdlib functions to work on built-in types
  - Remove core modules (abilities.funk, operators.funk, primitives.funk)
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 8.3_

- [x] 7. Update error messages and diagnostics
  - Add clear distinction between built-in and stdlib features in error messages
  - Implement helpful migration warnings for deprecated core imports
  - Update type error messages to suggest appropriate stdlib imports
  - Add IDE integration hints for built-in vs stdlib features
  - _Requirements: 8.1, 8.2, 8.4, 8.5_

- [x] 8. Create comprehensive test suite
  - Write tests for all built-in types without imports
  - Test all operators work as language constructs
  - Verify ability constraints work with built-in abilities
  - Test stdlib functions work on built-in types with proper imports
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 3.1, 4.1, 4.5_

- [x] 9. Performance optimization and validation
  - Benchmark startup time improvement from removing module loading
  - Optimize operator compilation to native operations
  - Measure type checking performance improvements
  - Validate memory efficiency of built-in type representations
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 10. Migration guide and documentation
  - Create migration guide for existing codebases
  - Document new built-in types and their usage
  - Update language reference to distinguish built-ins from stdlib
  - Provide examples of new stdlib function usage patterns
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 8.5_

- [-] 11. Backward compatibility validation
  - Test that existing Option/Result syntax continues to work
  - Verify existing operator usage remains unchanged
  - Ensure existing ability constraints work without modification
  - Test that only import statements need to be updated in existing code
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [-] 12. Integration testing and validation
  - Test complex programs using both built-ins and stdlib functions
  - Verify qualified calls work correctly (list::map, option::flatMap)
  - Test that module system works cleanly with new architecture
  - Validate that circular dependency issues are resolved
  - _Requirements: 4.5, 5.1, 5.2, 5.3, 5.4_