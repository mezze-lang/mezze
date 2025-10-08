# Funk Programming Language Documentation

Welcome to the Funk programming language documentation! Funk is a modern functional programming language with powerful pattern matching, algebraic effects, and a clean, expressive syntax.

## 📚 Documentation Structure

### [🚀 Language Guide](./language-guide/)
Learn Funk from the ground up with comprehensive guides covering all language features:

- **[Getting Started](./language-guide/getting-started.md)** - Your first steps with Funk
- **[Basic Syntax](./language-guide/basic-syntax.md)** - Variables, functions, and expressions
- **[Pattern Matching](./language-guide/pattern-matching.md)** - Powerful match expressions with guards
- **[Type System](./language-guide/type-system.md)** - Types, variants, and records
- **[Functions](./language-guide/functions.md)** - Lambdas, partial application, and composition
- **[Effects System](./language-guide/effects.md)** - Algebraic effects and handlers

### [📖 Reference](./reference/)
Detailed technical reference for language features:

- **[Syntax Reference](./reference/syntax.md)** - Complete syntax specification
- **[Built-in Types](./reference/types.md)** - All built-in types and operations
- **[Standard Library](./reference/stdlib.md)** - Standard library functions
- **[Compiler Options](./reference/compiler.md)** - Command-line tools and options

### [🎯 Tutorials](./tutorials/)
Step-by-step tutorials for specific use cases:

- **[Building a Calculator](./tutorials/calculator.md)** - Pattern matching and arithmetic
- **[File Processing](./tutorials/file-processing.md)** - Working with effects
- **[Web API](./tutorials/web-api.md)** - Building web services with Funk

## 🔗 Quick Links

- [Examples Repository](../examples/) - Hands-on code examples
- [Language Specification](./reference/syntax.md) - Complete language specification
- [Getting Help](#getting-help) - Community and support resources

## 🌟 Key Features

### Pattern Matching
```funk
type Color = | Red, Blue, Green |

let describe = {color: Color} -> match color is
  Red -> "The color of passion"
  Blue -> "The color of the sky"
  Green -> "The color of nature"
```

### Guard Conditions
```funk
type Temperature = | Hot { degrees: Int } |

let classify = {temp: Temperature} -> match temp is
  Hot { degrees } if degrees > 100 -> "Boiling!"
  Hot { degrees } if degrees > 80 -> "Very hot"
  Hot { degrees } -> "Warm"
```

### Multiple Patterns
```funk
type Status = | Success, Warning, Error |

let isOk = {status: Status} -> match status is
  Success | Warning -> true
  Error -> false
```

### Algebraic Effects
```funk
effect Console {
  print: {message: String} -> Unit <>
}

let greet = {name: String} -> do
  Console.print {message = "Hello, " ++ name ++ "!"}
```

## Getting Help

- **Examples**: Check out the [examples](../examples/) folder for practical code samples
- **Issues**: Report bugs and request features on GitHub
- **Community**: Join our community discussions

---

*Happy coding with Funk! 🎉*
