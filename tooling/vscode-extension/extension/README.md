# Funk Language Support for VS Code

Comprehensive syntax highlighting and language support for the Funk functional programming language.

## Features

### Syntax Highlighting

This extension provides rich syntax highlighting for all Funk language features:

- **Keywords**: `let`, `type`, `effect`, `ability`, `impl`, `match`, `is`, `if`, `then`, `else`, `do`, `perform`, `with`, `where`, `for`, `in`, `use`, `pub`, `as`
- **Type Variables**: `'a`, `'b`, `'t`, etc.
- **Types and Constructors**: Capitalized identifiers like `Option`, `Result`, `Some`, `None`
- **Effect Rows**: `<State, IO, Network>`
- **Ability Constraints**: `with NumAdd 'a, Eq 'b`
- **Module Paths**: `math::geometry::area`
- **Operators**: `|>`, `->`, `==`, `&&`, `||`, `...`, `@`, etc.
- **String Interpolation**: `"Hello, $(name)!"` syntax
- **Comments**: Line comments with `#`
- **Literals**: Strings, numbers, booleans, arrays, records

### Language Features

- **Smart Indentation**: Automatic indentation for blocks, do expressions, match cases, and lambda bodies
- **Auto-closing Pairs**: Automatic closing of brackets, braces, parentheses, quotes, and variant delimiters `|`
- **Bracket Matching**: Highlighting of matching brackets and variant delimiters
- **Comment Continuation**: Auto-continuation of line comments when pressing Enter
- **Word Selection**: Smart word selection that handles type variables and qualified identifiers

### Code Snippets

Over 25 built-in snippets for common Funk patterns:

- **Declarations**: `let`, `publet`, `type`, `effect`, `ability`, `impl`
- **Functions**: `lambda`, `lambdap`, `lambdat`, `funeffect`, `funability`
- **Control Flow**: `match`, `matchg`, `if`, `do`, `perform`
- **Data Structures**: `variant`, `record`, `rec`, `recspread`, `arr`
- **Modules**: `use`, `useas`, `pubuse`
- **Utilities**: `pipe`, `section`

## Funk Language Overview

Funk is a pure functional programming language with:

- **Pure Functional Paradigm**: Immutable data structures and referential transparency
- **Algebraic Effects System**: First-class effects for managing side effects declaratively
- **Strong Static Type System**: Hindley-Milner type inference with effect tracking
- **Pattern Matching**: Exhaustive pattern matching with guards
- **Algebraic Data Types**: Records (product types) and variants (sum types)
- **Named Parameter Application**: Functions called with explicit parameter names

## Syntax Examples

### Let Binding
```funk
let add = {x, y} -> x + y
pub let result = add {x = 5, y = 3}
```

### Type Declaration
```funk
type Option 'a = | Some {value: 'a}, None |
type Point = {x: Int, y: Int}
```

### Effect Declaration
```funk
effect State {
  get: {} -> Int <>
  put: {value: Int} -> {} <>
}
```

### Ability Declaration
```funk
ability NumAdd 'a where {
  add: {left: 'a, right: 'a} -> 'a
}
```

### Pattern Matching
```funk
match value is
  Some {value = x} -> x
  None -> 0
```

### Function with Effects
```funk
let computation: {x: Int} -> Int <State> = {x} ->
  do
    let current = get {}
    put {value = current + x}
    current + x
```

## Installation

1. Open VS Code
2. Press `Ctrl+P` / `Cmd+P` to open the Quick Open dialog
3. Type `ext install funk-language` and press Enter

Or install from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=funk-lang.funk-language).

## Development

To contribute to this extension:

```bash
# Clone the repository
git clone https://github.com/funk-lang/funk.git
cd funk/tooling/vscode-extension

# Install dependencies (if any)
npm install

# Package the extension
npx vsce package

# Install locally
code --install-extension funk-language-0.2.0.vsix
```

## Supported File Extensions

- `.funk` - Funk source files

## Known Issues

Please report any issues on the [GitHub repository](https://github.com/funk-lang/funk/issues).

## Release Notes

### 0.2.0

- Comprehensive syntax highlighting for all language features
- Support for:
  - Module system (`use`, `pub`, qualified identifiers)
  - Effect rows (`<Effect1, Effect2>`)
  - Ability constraints (`with Ability Type`)
  - Pattern matching with guards
  - Lambda expressions (typed and untyped)
  - Do expressions and perform handlers
  - Record and variant types
  - All operators including pipe (`|>`), spread (`...`), and placeholder (`@`)
- Enhanced language configuration:
  - Smart indentation for functional constructs
  - Auto-closing variant delimiters
  - Improved word pattern for type variables
- 25+ code snippets for common patterns

### 0.1.0

- Initial release
- Basic syntax highlighting

## Contributing

Contributions are welcome! Please see the [contributing guidelines](https://github.com/funk-lang/funk/blob/main/CONTRIBUTING.md).

## License

MIT License - see [LICENSE](https://github.com/funk-lang/funk/blob/main/LICENSE) for details.

---

**Enjoy coding in Funk!** 🎵
