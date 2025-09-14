# Strategic Funk Language Syntax Highlighter Plan

## Your Questions Answered 🤔

### Why Node.js in devenv.nix?
**You're right to question this!** Since Funk already uses:
- ✅ **ANTLR 4.13.1** (already in your build system)
- ✅ **Kotlin/JVM** infrastructure  
- ✅ **GraalVM** with Truffle
- ✅ **Gradle** build system

**We should leverage what you already have instead of adding Node.js!**

### Is Tree-sitter necessary?
**No, not initially.** Tree-sitter is:
- ❌ **Overkill** for basic syntax highlighting
- ❌ **Duplicate effort** when you have ANTLR
- ❌ **Additional complexity** without clear benefits

### Can we generate from ANTLR?
**YES!** This is the smart approach:
- ✅ **Single source of truth** (your Funk.g4)
- ✅ **Automatic consistency** with language evolution
- ✅ **Leverage existing infrastructure**

## Recommended Strategic Approach 🎯

### Phase 1: ANTLR-Based TextMate Grammar Generator (BEST APPROACH)

Instead of manually writing TextMate grammars, **generate them from your existing ANTLR grammar**:

```kotlin
// New Gradle task in parser/build.gradle.kts
tasks.register("generateTextMateGrammar") {
    dependsOn("generateGrammarSource")
    doLast {
        // Read ANTLR grammar and generate TextMate JSON
        generateTextMateFromAntlr()
    }
}
```

**Benefits:**
- 🎯 **Single source of truth** - your Funk.g4
- 🔄 **Auto-sync** - grammar changes automatically update highlighting
- 🚀 **No new dependencies** - uses existing Kotlin/JVM stack
- 📦 **Gradle integration** - fits your existing build system

### Phase 2: VSCode Extension (Minimal Dependencies)

Create a **simple VSCode extension** that:
- Uses generated TextMate grammar
- Minimal package.json (no complex Node.js dependencies)
- Can be built/packaged with existing tools

### Phase 3: Language Server Protocol (Future)

**This is where the real power is!** Build an LSP server using your existing infrastructure:
- ✅ **Kotlin-based LSP server** (reuse parser, typechecker, interpreter)
- ✅ **Rich IDE features**: diagnostics, completion, hover, go-to-definition
- ✅ **Multi-editor support**: VSCode, Neovim, Emacs, IntelliJ
- ✅ **Leverage Truffle**: semantic analysis, debugging support

## Implementation Plan 🛠️

### Step 1: ANTLR → TextMate Generator (Week 1)

```kotlin
// In parser/src/main/kotlin/
class TextMateGrammarGenerator {
    fun generateFromAntlr(grammarFile: File): TextMateGrammar {
        // Parse ANTLR grammar
        // Extract keywords, operators, patterns
        // Generate TextMate JSON
    }
}
```

**What it generates:**
```json
{
  "name": "Funk",
  "scopeName": "source.funk",
  "fileTypes": ["funk"],
  "patterns": [
    {
      "name": "keyword.control.funk",
      "match": "\\b(let|type|effect|impl|ability|match|if|then|else|do)\\b"
    },
    // ... auto-generated from ANTLR tokens
  ]
}
```

### Step 2: Minimal VSCode Extension (Week 2)

```
funk-vscode/
├── package.json          # Minimal, no Node.js build deps
├── syntaxes/
│   └── funk.tmLanguage.json  # Generated from ANTLR
├── language-configuration.json
└── README.md
```

**No npm install needed!** Just package with VSCode's built-in tools.

### Step 3: Gradle Integration (Week 2)

```kotlin
// In build.gradle.kts
tasks.register("buildVSCodeExtension") {
    dependsOn("generateTextMateGrammar")
    doLast {
        // Copy generated grammar to VSCode extension
        // Package extension using vsce (can be downloaded as needed)
    }
}
```

### Step 4: Language Server (Future - Week 4+)

```kotlin
// New module: language-server/
class FunkLanguageServer : LanguageServer {
    private val parser = FunkParser()
    private val typechecker = FunkTypechecker()
    
    override fun textDocument_hover(params: HoverParams): Hover {
        // Use existing parser + typechecker
    }
    
    override fun textDocument_completion(params: CompletionParams): CompletionList {
        // Leverage ANTLR parse tree
    }
}
```

## Why This Approach is Superior 🏆

### 1. **Leverage Existing Infrastructure**
- ✅ No Node.js needed
- ✅ Uses your ANTLR grammar as source of truth
- ✅ Integrates with existing Gradle build
- ✅ Kotlin-based (consistent with your stack)

### 2. **Automatic Consistency**
- ✅ Grammar changes → automatic highlighting updates
- ✅ New keywords → immediate VSCode support
- ✅ No manual TextMate maintenance

### 3. **Future-Proof Architecture**
- ✅ Easy to add LSP server later
- ✅ Multi-editor support through LSP
- ✅ Rich IDE features using existing compiler infrastructure

### 4. **Minimal Complexity**
- ✅ No Tree-sitter needed
- ✅ No complex Node.js build pipeline
- ✅ Reuses existing tools and knowledge

## Updated Project Structure

```
funk/
├── parser/
│   ├── src/main/kotlin/
│   │   └── TextMateGrammarGenerator.kt  # NEW
│   └── build.gradle.kts                 # Updated
├── language-server/                     # NEW (Future)
│   ├── src/main/kotlin/
│   │   └── FunkLanguageServer.kt
│   └── build.gradle.kts
├── vscode-extension/                    # NEW
│   ├── package.json                     # Minimal
│   ├── syntaxes/
│   │   └── funk.tmLanguage.json         # Generated
│   └── language-configuration.json
└── devenv.nix                          # No Node.js needed!
```

## Development Commands

```bash
# Generate TextMate grammar from ANTLR
gradle generateTextMateGrammar

# Build VSCode extension
gradle buildVSCodeExtension

# Install extension locally
code --install-extension ./vscode-extension

# Future: Start language server
gradle runLanguageServer
```

## Migration Path to Full IDE Support

1. **Week 1-2**: Basic syntax highlighting (ANTLR → TextMate)
2. **Week 3-4**: Enhanced VSCode extension with snippets, themes
3. **Month 2**: Kotlin-based LSP server
4. **Month 3**: Rich IDE features (completion, diagnostics, etc.)
5. **Month 4**: Multi-editor support (Neovim, Emacs, IntelliJ)

## Why Skip Tree-sitter Initially?

1. **ANTLR is already your parser** - Tree-sitter would be redundant
2. **VSCode doesn't natively use Tree-sitter** for syntax highlighting
3. **LSP provides better IDE features** than Tree-sitter alone
4. **Complexity without clear benefits** for your use case

## Conclusion: The Smart Path Forward 🚀

**Don't add Node.js. Don't write Tree-sitter. Don't duplicate effort.**

**Instead:**
1. ✅ Generate TextMate grammar from your existing ANTLR grammar
2. ✅ Create minimal VSCode extension using generated grammar  
3. ✅ Build Kotlin-based LSP server for rich IDE features
4. ✅ Leverage your existing GraalVM/Truffle infrastructure

This approach gives you:
- **Immediate syntax highlighting** with minimal effort
- **Future-proof architecture** for advanced IDE features
- **Consistency** with your existing development stack
- **No unnecessary dependencies** or complexity

**Ready to implement this smarter approach?**

