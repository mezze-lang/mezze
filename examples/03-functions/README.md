# Functions - Functional Programming in Funk

Explore Funk's functional programming capabilities with lambdas, composition, and higher-order functions.

## Examples in this folder

### [simple-function.funk](./simple-function.funk)
Basic function definition and usage.
- Lambda syntax `{param} -> body`
- Function calls with record syntax
- Return values

### [function-composition.funk](./function-composition.funk)
Composing functions for powerful abstractions.
- Combining simple functions
- Building complex operations
- Reusable function components

### [higher-order.funk](./higher-order.funk)
Functions that work with other functions.
- Functions as parameters
- Functions as return values
- Functional abstractions

### [partial-application.funk](./partial-application.funk)
Partial application and currying techniques.
- Creating specialized functions
- Function configuration
- Practical applications

## Key Concepts Covered

- **Lambda Expressions**: `{x, y} -> x + y`
- **Function Calls**: `add {x = 5, y = 3}`
- **Higher-Order Functions**: Functions that take/return functions
- **Composition**: Building complex functions from simple ones
- **Partial Application**: Creating specialized versions of functions

## Function Syntax Reference

```funk
# Basic function
let add = {x, y} -> x + y

# Function with type annotation
let greet: {name: String} -> String = {name} -> "Hello, " ++ name

# Function call
let result = add {x = 5, y = 3}

# Higher-order function
let applyTwice = {f, x} -> f {arg = f {arg = x}}
```

## Running the Examples

```bash
funk-dev exec simple-function.funk
funk-dev exec function-composition.funk
```

## Next Steps

- **[Types](../04-types/)** - Learn about function types and signatures
- **[Effects](../05-effects/)** - Combine functions with effects
