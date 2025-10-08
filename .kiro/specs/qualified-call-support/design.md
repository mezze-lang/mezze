# Qualified Call Support Design

## Overview

This design implements qualified call syntax (`module::function`) for the Funk language, enabling direct access to module functions with proper type inference and constraint propagation. The implementation integrates with the existing TypeChecker and ModuleResolver systems.

## Architecture

### Core Components

1. **Qualified Call Detection** - Identifies `::` syntax in function identifiers
2. **Module Resolution** - Maps short names to full module paths
3. **Function Lookup** - Finds functions in resolved modules
4. **Constraint Propagation** - Applies type constraints for known functions
5. **Error Handling** - Provides clear diagnostics for failures

### Integration Points

- **TypeChecker.inferBasicType()** - Main entry point for function call processing
- **ModuleResolver** - Existing module resolution infrastructure
- **ConstraintSolver** - Existing constraint propagation system

## Components and Interfaces

### QualifiedCallHandler

```kotlin
class QualifiedCallHandler {
    fun isQualifiedCall(identifier: String): Boolean
    fun handleQualifiedCall(qualifiedName: String, arguments: Expression): FunkType
    fun applyQualifiedCallConstraints(qualifiedName: String, argumentType: FunkType, functionType: FunctionType)
}
```

### Module Name Mapping

```kotlin
private val moduleNameMap = mapOf(
    "int" to "std::types::int",
    "float" to "std::types::float", 
    "string" to "std::types::string",
    "boolean" to "std::types::boolean"
)
```

### Constraint Patterns

```kotlin
private val constraintPatterns = mapOf(
    "int::to_string" to ValueFieldConstraint(IntType),
    "float::to_string" to ValueFieldConstraint(FloatType),
    "string::length" to StrFieldConstraint(StringType)
)
```

## Data Models

### QualifiedCallInfo

```kotlin
data class QualifiedCallInfo(
    val moduleName: String,
    val functionName: String,
    val fullModulePath: String,
    val functionType: FunctionType
)
```

### ConstraintPattern

```kotlin
sealed class ConstraintPattern {
    data class ValueFieldConstraint(val expectedType: FunkType) : ConstraintPattern()
    data class StrFieldConstraint(val expectedType: FunkType) : ConstraintPattern()
    data class CustomConstraint(val applier: (FunkType, FunctionType) -> Unit) : ConstraintPattern()
}
```

## Error Handling

### Error Types

1. **InvalidQualifiedCallFormat** - Malformed `::` syntax
2. **ModuleNotFound** - Module resolution failure
3. **FunctionNotFound** - Function not exported by module
4. **NotAFunction** - Qualified target is not a function
5. **ModuleResolverUnavailable** - System configuration issue

### Error Messages

```kotlin
"Invalid qualified call format: $qualifiedName (expected module::function)"
"Module not found: $moduleName (resolved to $fullModulePath)"
"Function not found: $functionName in module $fullModulePath"
"Qualified call target is not a function: $qualifiedName"
"Module resolver not available for qualified call: $qualifiedName"
```

## Testing Strategy

### Unit Tests

1. **QualifiedCallDetectionTest** - Test `::` syntax recognition
2. **ModuleResolutionTest** - Test module name mapping
3. **FunctionLookupTest** - Test function resolution in modules
4. **ConstraintPropagationTest** - Test type constraint application
5. **ErrorHandlingTest** - Test error message generation

### Integration Tests

1. **BasicQualifiedCallTest** - Simple qualified calls work
2. **DoExpressionConstraintTest** - Constraint propagation through `do` expressions
3. **StdlibIntegrationTest** - All stdlib modules work with qualified calls
4. **ComplexExpressionTest** - Nested and complex expressions work correctly

### Test Cases

```funk
// Basic qualified call
let test1 = int::to_string {value = 42}

// Constraint propagation in do expression
let test2 = {x, y} -> do
  let sum = x + y
  int::to_string {value = sum}

// Multiple qualified calls
let test3 = {s} -> do
  let len = string::length {str = s}
  int::to_string {value = len}

// Error cases
let error1 = invalid::function {value = 42}  // Module not found
let error2 = int::invalid {value = 42}       // Function not found
let error3 = int::to_string {wrong = 42}     // Type mismatch
```

## Implementation Flow

### 1. Detection Phase
```
FunctionCall -> isIdentifier? -> contains("::")? -> handleQualifiedCall()
```

### 2. Resolution Phase
```
qualifiedName -> split("::") -> mapModuleName() -> resolveModule() -> lookupFunction()
```

### 3. Type Application Phase
```
functionType -> inferArgumentType() -> addConstraints() -> applySpecificConstraints() -> returnType
```

### 4. Constraint Propagation Phase
```
constraints -> ConstraintSolver -> unification -> typeSubstitution -> finalTypes
```

## Performance Considerations

- **Module Caching** - ModuleResolver already caches resolved modules
- **Pattern Matching** - Use HashMap for O(1) constraint pattern lookup
- **Early Validation** - Fail fast on malformed qualified calls
- **Lazy Resolution** - Only resolve modules when qualified calls are used

## Security Considerations

- **Module Access Control** - Respect existing module visibility rules
- **Path Traversal** - Validate module names to prevent path traversal
- **Resource Limits** - Reuse existing module resolution limits
- **Error Information** - Don't leak internal paths in error messages