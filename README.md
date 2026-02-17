# Mezze Programming Language

<div align="center">

**A modern functional programming language with powerful pattern matching and algebraic effects**

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/your-org/mezze)
[![Tests](https://img.shields.io/badge/tests-577%20passing-brightgreen.svg)](https://github.com/your-org/mezze)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[**🚀 Quick Start**](#quick-start) • [**📚 Documentation**](./docs/) • [**🎯 Examples**](./examples/) • [**🔧 Installation**](#installation)

</div>

---

## ✨ What Makes Mezze Special?

Mezze is designed around **powerful pattern matching** with a clean, modern syntax that makes complex data transformations intuitive and safe.

```mezze
type Result = | Success { data: String }, Error { code: Int, message: String } |

let handleResult = {result: Result} -> match result is
  Success { data } -> "✅ Got: $(data)"
  Error { code, message } if code >= 500 -> "🚨 Server error: $(message)"
  Error { code, message } -> "⚠️  Client error ($(intToString {val = code})): $(message)"
```

### 🔥 Key Features

- **🎯 New Match Syntax**: Clean `match ... is` syntax instead of `match ... {}`
- **⚡ Multiple Patterns**: Group similar cases with `Red | Blue -> "primary"`
- **🛡️ Guard Conditions**: Add `if condition` to patterns for precise control
- **🧠 Logical Operators**: Use `&&` and `||` in guard expressions
- **✅ Exhaustiveness Checking**: Compiler ensures all cases are handled
- **🔧 Algebraic Effects**: Manage side effects functionally
- **📊 Strong Type System**: Variants, records, and type inference
- **🚀 Fast Execution**: Efficient interpreter with comprehensive optimization

## 🚀 Quick Start

### Installation

```bash
# Clone the repository
git clone https://github.com/your-org/mezze.git
cd mezze

# Build the project
gradle build

# Verify installation
mezze-dev --version
```

### Your First Program

Create `hello.mz`:
```mezze
# Welcome to Mezze!
let greeting = "Hello, Mezze!"
let audience = "World"
let message = "$(greeting), $(audience)! 🎉"

message
```

Run it:
```bash
mezze-dev exec hello.mz
# Output: Hello, Mezze!, World! 🎉
```

### Pattern Matching in Action

```mezze
type Weather = | Sunny { temp: Int }, Rainy, Cloudy, Snowy { temp: Int } |

let planActivity = {weather: Weather} -> match weather is
  Sunny { temp } if temp > 25 -> "🏖️ Beach day!"
  Sunny { temp } -> "🚶 Nice walk weather"
  Rainy -> "📚 Perfect reading weather"
  Snowy { temp } if temp < -10 -> "🏠 Stay inside!"
  Snowy { temp } -> "⛄ Snowman time!"
  Cloudy -> "☁️ Maybe bring an umbrella"

let today = Sunny { temp = 28 }
planActivity {weather = today}  # "🏖️ Beach day!"
```

## 📚 Learn Mezze

### 🎓 Learning Path

1. **[📖 Getting Started](./docs/language-guide/getting-started.md)** - Installation and basics
2. **[⚡ Basic Syntax](./docs/language-guide/basic-syntax.md)** - Language fundamentals
3. **[🎯 Pattern Matching](./docs/language-guide/pattern-matching.md)** - Master the key feature
4. **[🎯 Examples](./examples/)** - Hands-on code examples

### 📁 Documentation Structure

- **[📚 Language Guide](./docs/language-guide/)** - Learn Mezze step by step
- **[📖 Reference](./docs/reference/)** - Complete technical reference  
- **[🎯 Tutorials](./docs/tutorials/)** - Project-based learning
- **[💡 Examples](./examples/)** - Organized code samples

### 🎯 Examples by Topic

- **[01-basics](./examples/01-basics/)** - Variables, functions, basic syntax
- **[02-pattern-matching](./examples/02-pattern-matching/)** - Match expressions, guards, multiple patterns
- **[03-functions](./examples/03-functions/)** - Lambdas, composition, higher-order functions
- **[04-types](./examples/04-types/)** - Variants, records, type annotations
- **[05-effects](./examples/05-effects/)** - Algebraic effects and handlers
- **[06-advanced](./examples/06-advanced/)** - Complex patterns and real-world examples

## 🔧 Development

### Project Structure

```
mezze/
├── 📚 docs/                    # Documentation
├── 🎯 examples/                # Code examples  
├── 🏗️  ast/                    # Abstract Syntax Tree
├── 📝 parser/                  # ANTLR-based parser
├── 🔍 typechecker/             # Type checking & inference
├── ⚡ interpreter/              # Runtime execution
├── 🖥️  cli/                    # Command-line interface
└── 📦 stdlib/                  # Standard library
```

### Building from Source

```bash
# Full build
gradle build

# Run tests
gradle test

# Build CLI
gradle :cli:build

# Run specific module tests
gradle :typechecker:test
gradle :interpreter:test
```

### Testing

Mezze has comprehensive test coverage:

- **✅ Parser Tests**: Syntax parsing and AST generation
- **✅ Typechecker Tests**: 325+ tests for type inference and exhaustiveness  
- **✅ Interpreter Tests**: 252+ tests for runtime execution
- **✅ Integration Tests**: End-to-end functionality

```bash
# Run all tests
gradle test

# Run specific test suites
gradle :parser:test
gradle :typechecker:test  
gradle :interpreter:test
```

## 🌟 Language Features

### Pattern Matching Evolution

```mezze
# ❌ Old syntax (deprecated)
match value {
  | Pattern -> result
}

# ✅ New syntax (current)
match value is
  Pattern -> result
```

### Multiple Patterns

```mezze
type Status = | Success, Info, Warning, Error, Critical |

let severity = {status: Status} -> match status is
  Success | Info -> "low"           # Multiple patterns with |
  Warning -> "medium"               
  Error | Critical -> "high"        # Group related cases
```

### Guard Conditions with Logic

```mezze
type User = | User { age: Int, premium: Boolean, verified: Boolean } |

let accessLevel = {user: User} -> match user is
  User { age, premium, verified } if age >= 18 && premium && verified -> "full"
  User { age, premium, verified } if age >= 18 && (premium || verified) -> "standard"  
  User { age, premium, verified } if age >= 13 -> "basic"
  _ -> "restricted"
```

### Exhaustiveness Checking

```mezze
type Color = | Red, Green, Blue |

# ✅ Exhaustive - compiles successfully
let describe = {color: Color} -> match color is
  Red -> "warm"
  Green -> "natural"  
  Blue -> "cool"

# ❌ Non-exhaustive - compile error!
let incomplete = {color: Color} -> match color is
  Red -> "warm"
  # Missing Green and Blue - compiler catches this!
```

## 🤝 Contributing

We welcome contributions! Here's how to get started:

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/amazing-feature`
3. **Make your changes** and add tests
4. **Run the test suite**: `gradle test`
5. **Commit your changes**: `git commit -m 'Add amazing feature'`
6. **Push to your branch**: `git push origin feature/amazing-feature`
7. **Open a Pull Request**

### Development Guidelines

- **Add tests** for new features
- **Update documentation** for user-facing changes
- **Follow existing code style**
- **Write clear commit messages**

## 📊 Project Status

- **🟢 Parser**: Complete with new syntax support
- **🟢 Type System**: Full type inference and checking
- **🟢 Pattern Matching**: Complete with guards and multiple patterns
- **🟢 Interpreter**: Full runtime execution
- **🟢 Effects System**: Algebraic effects implementation
- **🟢 Documentation**: Comprehensive guides and examples
- **🟡 Standard Library**: Growing collection of utilities
- **🔴 Package Manager**: Planned for future release

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **ANTLR** - For the excellent parser generator
- **Kotlin** - For the robust implementation platform  
- **Gradle** - For the build system
- **The functional programming community** - For inspiration and guidance

---

<div align="center">

**Ready to explore functional programming with powerful pattern matching?**

[**🚀 Get Started**](./docs/language-guide/getting-started.md) • [**📚 Read the Docs**](./docs/) • [**🎯 Try Examples**](./examples/)

**Made with ❤️ by the Mezze team**

</div>
