# Getting Started with Funk

Welcome to Funk! This guide will help you get up and running with the Funk programming language.

## Installation

### Prerequisites
- Java 21 or higher
- Gradle (for building from source)

### Building from Source
```bash
git clone https://github.com/your-org/funk.git
cd funk
gradle build
```

### Running Funk Programs
```bash
# Check syntax and types
funk-dev check my_program.funk

# Execute a program
funk-dev exec my_program.funk

# Interactive mode (if available)
funk-dev repl
```

## Your First Funk Program

Create a file called `hello.funk`:

```funk
# This is a comment
let greeting = "Hello, World!"
let main = greeting
```

Run it:
```bash
funk-dev exec hello.funk
```

## Basic Concepts

### Variables and Let Bindings
```funk
# Simple values
let name = "Alice"
let age = 30
let isActive = true

# Expressions
let sum = 10 + 20
let message = "Hello, " ++ name
```

### Functions
```funk
# Simple function
let add = {x, y} -> x + y

# Using the function
let result = add {x = 5, y = 3}
```

### Types
```funk
# Type annotations (optional but helpful)
let greet: {name: String} -> String = {name} -> "Hello, " ++ name

# Custom types
type Color = | Red, Green, Blue |
let favoriteColor = Red
```

### Pattern Matching
```funk
type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int } |

let area = {shape: Shape} -> match shape is
  Circle { radius } -> 3 * radius * radius  # Simplified π
  Rectangle { width, height } -> width * height
```

## Next Steps

- **[Basic Syntax](./basic-syntax.md)** - Learn about Funk's syntax in detail
- **[Pattern Matching](./pattern-matching.md)** - Master Funk's powerful pattern matching
- **[Type System](./type-system.md)** - Understand Funk's type system
- **[Examples](../../examples/)** - Explore practical examples

## Common Patterns

### Error Handling
```funk
type Result = | Success { value: String }, Error { message: String } |

let processResult = {result: Result} -> match result is
  Success { value } -> "Got: " ++ value
  Error { message } -> "Error: " ++ message
```

### Working with Lists (when available)
```funk
# Lists are coming soon!
# let numbers = [1, 2, 3, 4, 5]
```

### Configuration Records
```funk
type Config = { 
  host: String, 
  port: Int, 
  debug: Boolean 
}

let defaultConfig = {
  host = "localhost",
  port = 8080,
  debug = false
}
```

Happy coding! 🚀
