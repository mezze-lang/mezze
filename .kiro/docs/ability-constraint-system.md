# Ability Constraint System

The Funk programming language features a powerful **ability constraint system** that enables safe and expressive polymorphic programming. This system ensures that polymorphic functions can only be used with types that support the required operations.

## Overview

The ability constraint system extends Funk's type system with **abilities** - named sets of operations that types can support. When you write polymorphic code that uses operations like `+` or `==`, the type system automatically infers which abilities are required and ensures they are satisfied.

### Key Benefits

- **Type Safety**: Prevents runtime errors by catching ability mismatches at compile time
- **Expressiveness**: Enables powerful polymorphic programming patterns
- **Clarity**: Makes type requirements explicit and self-documenting
- **Performance**: Zero runtime overhead - all checks happen at compile time

## Basic Concepts

### Abilities

An **ability** is a named collection of operations that a type can support. Funk includes several built-in abilities:

- **`NumAdd`**: Addition operation (`+`)
- **`NumSub`**: Subtraction operation (`-`)
- **`NumMul`**: Multiplication operation (`*`)
- **`NumDiv`**: Division operation (`/`)
- **`NumMod`**: Modulo operation (`%`)
- **`Eq`**: Equality comparison (`==`, `!=`)
- **`Ord`**: Ordering comparison (`<`, `>`, `<=`, `>=`)
- **`LogicalAnd`**: Logical AND (`&&`)
- **`LogicalOr`**: Logical OR (`||`)
- **`LogicalNot`**: Logical NOT (`!`)
- **`StringConcat`**: String interpolation (via `$()` syntax)

### Built-in Type Abilities

| Type | Abilities |
|------|----------|
| `Int` | `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `NumMod`, `Eq`, `Ord` |
| `Frac` | `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `NumMod`, `Eq`, `Ord` |
| `Nat` | `NumAdd`, `NumSub`, `NumMul`, `NumDiv`, `NumMod`, `Eq`, `Ord` |
| `Bool` | `Eq`, `LogicalAnd`, `LogicalOr`, `LogicalNot` |
| `String` | `Eq`, `Ord`, `StringConcat` |

## How It Works

### Automatic Constraint Generation

When you write polymorphic code, the type system automatically generates ability constraints:

```funk
let add = {x, y} -> x + y
// Inferred type: ∀'a. {x: 'a, y: 'a} -> 'a where 'a : NumAdd
```

The constraint `'a : NumAdd` means "type variable 'a must support the NumAdd ability".

### Constraint Propagation

Constraints are automatically propagated through function calls:

```funk
let add = {x, y} -> x + y
let double = {x} -> add {x = x, y = x}
// double also requires NumAdd ability

let result = double {x = 5}  // ✓ Works - Int has NumAdd
let bad = double {x = true}   // ✗ Error - Bool lacks NumAdd
```

### Multiple Constraints

Functions can require multiple abilities:

```funk
let addAndCompare = {x, y, z} -> (x + y) == z
// Requires both NumAdd and Eq abilities
// Type: ∀'a. {x: 'a, y: 'a, z: 'a} -> Bool where 'a : NumAdd + Eq
```

## Usage Examples

### Basic Arithmetic

```funk
// Polymorphic addition function
let add = {x, y} -> x + y

// Works with any numeric type
let intResult = add {x = 1, y = 2}        // Int
let fracResult = add {x = 1.5, y = 2.5}   // Frac
let natResult = add {x = 1n, y = 2n}      // Nat

// Error: Bool doesn't support addition
let badResult = add {x = true, y = false} // ✗ Compile error
```

### Comparison Operations

```funk
// Polymorphic equality check
let isEqual = {x, y} -> x == y

// Works with types that support equality
let intEqual = isEqual {x = 1, y = 2}           // Bool
let stringEqual = isEqual {x = "a", y = "b"}     // Bool

// Polymorphic ordering
let isLess = {x, y} -> x < y
let intLess = isLess {x = 1, y = 2}             // Bool
let stringLess = isLess {x = "a", y = "b"}       // Bool
```

### Complex Constraints

```funk
// Function requiring multiple abilities
let processNumbers = {x, y, z} -> {
    let sum = x + y      // Requires NumAdd
    let isLarge = sum > z // Requires Ord
    let isEqual = sum == z // Requires Eq
    {sum = sum, isLarge = isLarge, isEqual = isEqual}
}

// Type: ∀'a. {x: 'a, y: 'a, z: 'a} -> {sum: 'a, isLarge: Bool, isEqual: Bool} 
//       where 'a : NumAdd + Ord + Eq

let result = processNumbers {x = 1, y = 2, z = 3}  // ✓ Works with Int
```

### Higher-Order Functions

```funk
// Function that takes a binary operation
let applyBinary = {f, x, y} -> f {x = x, y = y}

// Works with any binary function
let addResult = applyBinary {f = add, x = 1, y = 2}
let compareResult = applyBinary {f = isEqual, x = "a", y = "b"}
```

## Error Messages

The ability constraint system provides clear, helpful error messages:

```funk
let result = true + false
// Error: Type 'Bool' does not support ability 'NumAdd' required by operator '+'
//        at line 1, column 15
//        
//        Suggestion: Use numeric types (Int, Frac, Nat) for arithmetic operations
```

```funk
let compare = {f, g} -> f < g  // Functions don't support ordering
// Error: Type '{...} -> ...' does not support ability 'Ord' required by operator '<'
//        
//        Note: Function types cannot be compared for ordering
```

## Advanced Features

### Constraint Composition

```funk
// Function that combines multiple constraints
let complexOperation = {x, y, z} -> {
    let sum = x + y           // NumAdd
    let product = x * y       // NumMul  
    let isEqual = sum == z    // Eq
    let isLess = product < z  // Ord
    {sum = sum, product = product, isEqual = isEqual, isLess = isLess}
}

// Type: ∀'a. {x: 'a, y: 'a, z: 'a} -> {...} 
//       where 'a : NumAdd + NumMul + Eq + Ord
```

### Polymorphic Data Structures

```funk
// Generic function working with records
let processPoint = {p} -> {
    let sum = p.x + p.y      // Requires NumAdd for field types
    let isOrigin = p.x == 0 && p.y == 0  // Requires Eq
    {sum = sum, isOrigin = isOrigin}
}

let point = {x = 1, y = 2}
let result = processPoint {p = point}  // ✓ Works
```

### Module Integration

```funk
// Qualified calls work with constraints
let convertAndAdd = {x, y} -> {
    let sum = x + y                    // NumAdd constraint
    string::from_int {value = sum}     // Qualified call
}

// Type: ∀'a. {x: 'a, y: 'a} -> String where 'a : NumAdd
```

## Implementation Architecture

### Core Components

1. **AbilityRegistry**: Manages built-in and user-defined abilities
2. **ConstraintSolver**: Handles ability constraint solving and validation
3. **TypeChecker**: Generates constraints during type inference
4. **TypePrinter**: Displays types with constraint information

### Constraint Generation

The type checker automatically generates ability constraints when it encounters:

- Binary operators (`+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`)
- Logical operators (`&&`, `||`, `!`)
- String interpolation (`$()`)
- Any operation that requires specific type capabilities

### Constraint Solving

The constraint solver:

1. Collects all ability constraints during type inference
2. Validates constraints against concrete types
3. Propagates constraints through polymorphic instantiation
4. Reports clear error messages for constraint violations

### Performance Characteristics

- **Constraint Generation**: O(n) where n is the number of operations
- **Constraint Solving**: O(c) where c is the number of constraints
- **Memory Usage**: Minimal overhead - constraints are lightweight
- **Runtime Impact**: Zero - all checking happens at compile time

## Best Practices

### Writing Polymorphic Functions

```funk
// Good: Clear, simple constraints
let add = {x, y} -> x + y

// Good: Multiple related constraints
let addAndCompare = {x, y, z} -> (x + y) == z

// Avoid: Overly complex constraint combinations
// let complexFunc = {x} -> x + x * x - x / x == x && x > x
```

### Error Handling

```funk
// The type system will catch constraint violations at compile time
let safeAdd = {x, y} -> x + y

// This will be caught before runtime:
// let badCall = safeAdd {x = true, y = false}  // Compile error
```

### Testing Polymorphic Code

```funk
// Test with different numeric types
let testAdd = () -> {
    assert (add {x = 1, y = 2} == 3)           // Int
    assert (add {x = 1.5, y = 2.5} == 4.0)     // Frac
    assert (add {x = 1n, y = 2n} == 3n)        // Nat
}
```

## Migration Guide

### From Untyped Code

If you have existing polymorphic functions, they should work automatically:

```funk
// Before: Works but no compile-time safety
let add = {x, y} -> x + y

// After: Same code, now with compile-time ability checking
let add = {x, y} -> x + y  // Automatically gets NumAdd constraint
```

### Fixing Constraint Violations

```funk
// Error: Bool doesn't support NumAdd
let bad = true + false

// Fix: Use appropriate types
let good = 1 + 2  // Int supports NumAdd
```

## Future Extensions

The ability constraint system is designed to be extensible:

- **Custom Abilities**: Define your own abilities for user-defined types
- **Ability Hierarchies**: Create ability inheritance relationships
- **Conditional Abilities**: Abilities that depend on type parameters
- **Effect Integration**: Combine abilities with effect constraints

## Conclusion

The ability constraint system makes Funk's polymorphic programming both safer and more expressive. It catches type errors at compile time while preserving the flexibility of generic programming. The system is designed to be intuitive - most constraints are inferred automatically, and error messages guide you toward correct usage.

For more examples and advanced usage patterns, see the test suite in `typechecker/src/test/kotlin/com/funk/language/typechecker/AbilityConstraint*Test.kt`.