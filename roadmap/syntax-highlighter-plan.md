# Funk Language Syntax Highlighter & Tree-sitter Grammar Plan

## Overview

This document outlines the plan for creating comprehensive syntax highlighting support for the Funk programming language across multiple editors, starting with VSCode and expanding to other editors via Tree-sitter.

## Project Structure

```
funk/
├── syntax-highlighting/
│   ├── vscode-extension/
│   │   ├── package.json
│   │   ├── syntaxes/
│   │   │   └── funk.tmLanguage.json
│   │   ├── language-configuration.json
│   │   ├── themes/
│   │   │   └── funk-dark.json
│   │   └── README.md
│   ├── tree-sitter-funk/
│   │   ├── grammar.js
│   │   ├── src/
│   │   │   ├── parser.c
│   │   │   └── tree_sitter/
│   │   ├── test/
│   │   │   └── corpus/
│   │   ├── package.json
│   │   └── binding.gyp
│   └── README.md
```

## Phase 1: VSCode Extension with TextMate Grammar

### 1.1 Language Features to Highlight

Based on the Funk.g4 grammar and example files, we need to support:

#### Keywords
- **Control Flow**: `if`, `then`, `else`, `match`, `do`
- **Declarations**: `let`, `type`, `effect`, `impl`, `ability`, `for`, `where`
- **Effects**: `perform`
- **Literals**: `true`, `false`

#### Operators
- **Arithmetic**: `+`, `-`, `*`, `/`, `++` (concat)
- **Comparison**: `>`, `<`, `>=`, `<=`, `==`, `!=`
- **Assignment**: `=`
- **Functional**: `->` (arrow), `|>` (pipe)
- **Special**: `@` (placeholder), `_` (wildcard), `...` (ellipsis)

#### Delimiters
- **Brackets**: `()`, `{}`, `[]`
- **Separators**: `,`, `:`, `.`, `|`

#### Literals
- **Numbers**: Integers (`42`) and Floats (`3.14`)
- **Strings**: Double-quoted strings with escape sequences
- **Booleans**: `true`, `false`

#### Identifiers
- **Variables**: Standard identifiers (`[a-zA-Z_][a-zA-Z0-9_]*`)
- **Type Variables**: Prefixed with apostrophe (`'a`, `'b`)

#### Comments
- **Line Comments**: `#` to end of line

#### Advanced Constructs
- **Records**: `{ name = "Alice", age = 30 }`
- **Variants**: `| Ok { value: Int }, Error { message: String } |`
- **Lambda Functions**: `{ x, y } -> x + y`
- **Pattern Matching**: `match expr { | pattern -> result }`
- **Effect Declarations**: `effect Log { log : {msg: String} -> {} }`
- **Implementations**: `impl Log for Console { ... }`

### 1.2 TextMate Grammar Scopes

We'll use standard TextMate scopes for consistency:

```json
{
  "keywords": "keyword.control.funk",
  "operators": "keyword.operator.funk",
  "strings": "string.quoted.double.funk",
  "numbers": "constant.numeric.funk",
  "comments": "comment.line.number-sign.funk",
  "identifiers": "variable.other.funk",
  "type-variables": "variable.parameter.type.funk",
  "function-names": "entity.name.function.funk",
  "type-names": "entity.name.type.funk",
  "effect-names": "entity.name.effect.funk"
}
```

### 1.3 Language Configuration

VSCode language configuration for:
- **Bracket Matching**: `{}`, `()`, `[]`
- **Auto-closing**: Brackets and quotes
- **Commenting**: Line comments with `#`
- **Indentation**: 2-space indentation
- **Word Patterns**: For word selection

## Phase 2: Tree-sitter Grammar

### 2.1 Grammar Structure

The Tree-sitter grammar will closely follow the ANTLR grammar but with Tree-sitter syntax:

```javascript
module.exports = grammar({
  name: 'funk',
  
  rules: {
    program: $ => repeat(choice($.statement, $.newline)),
    
    statement: $ => choice(
      $.let_binding,
      $.type_declaration,
      $.effect_declaration,
      $.impl_declaration,
      $.ability_declaration,
      $.impl_ability_declaration,
      $.expression_statement
    ),
    
    // ... more rules
  }
});
```

### 2.2 Tree-sitter Benefits

- **Incremental Parsing**: Fast re-parsing on edits
- **Error Recovery**: Continues parsing despite syntax errors
- **Multi-language Support**: Works with Neovim, Emacs, Atom, etc.
- **Syntax Highlighting**: More accurate than regex-based approaches
- **Code Navigation**: Supports go-to-definition, folding, etc.

## Phase 3: Development Environment Setup

### 3.1 DevEnv Configuration

Update `devenv.nix` to include:

```nix
{
  packages = [
    # Existing packages...
    pkgs-unstable.nodejs_20
    pkgs-unstable.npm
    pkgs-unstable.tree-sitter
    pkgs-unstable.vscode
  ];
  
  scripts = {
    # Existing scripts...
    
    # Syntax highlighting development
    build-vscode-ext.exec = ''
      echo "🎨 Building VSCode extension..."
      cd syntax-highlighting/vscode-extension
      npm install
      npm run compile
    '';
    
    install-vscode-ext.exec = ''
      echo "📦 Installing VSCode extension locally..."
      cd syntax-highlighting/vscode-extension
      code --install-extension .
    '';
    
    build-treesitter.exec = ''
      echo "🌳 Building Tree-sitter grammar..."
      cd syntax-highlighting/tree-sitter-funk
      npm install
      npx tree-sitter generate
      npx tree-sitter build
    '';
    
    test-treesitter.exec = ''
      echo "🧪 Testing Tree-sitter grammar..."
      cd syntax-highlighting/tree-sitter-funk
      npx tree-sitter test
    '';
  };
}
```

### 3.2 Package Management

- **VSCode Extension**: Use npm/yarn for dependencies
- **Tree-sitter**: Use npm for tree-sitter CLI and dependencies
- **Testing**: Include test files from examples/ directory

## Phase 4: Implementation Timeline

### Week 1: VSCode Extension Foundation
1. ✅ Create project structure
2. ✅ Set up basic TextMate grammar
3. ✅ Implement keyword highlighting
4. ✅ Add operator and literal support
5. ✅ Test with basic Funk files

### Week 2: Advanced VSCode Features
1. ✅ Implement record and variant highlighting
2. ✅ Add lambda function support
3. ✅ Pattern matching syntax
4. ✅ Effect system highlighting
5. ✅ Language configuration and themes

### Week 3: Tree-sitter Grammar
1. ✅ Set up Tree-sitter project
2. ✅ Implement core grammar rules
3. ✅ Add expression parsing
4. ✅ Support advanced constructs
5. ✅ Comprehensive testing

### Week 4: Integration and Testing
1. ✅ Test with all example files
2. ✅ Performance optimization
3. ✅ Documentation and README
4. ✅ Publish to VSCode marketplace
5. ✅ Community feedback integration

## Phase 5: Editor Integration

### 5.1 Supported Editors (via Tree-sitter)

- **Neovim**: Native Tree-sitter support
- **Emacs**: tree-sitter-mode
- **Atom**: Built-in Tree-sitter support
- **Sublime Text**: LSP-based integration
- **Vim**: Via plugins

### 5.2 Language Server Protocol (Future)

Consider implementing an LSP server for:
- **Semantic Highlighting**: Beyond syntax highlighting
- **Code Completion**: Context-aware suggestions
- **Error Diagnostics**: Real-time error checking
- **Go-to Definition**: Navigate to declarations
- **Hover Information**: Type information on hover

## Testing Strategy

### Test Files Coverage

Use existing example files for comprehensive testing:

1. **Basic Syntax**: `examples/basic/`
   - Literals, variables, simple expressions
   
2. **Functions**: `examples/functions/`
   - Lambda expressions, function calls, pipe operators
   
3. **Records**: `examples/records/`
   - Record literals, updates, punning
   
4. **Variants**: `examples/variants/`
   - Variant types, pattern matching
   
5. **Effects**: `examples/effects/`
   - Effect declarations, implementations, handlers
   
6. **Advanced**: `examples/advanced/`
   - Complex nested expressions, do blocks

### Validation Criteria

- ✅ All keywords properly highlighted
- ✅ Correct operator precedence in highlighting
- ✅ String and number literals correctly identified
- ✅ Comments properly styled
- ✅ Bracket matching works correctly
- ✅ No false positives in highlighting
- ✅ Performance acceptable on large files

## Distribution

### VSCode Extension
- **Marketplace**: Publish to VSCode marketplace
- **GitHub**: Open source repository
- **Documentation**: Comprehensive README and examples

### Tree-sitter Grammar
- **npm**: Publish as npm package
- **GitHub**: Include in tree-sitter organization
- **Integration Guides**: For different editors

## Success Metrics

1. **Accuracy**: 100% of Funk syntax correctly highlighted
2. **Performance**: Sub-100ms highlighting for typical files
3. **Adoption**: Integration in at least 3 major editors
4. **Community**: Active contributions and feedback
5. **Maintenance**: Regular updates with language evolution

## Future Enhancements

1. **Semantic Highlighting**: Beyond syntax, show semantic information
2. **Code Folding**: Intelligent folding for functions, records, etc.
3. **Bracket Colorization**: Different colors for nested brackets
4. **Error Highlighting**: Show syntax errors inline
5. **Snippets**: Code snippets for common patterns
6. **Themes**: Multiple color themes optimized for Funk
7. **LSP Integration**: Full language server support

---

This plan provides a comprehensive roadmap for creating world-class syntax highlighting support for the Funk programming language, ensuring developers have an excellent editing experience across multiple platforms.
