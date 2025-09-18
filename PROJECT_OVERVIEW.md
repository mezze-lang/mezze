# Funk Programming Language - Project Overview

Welcome to the Funk programming language! This document provides an overview of the project structure and key features.

## 🎯 What is Funk?

Funk is a modern functional programming language featuring:

- **🔥 Powerful Pattern Matching** with the new `match ... is` syntax
- **🚀 Multiple Patterns** using `|` operator: `Red | Blue -> "primary"`
- **⚡ Guard Conditions** with `if`: `Hot { temp } if temp > 80 -> "very hot"`
- **🧠 Logical Operators** in guards: `&&` and `||`
- **🔧 Algebraic Effects** for managing side effects
- **📊 Strong Type System** with variants and records
- **✅ Exhaustiveness Checking** for pattern completeness

## 📁 Project Structure

```
funk/
├── 📚 docs/                    # Complete documentation
│   ├── language-guide/         # Learning materials
│   ├── reference/              # Technical reference
│   └── tutorials/              # Step-by-step guides
│
├── 🎯 examples/                # Organized examples
│   ├── 01-basics/              # Language fundamentals
│   ├── 02-pattern-matching/    # Pattern matching features
│   ├── 03-functions/           # Functional programming
│   ├── 04-types/               # Type system
│   ├── 05-effects/             # Algebraic effects
│   └── 06-advanced/            # Complex patterns
│
├── 🏗️ Core Implementation/
│   ├── ast/                    # Abstract Syntax Tree
│   ├── parser/                 # ANTLR-based parser
│   ├── typechecker/            # Type checking & inference
│   ├── interpreter/            # Runtime execution
│   └── cli/                    # Command-line interface
│
└── 🧪 Testing/
    ├── Comprehensive test suites
    ├── 325+ typechecker tests passing
    └── 252+ interpreter tests passing
```

## 🚀 Quick Start

### Installation
```bash
git clone <repository-url>
cd funk
gradle build
```

### Your First Program
Create `hello.funk`:
```funk
let message = "Hello, Funk!"
message
```

Run it:
```bash
funk-dev exec hello.funk
```

### Pattern Matching Example
```funk
type Color = | Red, Green, Blue |

let describe = {color: Color} -> match color is
  Red | Blue -> "Primary color"
  Green -> "Secondary color"

describe {color = Red}  # "Primary color"
```

### Guard Conditions Example
```funk
type Grade = | Score { points: Int } |

let letterGrade = {grade: Grade} -> match grade is
  Score { points } if points >= 90 && points <= 100 -> "A"
  Score { points } if points >= 80 -> "B"
  _ -> "C or below"
```

## 📚 Learning Path

1. **[Getting Started](./docs/language-guide/getting-started.md)** - Installation and first steps
2. **[Basic Syntax](./docs/language-guide/basic-syntax.md)** - Language fundamentals  
3. **[Pattern Matching](./docs/language-guide/pattern-matching.md)** - Master the key feature
4. **[Examples](./examples/)** - Hands-on code examples
5. **[Reference](./docs/reference/)** - Complete language reference

## 🌟 Key Features Showcase

### New Match Syntax
```funk
# Old syntax (deprecated)
match value { | Pattern -> result }

# New syntax (current)
match value is
  Pattern -> result
```

### Multiple Patterns
```funk
type Status = | Success, Info, Warning, Error |

let isOk = {status: Status} -> match status is
  Success | Info -> true      # Multiple patterns with |
  Warning | Error -> false
```

### Guard Conditions
```funk
type User = | User { age: Int, premium: Boolean } |

let access = {user: User} -> match user is
  User { age, premium } if age >= 18 && premium -> "Full access"
  User { age, premium } if age >= 18 -> "Basic access"  
  _ -> "Limited access"
```

### Exhaustiveness Checking
```funk
type Color = | Red, Green, Blue |

# ✅ Exhaustive - compiles successfully
let primary = {color: Color} -> match color is
  Red -> true
  Green -> false
  Blue -> true

# ❌ Non-exhaustive - compile error!
let incomplete = {color: Color} -> match color is
  Red -> true
  # Missing Green and Blue cases!
```

## 🧪 Testing Status

- **✅ Parser**: Complete implementation with new syntax
- **✅ Typechecker**: 325+ tests passing with exhaustiveness checking
- **✅ Interpreter**: 252+ tests passing with runtime execution
- **✅ Integration**: All components working together seamlessly

## 🎯 Recent Achievements

- ✅ Implemented new `match ... is` syntax
- ✅ Added multiple patterns with `|` operator
- ✅ Added guard conditions with `if`
- ✅ Added logical operators `&&` and `||`
- ✅ Complete exhaustiveness checking
- ✅ Comprehensive test suite
- ✅ Full documentation and examples

## 🔗 Quick Links

- **[Documentation](./docs/)** - Complete language documentation
- **[Examples](./examples/)** - Runnable code examples
- **[Getting Started](./docs/language-guide/getting-started.md)** - Your first Funk program
- **[Pattern Matching Guide](./docs/language-guide/pattern-matching.md)** - Master the key feature

## 🎉 Next Steps

1. **Explore Examples**: Start with [`examples/01-basics/`](./examples/01-basics/)
2. **Try Pattern Matching**: Check out [`examples/02-pattern-matching/`](./examples/02-pattern-matching/)
3. **Build Something**: Use the advanced examples as inspiration
4. **Read Documentation**: Deep dive into the language guide

Welcome to functional programming with Funk! 🚀
