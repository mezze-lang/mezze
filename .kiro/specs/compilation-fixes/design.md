# Compilation Fixes Design Document

## Overview

This design addresses the systematic resolution of compilation errors across the Funk language project. The errors fall into several categories: missing type definitions, unresolved imports, incorrect API usage, and test file issues. The approach will be to fix issues in dependency order, starting with core types and working up to higher-level components.

## Architecture

### Dependency Resolution Order

1. **AST Module** - Core type definitions and constraints
2. **Parser Module** - Depends on AST types
3. **TypeChecker Module** - Depends on AST and Parser
4. **Test Files** - Depend on all modules

### Error Categories

#### Category 1: Missing Type Definitions
- `ArrayType` - Missing from AST module
- `ArrayTypeAnnotation` - Missing from AST module  
- `ArrayLiteral` - Missing from AST module
- `DisplayAbilityConstraint` - Missing constraint type

#### Category 2: Import Resolution
- `stdlib` import in TypeChecker
- `BidirectionalTypeChecker` import in TypeChecker
- Cross-module type imports

#### Category 3: API Signature Issues
- `ModuleError.ExportNotFound` constructor calls
- Generic type parameter inference
- Collection method ambiguity resolution

## Components and Interfaces

### AST Module Fixes

#### ArrayType Definition
```kotlin
// Add to FunkAST.kt
@Serializable
data class ArrayType(
    val elementType: FunkType
) : FunkType()
```

#### ArrayTypeAnnotation Definition
```kotlin
// Add to FunkAST.kt
@Serializable
data class ArrayTypeAnnotation(
    val elementType: TypeAnnotation
) : TypeAnnotation()
```

#### ArrayLiteral Definition
```kotlin
// Add to FunkAST.kt
@Serializable
data class ArrayLiteral(
    val elements: List<Expression>
) : Expression()
```

#### DisplayAbilityConstraint Definition
```kotlin
// Add constraint display functionality
data class DisplayAbilityConstraint(
    val abilityName: String,
    val typeParam: FunkType
)
```

### TypeChecker Module Fixes

#### Import Resolution
- Add proper stdlib import path
- Add BidirectionalTypeChecker import or remove usage
- Fix cross-module dependencies

#### API Signature Fixes
- Fix ModuleError.ExportNotFound constructor calls
- Add explicit type parameters for generic inference
- Resolve collection method ambiguities

### Parser Module Fixes

#### Array Support
- Ensure ArrayLiteral parsing is properly integrated
- Fix test file references to array types
- Update parser grammar if needed

## Data Models

### Type System Integration

#### ArrayType Integration
```kotlin
// Update type checking logic
when (type) {
    is ArrayType -> handleArrayType(type)
    is ArrayListType -> handleArrayListType(type)
    // ... existing cases
}
```

#### Constraint System Integration
```kotlin
// Update constraint solver
when (constraint) {
    is AbilityConstraint -> handleAbilityConstraint(constraint)
    is DisplayAbilityConstraint -> handleDisplayConstraint(constraint)
    // ... existing cases
}
```

## Error Handling

### Compilation Error Recovery
- Fix errors in dependency order to minimize cascading failures
- Preserve existing functionality while adding missing components
- Ensure backward compatibility with existing code

### Missing Reference Resolution
- Add missing imports systematically
- Define missing types with minimal viable implementations
- Update API calls to match current signatures

## Testing Strategy

### Incremental Compilation Testing
1. Fix AST module and verify compilation
2. Fix Parser module and verify compilation  
3. Fix TypeChecker module and verify compilation
4. Fix test files and verify full build

### Functionality Preservation Testing
1. Verify qualified call detection still works
2. Verify module resolution remains functional
3. Verify constraint propagation is preserved
4. Verify error handling remains intact

### Build Verification
1. Run `gradle build` after each major fix
2. Ensure no new compilation errors are introduced
3. Verify all modules compile successfully
4. Confirm test files are compilable

## Implementation Phases

### Phase 1: Core Type Definitions
- Add missing ArrayType, ArrayTypeAnnotation, ArrayLiteral to AST
- Add missing constraint types
- Update type system integration

### Phase 2: Import Resolution
- Fix stdlib import in TypeChecker
- Resolve BidirectionalTypeChecker reference
- Add missing cross-module imports

### Phase 3: API Signature Fixes
- Fix ModuleError constructor calls
- Resolve generic type inference issues
- Fix collection method ambiguities

### Phase 4: Test File Fixes
- Update test files to use correct APIs
- Fix missing references in test code
- Ensure all test files compile

### Phase 5: Build Verification
- Run full build and verify success
- Test qualified call functionality
- Confirm no regressions introduced