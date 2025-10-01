# Design Document

## Overview

This design addresses the critical syntax errors in the Funk standard library and establishes a robust architecture for stdlib modules, native function integration, and comprehensive testing. The current stdlib contains incorrect syntax for variant types, match expressions, and function definitions that prevent proper parsing and execution.

## Architecture

### Stdlib Module Structure
```
stdlib/std/
├── core/           # Core types and abilities (auto-imported)
│   ├── primitives.funk    # Basic types and operations
│   ├── option.funk        # Option 'a variant type
│   ├── result.funk        # Result 'a 'e variant type
│   ├── abilities.funk     # Core abilities
│   └── operators.funk     # Operator definitions
├── collections/    # Collection types
│   ├── list.funk          # List 'a operations (native List implementation)
│   ├── map.funk           # Map 'a 'b operations (native Map implementation)
│   └── array.funk         # ['a] operations (array list syntax)

├── types/          # Type-specific operations
│   ├── int.funk          # Int operations
│   ├── float.funk        # Float operations
│   ├── frac.funk         # Fraction operations
│   ├── string.funk       # String operations
│   ├── boolean.funk      # Boolean operations
│   └── nat.funk          # Natural number operations
├── math/           # Mathematical operations
│   └── basic.funk        # Basic math functions
└── effects/        # Effect system (expanded)
    ├── console.funk       # Console I/O effects
    ├── filesystem.funk    # File system effects
    ├── log.funk          # Logging effects
    └── concurrency.funk   # Concurrency and async operations (Loom-based)
```

### Native Function Integration Architecture
```
interpreter/src/main/kotlin/com/funk/language/interpreter/stdlib/
├── TruffleNativeImplementations.kt    # Main native function registry
├── numeric/
│   └── TruffleNumericImplementations.kt  # Numeric operations
├── collections/
│   ├── TruffleListImplementations.kt        # Native List 'a operations
│   ├── TruffleMapImplementations.kt         # Native Map 'a 'b operations
│   └── TruffleArrayImplementations.kt       # Native ['a] array operations
├── effects/
│   ├── TruffleEffectImplementations.kt     # Basic effect implementations
│   └── TruffleConcurrencyImplementations.kt # Loom-based concurrency implementations
└── types/
    └── TruffleTypeImplementations.kt       # Type-specific operations
```

## Components and Interfaces

### 1. Corrected Variant Type Syntax

**Option Type:**
```funk
type Option 'a = | Some { val: 'a }, None |
```

**Result Type:**
```funk
type Result 'a 'e = | Ok { val: 'a }, Err { error: 'e } |
```

**List Type (Native Implementation):**
```funk
# List 'a uses native Java List implementation
type List 'a = __native_list_type
```

**Map Type (Native Implementation):**
```funk
# Map 'a 'b uses native Java Map implementation
type Map 'a 'b = __native_map_type
```

**Array Syntax:**
```funk
# ['a] syntax maps to ArrayList operations
let empty: ['a] = []
let numbers: [Int] = [1, 2, 3, 4]
```

### 2. Corrected Match Expression Syntax

**Option Matching (Safe Operations Only):**
```funk
let map = { opt, f } -> match opt is
    Some { val } -> Some { val = f { val } }
    None -> None

let get_or_else = { opt, default } -> match opt is
    Some { val } -> val
    None -> default
```

**Result Matching (Safe Operations Only):**
```funk
let map = { result, f } -> match result is
    Ok { val } -> Ok { val = f { x = val } }
    Err { error } -> Err { error = error }

let map_err = { result, f } -> match result is
    Ok { val } -> Ok { val = val }
    Err { error } -> Err { error = f { x = error } }
```

### 3. Function Definition Patterns

**Simple Functions:**
```funk
let add = { x, y } -> x + y
let greet = { name } -> "Hello " ++ name
let result = add { x = 10, y = 20 }
```

**Typed Functions:**
```funk
let is_some: { opt: Option 'a } -> Boolean = { opt } -> match opt is
    Some { val } -> true
    None -> false

let is_none: { opt: Option 'a } -> Boolean = { opt } -> match opt is
    Some { val } -> false
    None -> true
```

### 4. Native Function Integration

**Registry Pattern:**
```kotlin
object TruffleNativeImplementations {
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerCoreOperations(context)
        registerOptionOperations(context)
        registerResultOperations(context)
        registerCollectionOperations(context)
        registerEffectOperations(context)
    }
}
```

**Native Function Naming Convention:**
- `__native_*` - Hidden from user code, only accessible through abilities
- `show` - Public utility functions
- Type-specific operations follow pattern: `__native_{type}_{operation}`

## Data Models

### Variant Type Representation
```kotlin
// In FunkValue.kt
sealed class FunkVariant : FunkValue {
    abstract val constructor: String
    abstract val data: FunkValue?
    
    data class OptionSome(val value: FunkValue) : FunkVariant() {
        override val constructor = "Some"
        override val data = FunkRecord(mapOf("value" to value))
    }
    
    object OptionNone : FunkVariant() {
        override val constructor = "None"
        override val data = null
    }
}
```

### Native Function Integration
```kotlin
// Pattern for native function implementation
context["__native_option_map"] = createNativeFunction("__native_option_map") { record ->
    val opt = record.getField("opt") as FunkVariant
    val f = record.getField("f") as FunkFunction
    
    when (opt) {
        is FunkVariant.OptionSome -> {
            val result = f.call(FunkRecord(mapOf("value" to opt.value)))
            FunkVariant.OptionSome(result)
        }
        is FunkVariant.OptionNone -> FunkVariant.OptionNone
    }
}
```

## Error Handling

### Syntax Error Prevention
1. **Grammar Validation**: All .funk files must parse successfully with the Funk grammar
2. **Type Checking**: All stdlib functions must type-check correctly
3. **Native Integration**: All `__native_*` functions must have corresponding implementations

### Runtime Error Handling
1. **Crash Functions**: Use `crash { msg = "..." }` for unrecoverable errors (avoid unsafe operations like unwrap)
2. **Result Types**: Use Result 'a 'e for recoverable errors with safe operations (map, map_err, get_or_else)
3. **Option Types**: Use Option 'a for optional values with safe operations (map, get_or_else, is_some, is_none)

## Testing Strategy

### 1. Syntax Validation Tests
```kotlin
class StdLibSyntaxTest {
    @Test
    fun `all stdlib files parse successfully`() {
        val stdlibFiles = listOf(
            "stdlib/std/core/option.funk",
            "stdlib/std/core/result.funk",
            // ... all stdlib files
        )
        
        stdlibFiles.forEach { file ->
            assertDoesNotThrow("$file should parse without errors") {
                FunkParser.parseFile(file)
            }
        }
    }
}
```

### 2. Native Function Integration Tests
```kotlin
class NativeIntegrationTest {
    @Test
    fun `option operations work correctly`() {
        val some = interpretExpression("Some { value = 42 }")
        val none = interpretExpression("None")
        
        val mapped = interpretExpression("map { opt = some, f = { x } -> x + 1 }")
        assertEquals(43, (mapped as OptionSome).value)
    }
}
```

### 3. Comprehensive Stdlib Tests
```kotlin
class StdLibComprehensiveTest {
    @Test
    fun `option type operations`() {
        // Test all Option operations: map, flatMap, unwrap, etc.
    }
    
    @Test
    fun `result type operations`() {
        // Test all Result operations: map, mapErr, unwrap, etc.
    }
    
    @Test
    fun `collection operations`() {
        // Test List 'a operations (native implementation)
        // Test Map 'a 'b operations (native implementation)
        // Test ['a] array syntax operations
    }
    
    @Test
    fun `fraction type operations`() {
        // Test Frac operations and arithmetic
    }
    
    @Test
    fun `effect operations`() {
        // Test console, filesystem, logging effects
    }
}
```

### 4. Backward Compatibility Tests
```kotlin
class BackwardCompatibilityTest {
    @Test
    fun `existing tests still pass`() {
        // Run all existing tests to ensure no regressions
        // Add use statements only if needed for module resolution
    }
}
```

### 5. Test File Updates
- **Minimal Changes**: Only add `use` statements if tests fail due to missing imports
- **Module Resolution**: Ensure tests can find stdlib modules
- **Preserve Behavior**: All existing test behavior must be maintained

## Implementation Phases

### Phase 1: Core Type Fixes
1. Fix Option and Result variant syntax
2. Fix match expressions in core types
3. Update native function calls

### Phase 2: Function Definition Fixes
1. Correct all function definition syntax
2. Fix type variable usage
3. Update parameter passing

### Phase 3: Native Integration Restructure
1. Reorganize native function implementations
2. Ensure all referenced `__native_*` functions exist
3. Add missing implementations

### Phase 4: Comprehensive Testing
1. Create syntax validation tests
2. Add native integration tests
3. Update existing tests with minimal changes
4. Verify backward compatibility

### Phase 5: Documentation and Validation
1. Update stdlib documentation
2. Validate all modules load correctly
3. Performance testing of native integrations