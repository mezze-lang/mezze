# Funk Examples

This directory contains comprehensive examples demonstrating all major features of the Funk programming language. Each example is self-contained and can be run independently.

## Running Examples

All examples have a `main` function with type `{} -> {}` that can be executed:

```bash
# Type check an example
funk check examples/01_primitives.funk

# Execute an example
funk exec examples/01_primitives.funk
```

## Examples Overview

### Basic Types and Structures

**01_primitives.funk** - Primitive types
- Integers, floats, strings, and booleans
- Basic operations and conversions
- Type safety demonstrations

**02_records_closed.funk** - Closed records
- Fixed record structures
- Field access and updates
- Nested records

**03_records_open.funk** - Open records
- Polymorphic record functions
- Row polymorphism
- Structural subtyping

**04_variants_closed.funk** - Closed variants
- Algebraic data types
- Pattern matching on variants
- Result and Option types

**05_variants_open.funk** - Open variants
- Extensible variants
- Wildcard patterns
- Partial matching

### Functions

**06_functions.funk** - Functions and higher-order functions
- Simple functions
- Currying and partial application
- Function composition
- Higher-order functions
- Recursion

**07_pattern_matching.funk** - Pattern matching
- Matching on variants
- Matching on records
- Nested patterns
- Guards and conditionals

### Advanced Features

**08_abilities.funk** - Abilities (type classes)
- Defining abilities
- Implementing abilities for types
- Generic functions with ability constraints
- Multiple operations per ability

**09_effects_log.funk** - Log effect
- Console logging
- Different log levels (trace, debug, info, warn, error)
- Effect handlers

**10_effects_filesystem.funk** - FileSystem effect
- Reading and writing files
- File existence checks
- Memory-based filesystem for testing

**11_effects_random.funk** - Random effect
- Random number generation
- Seeded randomness for reproducibility
- Random boolean values
- Random floats

**12_collections_array.funk** - Array operations
- Map, filter, fold
- Array length and indexing
- Functional array transformations

## Key Concepts Demonstrated

### Effect System
All examples use the algebraic effects system:
- **Log effect** - Console output with `perform Console`
- **FileSystem effect** - File operations with `perform MemoryFS` or `perform LocalDisk`
- **Random effect** - Random generation with `perform SeededRandom` or `perform SystemRandom`

### Type System Features
- **Closed types** - Fixed structure (records/variants)
- **Open types** - Extensible structure with `...`
- **Row polymorphism** - Functions accepting records with extra fields
- **Type inference** - Automatic type deduction
- **Generic types** - Parametric polymorphism with type variables

### Functional Programming
- **Immutability** - All values are immutable
- **Pure functions** - Functions without side effects (unless using effects)
- **Higher-order functions** - Functions as first-class values
- **Pattern matching** - Destructuring and case analysis
- **Recursion** - Primary iteration mechanism

## Example Template

Every example follows this structure:

```funk
# Import required modules
use std::effects::log::{Log, Console}

# Define functions and values
let example_function = { x: Int } -> x + 1

# Main function for execution (required for exec)
let main = {} ->
    perform Console in do
        Log.println { msg = "Output here" }
```

## Next Steps

After exploring these examples:
1. Check out the [stdlib documentation](../stdlib/README.md) for available modules
2. Read the [language guide](../docs/language-guide/) for detailed explanations
3. Try modifying examples to experiment with features
4. Write your own Funk programs!

## Testing Examples

All examples can be type-checked and executed:

```bash
# Check all examples
for f in examples/*.funk; do
    echo "Checking $f..."
    funk check "$f" || exit 1
done

# Run all examples
for f in examples/*.funk; do
    echo "Running $f..."
    funk exec "$f"
    echo "---"
done
```
