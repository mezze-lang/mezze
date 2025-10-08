# Compilation Fixes Implementation Tasks

## 1. AST Module Core Type Definitions

- [ ] 1.1 Add ArrayType definition to FunkAST.kt
  - Add ArrayType data class with elementType property
  - Integrate ArrayType into type system hierarchy
  - Update serialization annotations
  - _Requirements: 1.1, 1.4_

- [ ] 1.2 Add ArrayTypeAnnotation definition to FunkAST.kt
  - Add ArrayTypeAnnotation data class with elementType property
  - Integrate into TypeAnnotation hierarchy
  - Update serialization support
  - _Requirements: 1.2, 1.4_

- [ ] 1.3 Add ArrayLiteral definition to FunkAST.kt
  - Add ArrayLiteral data class with elements list
  - Integrate into Expression hierarchy
  - Update serialization support
  - _Requirements: 1.3, 1.4_

- [ ] 1.4 Add missing constraint types
  - Add DisplayAbilityConstraint data class
  - Add constraint display functionality
  - Update constraint system integration
  - _Requirements: 4.1, 4.4_

## 2. TypeChecker Module Import and Reference Fixes

- [ ] 2.1 Fix stdlib import in TypeChecker
  - Identify correct stdlib import path
  - Add proper import statement
  - Verify StdLib reference resolves
  - _Requirements: 2.1, 2.4_

- [ ] 2.2 Fix BidirectionalTypeChecker reference
  - Identify if BidirectionalTypeChecker exists or should be removed
  - Add proper import or remove usage
  - Update affected code accordingly
  - _Requirements: 2.2, 2.4_

- [ ] 2.3 Fix ArrayType references in ConstraintSolver
  - Add ArrayType import to ConstraintSolver
  - Update when expression to handle ArrayType
  - Fix ArrayType usage in unification logic
  - _Requirements: 1.1, 1.4_

- [ ] 2.4 Fix ArrayType references in ModuleResolver
  - Add ArrayType and ArrayTypeAnnotation imports
  - Fix type annotation processing
  - Update module type resolution
  - _Requirements: 1.1, 1.2, 1.4_

## 3. API Signature and Generic Type Fixes

- [ ] 3.1 Fix ModuleError.ExportNotFound constructor calls
  - Identify correct constructor signature
  - Update calls in TypeChecker to match signature
  - Remove extra arguments or add missing ones
  - _Requirements: 3.1, 3.4_

- [ ] 3.2 Fix generic type inference issues
  - Add explicit type parameters where needed
  - Resolve "Not enough information to infer type argument" errors
  - Update collection usage with proper type hints
  - _Requirements: 3.2, 3.4_

- [ ] 3.3 Fix collection method ambiguity
  - Resolve map() method ambiguity in TypeChecker
  - Add explicit type annotations for ambiguous calls
  - Update collection operations with clear types
  - _Requirements: 3.3, 3.4_

- [ ] 3.4 Fix constraint-related API usage
  - Update ability constraint handling
  - Fix constraint property access
  - Resolve constraint method calls
  - _Requirements: 3.4, 4.4_

## 4. Parser Module Array Support Fixes

- [ ] 4.1 Fix ArrayLiteral references in parser tests
  - Add proper ArrayLiteral imports to test files
  - Update test code to use correct ArrayLiteral API
  - Fix elements property access
  - _Requirements: 1.3, 4.3_

- [ ] 4.2 Fix ArrayTypeAnnotation references in parser tests
  - Add proper ArrayTypeAnnotation imports
  - Update test code to use correct API
  - Fix elementType property access
  - _Requirements: 1.2, 4.3_

- [ ] 4.3 Verify parser grammar supports arrays
  - Check if array parsing is properly implemented
  - Update grammar if needed for array literals
  - Ensure parser generates correct AST nodes
  - _Requirements: 1.3, 4.3_

## 5. AST Test File Fixes

- [ ] 5.1 Fix DisplayAbilityConstraint references in AST tests
  - Add proper DisplayAbilityConstraint imports
  - Update test code to use correct constraint API
  - Fix constraint property access
  - _Requirements: 4.1, 4.4_

- [ ] 5.2 Fix type display and printing test issues
  - Add missing print function imports
  - Update type display API usage
  - Fix constraint printing functionality
  - _Requirements: 4.1, 4.4_

- [ ] 5.3 Fix TypeScheme constraint test issues
  - Add missing constraint property access
  - Fix TypeScheme constructor calls
  - Update constraint-related test methods
  - _Requirements: 4.2, 4.4_

## 6. Build Verification and Testing

- [ ] 6.1 Verify AST module compilation
  - Run gradle build for AST module only
  - Fix any remaining AST compilation issues
  - Ensure all AST tests compile
  - _Requirements: 6.1, 6.2_

- [ ] 6.2 Verify Parser module compilation
  - Run gradle build for Parser module
  - Fix any remaining parser compilation issues
  - Ensure all parser tests compile
  - _Requirements: 6.1, 6.2_

- [ ] 6.3 Verify TypeChecker module compilation
  - Run gradle build for TypeChecker module
  - Fix any remaining typechecker compilation issues
  - Ensure all typechecker tests compile
  - _Requirements: 6.1, 6.2_

- [ ] 6.4 Verify full project build
  - Run complete gradle build
  - Fix any remaining cross-module issues
  - Ensure no compilation warnings remain
  - _Requirements: 6.1, 6.2, 6.3, 6.4_

## 7. Qualified Call Functionality Preservation

- [ ] 7.1 Test qualified call detection after fixes
  - Verify isQualifiedCall function still works
  - Test qualified call syntax recognition
  - Ensure no regressions in detection logic
  - _Requirements: 5.1, 5.4_

- [ ] 7.2 Test module resolution after fixes
  - Verify handleQualifiedCall function works
  - Test module path resolution
  - Ensure function lookup still functions
  - _Requirements: 5.2, 5.4_

- [ ] 7.3 Test constraint propagation after fixes
  - Verify applyQualifiedCallConstraints works
  - Test constraint generation and application
  - Ensure type inference remains functional
  - _Requirements: 5.3, 5.4_

- [ ] 7.4 Test error handling after fixes
  - Verify qualified call error messages work
  - Test all error scenarios still function
  - Ensure error recovery remains intact
  - _Requirements: 5.4_

## 8. Documentation and Cleanup

- [ ] 8.1 Document compilation fix changes
  - Document new type definitions added
  - Document import changes made
  - Document API signature fixes applied

- [ ] 8.2 Update build documentation
  - Update build instructions if needed
  - Document any new dependencies added
  - Update development setup guide

- [ ] 8.3 Clean up temporary fixes
  - Remove any temporary workarounds
  - Ensure all fixes are proper solutions
  - Verify no technical debt introduced