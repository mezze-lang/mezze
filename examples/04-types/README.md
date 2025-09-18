# Types - Understanding Funk's Type System

Master Funk's powerful type system including variants, records, and type annotations.

## Examples in this folder

### [variants.funk](./variants.funk)
Algebraic data types and variant definitions.
- Defining variant types
- Constructors with and without data
- Pattern matching on variants

### [records.funk](./records.funk)
Record types and field access.
- Record literals
- Field access with dot notation
- Record updates and manipulation

### [type-annotations.funk](./type-annotations.funk)
Explicit type annotations and signatures.
- Function type signatures
- Variable type annotations
- Type-driven development

### [complex-types.funk](./complex-types.funk)
Advanced type compositions and patterns.
- Nested types
- Recursive types
- Complex data structures

## Key Concepts Covered

- **Variant Types**: `type Color = | Red, Green, Blue |`
- **Record Types**: `{ name: String, age: Int }`
- **Type Annotations**: `let f: {x: Int} -> String = ...`
- **Constructor Patterns**: `Red`, `Person { name, age }`
- **Type Inference**: Automatic type deduction

## Type Syntax Reference

```funk
# Variant type definition
type Result = | Success { value: String }, Error { message: String } |

# Record type (inferred from usage)
let person = { name = "Alice", age = 30 }

# Function with type annotation
let process: {input: Result} -> String = {input} -> match input is
  Success { value } -> "Got: " ++ value
  Error { message } -> "Error: " ++ message
```

## Running the Examples

```bash
funk-dev exec variants.funk
funk-dev exec records.funk
```

## Next Steps

- **[Effects](../05-effects/)** - Learn about effect types
- **[Advanced](../06-advanced/)** - Complex type patterns
