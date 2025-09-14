# Funk Language Tooling

This directory contains all the tooling for Funk language editor support, including syntax highlighting, language servers, and editor extensions.

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
├── tree-sitter-funk/         # Tree-sitter grammar (future: Neovim, Helix, etc.)
│   ├── grammar.js            # Generated from ANTLR
│   ├── package.json
│   └── test/corpus/          # Test files
├── themes/                   # Color themes optimized for Funk
│   ├── funk-dark.json
│   └── funk-light.json
└── README.md                 # This file
```

## Strategy

### Phase 1: VSCode Support (Immediate)
- **ANTLR → TextMate Generator**: Convert Funk.g4 to TextMate grammar
- **VSCode Extension**: Basic syntax highlighting using generated grammar
- **No Node.js dependencies**: Pure Kotlin/JVM approach

### Phase 2: Modern Editor Support (Future)
- **ANTLR → Tree-sitter Generator**: Convert Funk.g4 to Tree-sitter grammar
- **Neovim Support**: Native Tree-sitter integration
- **Helix, Zed, Emacs**: Via Tree-sitter ecosystem

### Phase 3: Language Server (Advanced)
- **Kotlin-based LSP**: Reuse existing parser/typechecker/interpreter
- **Rich IDE Features**: Completion, diagnostics, hover, go-to-definition
- **Multi-editor**: Works with any LSP-compatible editor

## Development Commands

```bash
# Generate TextMate grammar from ANTLR
gradle :tooling:syntax-generators:generateTextMate

# Generate Tree-sitter grammar from ANTLR  
gradle :tooling:syntax-generators:generateTreeSitter

# Build VSCode extension
gradle :tooling:buildVSCodeExtension

# Test syntax highlighting
gradle :tooling:testSyntaxHighlighting
```

## Why This Approach?

1. **Single Source of Truth**: Funk.g4 grammar drives everything
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

