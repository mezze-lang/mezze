# Mezze Language Support for VS Code

Full language support for the Mezze functional programming language with algebraic effects.

## Features

### Language Server Integration

This extension includes a language server client that provides:

- **Diagnostics**: Real-time type errors and parse errors as you type
- **Hover Information**: See types and documentation on hover
- **Go to Definition**: Navigate to symbol definitions
- **Find References**: Find all usages of a symbol
- **Code Completion**: Context-aware completions
- **Document Symbols**: Outline view of your file structure

### Syntax Highlighting

Rich syntax highlighting for all Mezze language features:

- **Keywords**: `let`, `type`, `effect`, `ability`, `impl`, `match`, `is`, `if`, `then`, `else`, `do`, `perform`, `with`, `where`, `for`, `in`, `use`, `pub`, `newtype`, `having`
- **Type Variables**: `'a`, `'b`, `'t`, etc.
- **Types and Constructors**: Capitalized identifiers like `Option`, `Result`, `Some`, `None`
- **Effect Rows**: `<State, IO, Network>` with performed marker `<*>` or `<Log, *>`
- **Ability Constraints**: `with NumAdd 'a, Eq 'b`
- **Ability Method Calls**: `value.show {}`, `x.add { r = y }` (method-call syntax)
- **Module Paths**: `math::geometry::area`
- **Operators**: `|>`, `->`, `==`, `&&`, `||`, `...`, `@`, etc.
- **String Interpolation**: `"Hello, $(name)!"` syntax with nested expression highlighting
- **Comments**: Line comments with `#`
- **Literals**: Strings, integers, floats, fractions (`1//2`), natural numbers (`42n`), booleans (`True`/`False`), arrays, records

### Language Features

- **Smart Indentation**: Automatic indentation for blocks, do expressions, match cases, and lambda bodies
- **Auto-closing Pairs**: Automatic closing of brackets, braces, parentheses, quotes, and variant delimiters `|`
- **Bracket Matching**: Highlighting of matching brackets and variant delimiters
- **Comment Continuation**: Auto-continuation of line comments when pressing Enter
- **Word Selection**: Smart word selection that handles type variables and qualified identifiers

### Code Snippets

Over 35 built-in snippets for common Mezze patterns:

- **Declarations**: `let`, `lett`, `publet`, `letd`, `letdp` (destructuring)
- **Types**: `type`, `typeg`, `record`, `recordo`, `variant`, `variantd`, `varianto`, `newtype`
- **Functions**: `lambda`, `lambdap`, `lambdat`, `funeffect`, `funability`
- **Effects & Abilities**: `effect`, `ability`, `ability2`, `impl`, `implability`
- **Control Flow**: `match`, `matchg`, `matchm`, `if`, `do`, `perform`
- **Data Structures**: `rec`, `recspread`, `arr`, `frac`
- **Modules**: `use`, `useas`, `usesel`, `pubuse`
- **Calls**: `abcall` (ability method), `modcall` (module function)
- **Utilities**: `pipe`, `section`, `interp` (string interpolation)

## Requirements

- **mez CLI**: The Mezze compiler must be installed and available in your PATH, or configure `mezze.executablePath` in settings.

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `mezze.executablePath` | `mez` | Path to the mez executable. If not absolute, resolved relative to workspace root. |
| `mezze.trace.server` | `off` | Traces communication with the language server. Options: `off`, `messages`, `verbose` |

## Commands

- **Mezze: Restart Language Server** - Restart the language server (useful if you update the mez compiler)

## Mezze Language Overview

Mezze is a pure functional programming language with:

- **Pure Functional Paradigm**: Immutable data structures and referential transparency
- **Algebraic Effects System**: First-class effects for managing side effects declaratively
- **Strong Static Type System**: Hindley-Milner type inference with effect tracking
- **Pattern Matching**: Exhaustive pattern matching with guards
- **Algebraic Data Types**: Records (product types) and variants (sum types)
- **Named Parameter Application**: Functions called with explicit parameter names

## Syntax Examples

### Let Binding
```mezze
let add = {x, y} -> x + y
pub let result = add {x = 5, y = 3}
```

### Type Declaration
```mezze
type Option 'a = | Some {value: 'a}, None |
type Point = {x: Int, y: Int}
newtype Email = Email String
```

### Effect Declaration
```mezze
effect State having 'state where {
    get: {} -> 'state <State>,
    put: {value: 'state} -> {} <State>
}
```

### Ability Declaration
```mezze
ability NumAdd 'a where {
    add: {left: 'a, right: 'a} -> 'a
}
```

### Pattern Matching
```mezze
match value is
    Some {value = x} -> x
    None -> 0
```

### Function with Effects
```mezze
let computation: {x: Int} -> Int <State> = {x} ->
    do
        let current = get {}
        put {value = current + x}
        current + x
```

## Installation

1. Open VS Code
2. Press `Ctrl+P` / `Cmd+P` to open the Quick Open dialog
3. Type `ext install mezze-language` and press Enter

Or install from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=mezze-lang.mezze-language).

## Development

To build and install the extension locally:

```bash
# Navigate to extension directory
cd tooling/vscode-extension/extension

# Install dependencies
npm install

# Compile TypeScript
npm run compile

# Package the extension
npm run package

# Install locally
code --install-extension mezze-language-0.4.0.vsix
```

## Supported File Extensions

- `.mz` - Mezze source files

## Troubleshooting

### Language server not starting

1. Verify `mez` is installed and in PATH: `mez --version`
2. Check Output panel (View > Output) and select "Mezze Language Server"
3. Try setting `mezze.executablePath` to the full path of the `mez` executable

### Syntax highlighting issues

If certain constructs aren't highlighted correctly, please report the issue with a code sample.

## Known Issues

Please report any issues on the [GitHub repository](https://github.com/mezze-lang/mezze/issues).

## Release Notes

### 0.4.0

- **Language Server Integration**: Full LSP support for diagnostics, hover, go-to-definition, find references, and completion
- **New Keywords**: Added `newtype` and `having` keyword highlighting
- **Performed Marker**: Support for `<*>` performed marker in effect rows
- **Natural Numbers**: Highlighting for natural number literals (`42n`)
- **Boolean Literals**: Fixed to use `True`/`False` (capitalized)
- **Configuration**: New settings for executable path and server tracing
- **Commands**: Added "Restart Language Server" command

### 0.3.x

- String interpolation support
- Let destructuring patterns
- Ability method call highlighting
- Fraction literals
- Many new snippets

### 0.2.0

- Comprehensive syntax highlighting for all language features
- Module system support
- Effect rows and ability constraints
- Smart indentation and bracket matching
- 25+ code snippets

### 0.1.0

- Initial release with basic syntax highlighting

## Contributing

Contributions are welcome! Please see the [contributing guidelines](https://github.com/mezze-lang/mezze/blob/main/CONTRIBUTING.md).

## License

MIT License - see [LICENSE](https://github.com/mezze-lang/mezze/blob/main/LICENSE) for details.
