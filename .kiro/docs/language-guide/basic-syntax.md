# Basic Syntax

Learn the fundamental syntax elements of the Funk programming language.

## Comments

Use `#` for single-line comments:

```funk
# This is a comment
let value = 42  # End-of-line comment
```

## Variables and Let Bindings

Variables are declared using `let`:

```funk
# Simple values
let name = "Alice"
let age = 30
let isActive = true

# Expressions
let sum = 10 + 20
let greeting = "Hello, $(name)"
```

## Data Types

### Basic Types

```funk
# Integers
let count = 42
let negative = -10

# Strings
let message = "Hello, World!"
let empty = ""

# Booleans
let isTrue = true
let isFalse = false

# Floats (if supported)
let pi = 3.14159
```

### Records

Records are created with curly braces:

```funk
let person = {
  name = "Alice",
  age = 30,
  email = "alice@example.com"
}

# Access fields with dot notation
let personName = person.name
let personAge = person.age
```

### Variant Types

Define custom types with variants:

```funk
type Color = | Red, Green, Blue |
type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int } |

let myColor = Red
let myShape = Circle { radius = 5 }
```

## Functions

Functions are defined using lambda syntax:

```funk
# Simple function
let add = {x, y} -> x + y

# Function with type annotation
let greet: {name: String} -> String = {name} -> "Hello, $(name)"

# Function calls use record syntax
let result = add {x = 5, y = 3}
let message = greet {name = "Alice"}
```

## Operators

### Arithmetic Operators

```funk
let a = 10
let b = 3

let sum = a + b        # Addition: 13
let diff = a - b       # Subtraction: 7
let product = a * b    # Multiplication: 30
let quotient = a / b   # Division: 3
```

### Comparison Operators

```funk
let isEqual = a == b      # Equality: false
let isNotEqual = a != b   # Inequality: true
let isGreater = a > b     # Greater than: true
let isLess = a < b        # Less than: false
let isGreaterEq = a >= b  # Greater or equal: true
let isLessEq = a <= b     # Less or equal: false
```

### Logical Operators

```funk
let both = true && false    # Logical AND: false
let either = true || false  # Logical OR: true
```

### String Interpolation

```funk
let firstName = "John"
let lastName = "Doe"
let fullName = "$(firstName) $(lastName)"  # "John Doe"
```

## Control Flow

### If Expressions

```funk
let max = {a, b} -> if a > b then a else b

let message = if age >= 18 
              then "Adult" 
              else "Minor"
```

### Match Expressions

```funk
type Status = | Success, Warning, Error |

let handleStatus = {status: Status} -> match status is
  Success -> "All good!"
  Warning -> "Be careful"
  Error -> "Something went wrong"
```

## Type Annotations

While type inference usually works, you can add explicit types:

```funk
# Variable type annotation
let count: Int = 42
let message: String = "Hello"

# Function type annotation
let add: {x: Int, y: Int} -> Int = {x, y} -> x + y

# Complex type annotation
let process: {data: | Success { value: String } | Error { message: String } |} -> String = 
  {data} -> match data is
    Success { value } -> "Got: $(value)"
    Error { message } -> "Error: $(message)"
```

## Program Structure

A Funk program consists of:

1. Type definitions (optional)
2. Let bindings for variables and functions
3. A final expression that becomes the program's result

```funk
# Type definitions
type Color = | Red, Green, Blue |

# Variable bindings
let favoriteColor = Red
let colorName = "Red"

# Function definitions
let describeColor = {color: Color} -> match color is
  Red -> "Passionate"
  Green -> "Natural"
  Blue -> "Calm"

# Final expression (program result)
describeColor {color = favoriteColor}
```

## Best Practices

### Naming Conventions

- Use `camelCase` for variables and functions: `firstName`, `calculateArea`
- Use `PascalCase` for types and constructors: `Color`, `Success`
- Use descriptive names: `userAge` instead of `a`

### Code Organization

```funk
# Group related type definitions together
type User = | User { name: String, age: Int } |
type Status = | Active, Inactive |

# Define helper functions before main logic
let formatAge = {age} -> "$(intToString {val = age}) years old"
let isAdult = {age} -> age >= 18

# Main program logic
let user = User { name = "Alice", age = 25 }
let description = match user is
  User { name, age } -> "$(name) is $(formatAge {age = age})"

description
```

### Error Handling

Use Result types for operations that can fail:

```funk
type Result = | Success { value: String }, Error { message: String } |

let safeDivide = {a, b} -> 
  if b == 0 
  then Error { message = "Division by zero" }
  else Success { value = intToString {val = a / b} }
```

This covers the essential syntax elements you need to write Funk programs. Next, explore [Pattern Matching](./pattern-matching.md) to learn about Funk's most powerful feature!
