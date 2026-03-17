# Mezze Language Tooling

This directory contains all the tooling for Mezze language editor support, including syntax highlighting, language servers, and editor extensions.

## Structure

```
tooling/
├── syntax-generators/          # ANTLR → TextMate/Tree-sitter generators
│   ├── build.gradle.kts       # Kotlin-based generators
│   └── src/main/kotlin/       # Generator implementations
├── vscode-extension/          # VSCode extension (uses generated TextMate)
│   ├── package.json
│   ├── syntaxes/             # Generated TextMate grammars
│   └── language-configuration.json
├── tree-sitter-mezze/         # Tree-sitter grammar (future: Neovim, Helix, etc.)
│   ├── grammar.js            # Generated from ANTLR
│   ├── package.json
│   └── test/corpus/          # Test files
├── themes/                   # Color themes optimized for Mezze
│   ├── mezze-dark.json
│   └── mezze-light.json
└── README.md                 # This file
```

## Strategy

### Phase 1: VSCode Support (Immediate)
- **ANTLR → TextMate Generator**: Convert Mezze.g4 to TextMate grammar
- **VSCode Extension**: Basic syntax highlighting using generated grammar
- **No Node.js dependencies**: Pure Kotlin/JVM approach

### Phase 2: Modern Editor Support (Future)
- **ANTLR → Tree-sitter Generator**: Convert Mezze.g4 to Tree-sitter grammar
- **Neovim Support**: Native Tree-sitter integration
- **Helix, Zed, Emacs**: Via Tree-sitter ecosystem

### Phase 3: Language Server (Advanced)
- **Kotlin-based LSP**: Reuse existing parser/typechecker/interpreter
- **Rich IDE Features**: Completion, diagnostics, hover, go-to-definition
- **Multi-editor**: Works with any LSP-compatible editor

## Installing the VSCode Extension

```bash
# Navigate to the extension folder
cd tooling/vscode-extension/extension

# Install vsce if needed
npm install -g @vscode/vsce

# Package the extension
npx vsce package

# Install the generated .vsix file
code --install-extension mezze-language-0.3.1.vsix
```

**For development (live reload):**
1. Open `tooling/vscode-extension/extension` in VSCode
2. Press `F5` to launch Extension Development Host
3. Open any `.mz` file to test highlighting

## Current Status

The TextMate grammar (`syntaxes/mezze.tmLanguage.json`) is **manually maintained** and covers all Mezze language features including:
- String interpolation (`$()` syntax)
- Let destructuring patterns
- Ability method calls (`value.show {}`, `x.eq { r = y }`)
- Fraction literals (`1//2`)
- Effect rows, ability constraints, and more

See `vscode-extension/extension/test-syntax.mz` for comprehensive examples.

## Why This Approach?

1. **Single Source of Truth**: Mezze.g4 grammar drives everything
2. **Automatic Consistency**: Grammar changes → automatic highlighting updates
3. **Future-Proof**: Easy path to Tree-sitter for modern editors
4. **Leverage Existing Stack**: Kotlin/JVM, no unnecessary dependencies
5. **Incremental Development**: VSCode first, then expand to other editors

## Editor Support Roadmap

- ✅ **Week 1-2**: VSCode (TextMate grammar)
- 🔄 **Week 3-4**: Enhanced VSCode features (snippets, themes)
- 📅 **Month 2**: Tree-sitter grammar generation
- 📅 **Month 3**: Neovim integration
- 📅 **Month 4**: Language Server Protocol
- 📅 **Month 5**: Multi-editor ecosystem (Helix, Zed, Emacs)

