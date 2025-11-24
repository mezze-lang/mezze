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
- **Ability Method Calls**: `Show.show`, `NumOps.add` (Ability.method syntax)
- **Module Paths**: `math::geometry::area`
- **Operators**: `|>`, `->`, `==`, `&&`, `||`, `...`, `@`, etc.
- **String Interpolation**: `"Hello, $(name)!"` syntax with nested expression highlighting
- **Comments**: Line comments with `#`
- **Literals**: Strings, integers, floats, fractions (`1//2`), booleans, arrays, records
- **Record Destructuring**: `let { x, y } = point` and `let { x, ... } = record`

### Language Features

- **Smart Indentation**: Automatic indentation for blocks, do expressions, match cases, and lambda bodies
- **Auto-closing Pairs**: Automatic closing of brackets, braces, parentheses, quotes, and variant delimiters `|`
- **Bracket Matching**: Highlighting of matching brackets and variant delimiters
- **Comment Continuation**: Auto-continuation of line comments when pressing Enter
- **Word Selection**: Smart word selection that handles type variables and qualified identifiers

### Code Snippets

Over 35 built-in snippets for common Funk patterns:

- **Declarations**: `let`, `lett`, `publet`, `letd`, `letdp` (destructuring)
- **Types**: `type`, `typeg`, `record`, `recordo`, `variant`, `variantd`, `varianto`
- **Functions**: `lambda`, `lambdap`, `lambdat`, `funeffect`, `funability`
- **Effects & Abilities**: `effect`, `ability`, `ability2`, `impl`, `implability`
- **Control Flow**: `match`, `matchg`, `matchm`, `if`, `do`, `perform`
- **Data Structures**: `rec`, `recspread`, `arr`, `frac`
- **Modules**: `use`, `useas`, `usesel`, `pubuse`
- **Calls**: `abcall` (ability method), `modcall` (module function)
- **Utilities**: `pipe`, `section`, `interp` (string interpolation)

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
code --install-extension funk-language-0.3.1.vsix
```

## Supported File Extensions

- `.funk` - Funk source files

## Known Issues

Please report any issues on the [GitHub repository](https://github.com/funk-lang/funk/issues).

## Release Notes

### 0.3.1

- **Extension Icon**: Purple "F" logo displayed in VSCode marketplace and extension panel
- **File Icons**: `.funk` files now display with a custom icon in the file explorer
- **Fixed `perform` Highlighting**: `perform Handler in` expressions now highlight correctly
- **Fixed Record Literal Highlighting**: Record literals like `{x = 10, y = 20}` now have proper field highlighting
- Updated package version

### 0.3.0

- **String Interpolation Support**: Full syntax highlighting for `$()` expressions inside strings
  - Nested strings inside interpolation: `"outer: $("inner $(x)")"`
  - Escape sequence: `\$()` to prevent interpolation
  - Expression highlighting within interpolation blocks
- **Let Destructuring**: Support for record destructuring patterns
  - `let { x, y } = point`
  - `let { x, ... } = record` (partial matching)
- **Ability Method Calls**: Highlighting for `Ability.method` syntax (e.g., `Show.show`, `NumOps.add`)
- **Fraction Literals**: Support for `1//2` fraction syntax
- **15+ New Snippets**: Added snippets for destructuring, open types, ability calls, module calls, and more
- Removed deprecated `++` operator (replaced by string interpolation)

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
