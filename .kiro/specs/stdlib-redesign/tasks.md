# Standard Library Redesign Implementation Plan

- [x] 1. Set up core stdlib directory structure
  - Create std/core/ directory with abilities.funk, primitives.funk, operators.funk
  - Create std/effects/, std/math/, std/string/, std/collections/, std/io/, std/async/ directories
  - Set up module resolution to recognize std:: imports
  - _Requirements: 3.1, 3.2, 3.3, 5.1_

- [x] 2. Implement granular core abilities framework
  - Create NumAdd, NumSub, NumMul, NumDiv, NumMod abilities (separate abilities with shorter names)
  - Create Eq ability with equals method
  - Create Ord ability with compare, less_than, greater_than methods (depends on Eq)
  - Create Show ability with to_string method
  - Define Ordering type with Less, Equal, Greater constructors
  - _Requirements: 1.1, 1.2, 1.3, 5.2_

- [x] 3. Implement primitive type ability implementations using existing natives
  - Create NumAdd, NumSub, NumMul, NumDiv, NumMod, Eq, Ord, Show implementations for Int (using add_int, subtract_int, etc.)
  - Create NumAdd, NumSub, NumMul, NumDiv, NumMod, Eq, Ord, Show implementations for Float (using add_float, subtract_float, etc.)
  - Create Eq, Ord, Show implementations for String type (using existing string natives)
  - Create Eq, Show implementations for Boolean type
  - Create NumAdd, NumSub, NumMul, NumDiv, NumMod, Eq, Ord, Show implementations for Nat (using add_nat, subtract_nat, etc.)
  - Add special case for Nat subtraction returning Int (using subtract_nat)
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 4. Create infix operator definitions using granular abilities
  - Implement (+) operator using NumAdd ability
  - Implement (-) operator using NumSub ability  
  - Implement (*) operator using NumMul ability
  - Implement (/) operator using NumDiv ability
  - Implement (%) operator using NumMod ability
  - Implement (==), (!=) operators using Eq ability
  - Implement (<), (>), (<=), (>=) operators using Ord ability
  - Add special case (-) operator for Nat types returning Int
  - Ensure operators are polymorphic over appropriate ability constraints
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.6_

- [x] 5. Update parser for infix operator desugaring
  - Modify visitBinaryOperation to desugar + to (+) function call
  - Modify visitBinaryOperation to desugar - to (-) function call
  - Modify visitBinaryOperation to desugar *, /, % to function calls
  - Modify visitComparisonExpression to desugar ==, !=, <, >, <=, >= to function calls
  - Ensure proper argument structure with left and right parameters
  - _Requirements: 1.1, 1.2, 1.3, 5.2, 5.3_

- [x] 6. Update TypeChecker for automatic core imports
  - Modify registerStdLibTypes to auto-import std::core::abilities
  - Modify registerStdLibTypes to auto-import std::core::primitives
  - Modify registerStdLibTypes to auto-import std::core::operators
  - Ensure core modules are always available without explicit imports
  - Add validation for ability constraint satisfaction
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 1.5_

- [x] 7. Create type-specific modules with native implementations and conversions
  - Create std/types/int.funk with Int operations (add, subtract, etc.) and conversions (from_float, from_nat, from_string)
  - Create std/types/float.funk with Float operations and conversions (from_int, from_nat, from_string)
  - Create std/types/nat.funk with Nat operations and conversions (from_int, from_float, from_string)
  - Create std/types/string.funk with String operations (length, concat, split) and conversions (to_int, to_float, to_nat)
  - Create std/types/boolean.funk with Boolean operations and conversions (from_string)
  - Include utility functions like abs, min, max for numeric types
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 6.2_

- [x] 8. Implement native function hiding mechanism
  - Create StdLibNativeImplementations.kt with comprehensive native function registry
  - Hide all functions starting with __native_ from user code access
  - Remove direct print/debug exposure - only accessible through effects
  - Implement __native_console_print, __native_console_debug for effect system
  - Implement __native_fs_read_file, __native_fs_write_file for file system effects
  - Ensure clean separation between user-accessible and internal functions
  - _Requirements: 4.4, 4.5_

- [x] 9. Port existing stdlib implementations to new architecture
  - Integrate existing NumericAbilities.kt, StringAbilities.kt, MathAbilities.kt, etc.
  - Update existing StdLib.kt to use new ability-based architecture
  - Port existing native implementations to use __native_ prefix for hiding
  - Ensure existing functionality is preserved while adding new ability system
  - Update existing effects modules to use hidden native functions
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 4.4_

- [x] 8. Create std::effects modules using existing natives
  - Create std/effects/console.funk with Console effect (print, debug) using existing print, debug natives
  - Create std/effects/filesystem.funk with FileSystem effect (read_file, write_file) using existing natives
  - Create std/effects/async.funk with async/await capabilities
  - Create std/effects/random.funk with random number generation
  - Create std/effects/system.funk with system calls and environment access
  - Ensure no direct function exposure - only through effects
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [x] 9. Create std::math modules
  - Create std/math/basic.funk with abs, min, max, pow, sqrt functions
  - Create std/math/trig.funk with sin, cos, tan, asin, acos, atan functions
  - Create std/math/stats.funk with mean, median, standard deviation functions
  - Create std/math/random.funk with random distributions
  - Implement native mathematical operations with proper error handling
  - _Requirements: 6.1, 3.1, 3.2, 3.3_

- [x] 10. Create std::string modules
  - Create std/string/core.funk with length, substring, concat, split functions
  - Create std/string/format.funk with string formatting and template functions
  - Create std/string/regex.funk with regular expression operations
  - Create std/string/unicode.funk with Unicode string operations
  - Implement efficient native string operations
  - _Requirements: 6.2, 3.1, 3.2, 3.3_

- [x] 11. Create std::collections modules using existing natives
  - ✅ Create std/collections/arraylist.funk with comprehensive array operations using ['a] syntax
  - ✅ Create std/collections/map.funk with HashMap-based Map 'k 'v operations
  - ✅ Create std/collections/set.funk with HashSet-based Set 'a operations
  - ✅ Create higher-order functions (map, filter, fold) for all collections
  - ✅ Implement native operations for Map and Set with immutable semantics
  - _Requirements: 6.3, 3.1, 3.2, 3.3_

- [x] 12. Create std::io modules
  - Create std/io/console.funk with console I/O operations
  - Create std/io/file.funk with file reading/writing operations
  - Create std/io/network.funk with HTTP, TCP/UDP operations
  - Create std/io/json.funk with JSON parsing and serialization
  - Implement secure native I/O operations with proper error handling
  - _Requirements: 6.5, 3.1, 3.2, 3.3_

- [x] 13. Create std::async modules (REMOVED - simplified stdlib)
  - ~~Create std/async/futures.funk with Future/Promise operations~~ - REMOVED
  - ~~Create std/async/channels.funk with channel communication~~ - REMOVED  
  - ~~Create std/async/parallel.funk with parallel execution utilities~~ - REMOVED
  - ~~Implement native async operations using Truffle async capabilities~~ - REMOVED
  - Async functionality moved to dedicated concurrency system
  - _Requirements: 6.4, 4.2, 3.1, 3.2_

- [x] 14. Update module resolution for std:: imports
  - Modify ModuleResolver to handle std:: module paths
  - Add special resolution logic for standard library modules
  - Ensure std:: modules are found in stdlib directory
  - Add caching for frequently used stdlib modules
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 15. Create comprehensive test suite
  - Write tests for all core abilities (Numeric, Eq, Ord, Show)
  - Write tests for primitive type implementations
  - Write tests for infix operator functionality
  - Write tests for all stdlib modules (effects, math, string, collections, io, async)
  - Write integration tests for ability constraint checking
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [ ] 16. Add error handling and validation
  - Implement compile-time ability constraint validation
  - Add clear error messages for missing ability implementations
  - Implement runtime error handling for native operations
  - Add validation for effect usage and implementations
  - Ensure graceful handling of I/O and system operation failures
  - _Requirements: 1.5, 4.5, 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 17. Performance optimization and benchmarking
  - Optimize native implementations for primitive operations
  - Add Truffle specialization for hot paths
  - Implement efficient ability dispatch mechanisms
  - Create performance benchmarks for stdlib operations
  - Ensure minimal overhead for core operations
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [ ] 18. Documentation and examples
  - Create comprehensive API documentation for all stdlib modules
  - Add usage examples for core abilities and operators
  - Create tutorials for effect usage and custom ability implementations
  - Add migration guide from old stdlib to new ability-based system
  - Create best practices guide for stdlib usage
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5_