# Implementation Plan

- [ ] 1. Fix Core Variant Type Syntax
  - Rewrite Option type with correct variant syntax: `type Option 'a = | Some { val: 'a }, None |`
  - Rewrite Result type with correct variant syntax: `type Result 'a 'e = | Ok { val: 'a }, Err { error: 'e } |`
  - Remove all incorrect syntax from option.funk and result.funk
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 2. Fix Match Expression Syntax
  - Update all match expressions to use `match expr is` syntax
  - Fix pattern matching to use correct constructor patterns like `Some { val } -> val`
  - Update multiple pattern syntax to use `pattern1 | pattern2 -> result`
  - Remove any incorrect match syntax
  - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [ ] 3. Implement Safe Option Operations
  - Create `map` function for Option type using correct match syntax
  - Create `get_or_else` function for safe value extraction
  - Create `is_some` and `is_none` predicate functions
  - Remove unsafe `unwrap` function completely
  - Use snake_case naming convention for all functions
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 4. Implement Safe Result Operations
  - Create `map` function for Result type using correct match syntax
  - Create `map_err` function for error transformation
  - Create `get_or_else` function for safe value extraction
  - Create `is_ok` and `is_err` predicate functions
  - Remove unsafe `unwrap` and `unwrap_err` functions completely
  - Use snake_case naming convention for all functions
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 5. Fix Function Definition Syntax
  - Update all function definitions to use `let name = { params } -> body` syntax
  - Fix typed function definitions to use `let name: { param: Type } -> ReturnType = { param } -> body`
  - Ensure all parameters use record syntax `{ param1, param2 }`
  - Update function calls to use record syntax `func { param1 = value1, param2 = value2 }`
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 6. Implement Crash Function
  - Create `crash` function that takes `{ msg: String }` parameter
  - Implement native `__native_crash` function in TruffleNativeImplementations.kt
  - Replace all `__native_panic` calls with `crash { msg = "..." }`
  - Ensure crash function exits the program cleanly
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 7. Add Fraction Type Support
  - Create `stdlib/std/types/frac.funk` with Frac type operations
  - Implement native fraction operations in TruffleNumericImplementations.kt
  - Add fraction arithmetic operations (add, subtract, multiply, divide)
  - Add fraction conversion functions (to_float, from_int, etc.)
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 8. Implement Native List Type
  - Create `stdlib/std/collections/list.funk` with List 'a operations
  - Implement `TruffleListImplementations.kt` for native Java List operations
  - Add core list operations (map, filter, fold, length, etc.)
  - Use correct type syntax `List 'a` throughout
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 9. Implement Native Map Type
  - Create `stdlib/std/collections/map.funk` with Map 'a 'b operations
  - Implement `TruffleMapImplementations.kt` for native Java Map operations
  - Add core map operations (get, put, remove, keys, values, etc.)
  - Use correct type syntax `Map 'a 'b` throughout
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 10. Fix Array List Syntax
  - Update `stdlib/std/collections/array.funk` to use `['a]` syntax correctly
  - Ensure array literal syntax `[1, 2, 3]` works properly
  - Update all array operations to use correct syntax
  - Test array list operations with interpreter
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 11. Restructure Native Function Organization
  - Move all numeric operations to `TruffleNumericImplementations.kt`
  - Move all collection operations to separate implementation files
  - Move all effect operations to `TruffleEffectImplementations.kt`
  - Ensure all `__native_*` functions referenced in .funk files are implemented
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 12. Update Concurrency Effects
  - Move async operations from `stdlib/std/async/futures.funk` to `stdlib/std/effects/concurrency.funk`
  - Integrate with existing Loom-based concurrency system
  - Update concurrency operations to use correct Funk syntax
  - Remove duplicate async module structure
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [ ] 13. Create Comprehensive Syntax Tests
  - Create `StdLibSyntaxTest.kt` to validate all .funk files parse correctly
  - Test each stdlib module individually for syntax errors
  - Ensure all variant types parse with correct comma-separated syntax
  - Ensure all match expressions parse with correct `match expr is` syntax
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 14. Create Native Integration Tests
  - Create `NativeIntegrationTest.kt` to test all native function calls
  - Test Option operations (map, get_or_else, is_some, is_none)
  - Test Result operations (map, map_err, get_or_else, is_ok, is_err)
  - Test collection operations (List, Map, Array)
  - Test crash function behavior
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 15. Update Existing Tests
  - Run all existing tests to identify failures due to syntax changes
  - Add minimal `use` statements only where needed for module resolution
  - Update test expectations to match new snake_case function names
  - Ensure no existing functionality is broken
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

- [ ] 16. Validate Complete System
  - Run full test suite to ensure no regressions
  - Test stdlib loading and module resolution
  - Verify all native functions are properly registered
  - Test end-to-end functionality with corrected syntax
  - Performance test native integrations
  - _Requirements: 6.1, 6.2, 6.3, 6.4_