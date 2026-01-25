# Funk Codebase Refactoring Plan

**Branch**: `file-refactor`
**Goal**: Reorganize code into focused files with sealed traits for exhaustive pattern matching
**Principle**: No functionality changes - pure code reorganization and quality improvements

---

## Testing Strategy (Run After EVERY Phase!)

### Full Test Suite Execution
```bash
# 1. Build everything
gradle clean build

# 2. Run all tests
gradle test

# Expected results:
# - Parser tests: ~50+ tests pass
# - Typechecker tests: ~1700 tests pass
# - Interpreter tests: ~880 tests pass
# Total: ~2500+ tests should ALL PASS
```

### CLI Command Validation
```bash
# Test all CLI commands work
funk parse examples/hello.funk
funk check examples/hello.funk
funk exec examples/hello.funk
funk compile examples/hello.funk --output test.jar
java -jar test.jar
```

### Smoke Test Critical Features
```bash
# Test stdlib loading
funk exec -c "import std::effects::log; log::info {message = \"test\"}"

# Test type checking with abilities
funk check examples/complex_types.funk

# Test effect system
funk exec examples/effects_demo.funk
```

---

## Phase 1: AST Module Refactoring

### Current State
- **Files**: 3 (FunkAST.kt, TypePrinter.kt, OperatorAbilityMapping.kt)
- **Lines**: FunkAST.kt = 2,572 lines
- **When expressions**: 17
- **Issue**: All AST nodes in one massive file

### Refactoring Steps

#### 1.1 Split FunkAST.kt into logical files

**Create new files:**
- `ast/Expression.kt` - All expression types (Lambda, Application, Match, etc.)
- `ast/Pattern.kt` - All pattern types (LiteralPattern, VariantPattern, etc.)
- `ast/Type.kt` - All type definitions (FunctionType, VariantType, etc.)
- `ast/Declaration.kt` - Module items (LetBinding, TypeAliasDefinition, etc.)
- `ast/Ability.kt` - Ability and effect definitions
- `ast/Common.kt` - Shared types (Visibility, Identifier, SourceLocation, etc.)

**Keep FunkAST.kt as:**
- Module-level orchestration
- Export all types (for backward compatibility)
- Version and metadata

#### 1.2 Add sealed traits

**Make sealed:**
```kotlin
sealed interface Expression  // All expressions
sealed interface Pattern      // All patterns
sealed interface FunkType     // All types
sealed interface ModuleItem   // All declarations
sealed interface AbilityItem  // Ability members
```

**Benefits:**
- Compiler ensures exhaustive when matching
- No more `else -> error("Unexpected...")`
- Catch missing cases at compile time

#### 1.3 Convert when expressions

**Before:**
```kotlin
when (expr) {
    is Lambda -> ...
    is Application -> ...
    else -> error("Unexpected expression")  // BAD!
}
```

**After:**
```kotlin
when (expr) {  // Compiler ensures exhaustive
    is Lambda -> ...
    is Application -> ...
    is Match -> ...
    // Must handle ALL cases
}
```

#### 1.4 Clean up comments

**Remove:**
- "Critical fix" comments (make them proper code)
- "Hack" comments (refactor into proper solution)
- "TODO" that are already done

**Add:**
- KDoc on public interfaces
- Explanation of WHY, not WHAT

#### 1.5 Testing Gate

```bash
# Run all tests
gradle test

# Verify output
✓ All AST tests pass
✓ All Parser tests pass (depend on AST)
✓ All Typechecker tests pass
✓ All Interpreter tests pass
✓ CLI commands work
```

#### 1.6 Commit
```
git add ast/src/main/kotlin/com/funk/language/ast/
git commit -m "Refactor AST: Split into focused files with sealed traits

- Split FunkAST.kt (2,572 lines) into 6 focused files
- Add sealed interfaces for Expression, Pattern, Type, etc.
- Convert all when expressions to exhaustive matches
- Clean up 'critical fix' and 'hack' comments
- All tests pass ✓"
```

---

## Phase 2: Parser Module Refactoring

### Current State
- **Files**: 3 (FunkParser.kt, Lexer.kt, ParserCombinators.kt)
- **Lines**: FunkParser.kt = 4,932 lines
- **When expressions**: 276
- **Issue**: All parsing logic in one massive file

### Refactoring Steps

#### 2.1 Split FunkParser.kt

**Create new files:**
- `parser/ExpressionParser.kt` - parseExpression and helpers
- `parser/PatternParser.kt` - parsePattern and helpers
- `parser/TypeParser.kt` - parseType and helpers
- `parser/DeclarationParser.kt` - parseModuleItem, parseLetBinding
- `parser/AbilityParser.kt` - parseAbility, parseEffect
- `parser/FunkParser.kt` - Main entry point (much smaller)

#### 2.2 Extract parser combinators

Move repeated patterns to `ParserCombinators.kt`:
- sepBy variants
- optional constructs
- common token sequences

#### 2.3 Add sealed traits

Ensure parser result types are sealed if needed.

#### 2.4 Testing Gate

```bash
gradle test
✓ All tests pass (especially parser tests)
```

#### 2.5 Commit
```
git commit -m "Refactor Parser: Split into focused parsers

- Split FunkParser.kt (4,932 lines) into 6 focused files
- Extract common parser combinators
- Add sealed traits for parser results
- All tests pass ✓"
```

---

## Phase 3: Typechecker Module Refactoring (BIGGEST!)

### Current State
- **Files**: Multiple, but TypeChecker.kt dominates
- **Lines**: TypeChecker.kt = 18,307 lines (!!)
- **When expressions**: 253
- **Comments**: 320 TODO/hack/critical
- **Issue**: God class doing everything

### Refactoring Steps

#### 3.1 Split TypeChecker.kt

**Core checking (new files):**
- `typechecker/ExpressionTypeChecker.kt` - inferExpressionType
- `typechecker/PatternTypeChecker.kt` - inferPatternType
- `typechecker/DeclarationTypeChecker.kt` - typeCheckLetBinding
- `typechecker/AbilityTypeChecker.kt` - validateAbilityConstraints
- `typechecker/EffectTypeChecker.kt` - effect row checking

**Specialized logic:**
- `typechecker/UnificationEngine.kt` - unify, unifyTypes
- `typechecker/ConstraintValidator.kt` - ability constraint validation
- `typechecker/TypeSubstitution.kt` - substituteType, applySubstitution
- `typechecker/TypeExpansion.kt` - expandTypeApplication, expandAliases
- `typechecker/EnvironmentManager.kt` - context management

**Utilities:**
- `typechecker/TypeHelpers.kt` - common utilities
- `typechecker/ErrorReporting.kt` - error formatting helpers

**Keep as-is:**
- `ConstraintSolver.kt` (1,543 lines - reasonable)
- `ModuleResolver.kt` (module loading)
- `ContinuationTransformer.kt` (CPS transform)

#### 3.2 Add sealed traits aggressively

Make ALL type hierarchies sealed:
```kotlin
sealed interface FunkType  // Already exists?
sealed interface Constraint
sealed interface TypeConstraint
// etc.
```

#### 3.3 Convert 253 when expressions

**Critical**: This will catch bugs where we have:
```kotlin
else -> typeVar  // Silently wrong!
```

Should be:
```kotlin
is TypeApplication -> expandTypeApplication(...)
is TypeAlias -> expandTypeAlias(...)
is VarType -> typeVar
// etc. - exhaustive!
```

#### 3.4 Clean up 320 comments

Most work here - document WHY each fix was needed, make it proper code.

#### 3.5 Testing Gate (CRITICAL!)

```bash
# This is the most complex module - test thoroughly!
gradle clean build
gradle test

# Run specific test suites
gradle :typechecker:test
gradle :interpreter:test  # Depends on typechecker
gradle :cli:test

# Manual smoke tests
funk check stdlib/std/effects/log.funk
funk check examples/complex_types.funk
funk exec examples/effects_demo.funk

✓ ALL ~2500+ tests must pass
```

#### 3.6 Commit
```
git commit -m "Refactor Typechecker: Split god class into focused modules

- Split TypeChecker.kt (18,307 lines!) into 10+ focused files
- Add sealed traits for exhaustive pattern matching
- Convert 253 when expressions to exhaustive matches
- Clean up 320 'critical fix' comments
- All 2500+ tests pass ✓"
```

---

## Phase 4: Interpreter Module Refactoring

### Current State
- **Files**: Multiple, ASTToTruffleConverter.kt is large
- **Lines**: ASTToTruffleConverter.kt = 2,121 lines
- **When expressions**: 27
- **Issue**: Converter doing too much

### Refactoring Steps

#### 4.1 Split ASTToTruffleConverter.kt

**Create new files:**
- `interpreter/nodes/converter/ExpressionConverter.kt`
- `interpreter/nodes/converter/PatternConverter.kt`
- `interpreter/nodes/converter/EffectConverter.kt`
- `interpreter/nodes/converter/FunctionConverter.kt`
- `interpreter/nodes/converter/LiteralConverter.kt`
- `interpreter/nodes/ASTToTruffleConverter.kt` - Orchestrator

#### 4.2 Add sealed traits

- FunkValue hierarchy (if not already)
- Effect handler types
- Node result types

#### 4.3 Organize node hierarchy

Review `interpreter/nodes/` directory structure.

#### 4.4 Testing Gate

```bash
gradle test
gradle :interpreter:test

# Test runtime execution
funk exec examples/hello.funk
funk exec examples/effects_demo.funk

# Test compiled JAR
funk compile examples/hello.funk
java -jar examples/hello.jar

✓ All tests pass
✓ Execution works
✓ Compilation works
```

#### 4.5 Commit
```
git commit -m "Refactor Interpreter: Split converter into focused modules

- Split ASTToTruffleConverter.kt into 6 focused files
- Add sealed traits for exhaustive matching
- Organize node hierarchy
- All tests pass ✓"
```

---

## Phase 5: CLI Module Refactoring

### Current State
- **Files**: 1 (Main.kt)
- **Lines**: 1,299 lines
- **Issue**: Main doing everything

### Refactoring Steps

#### 5.1 Split Main.kt

**Create new files:**
- `cli/Commands.kt` - Command definitions and parsing
- `cli/CompilerBackend.kt` - JAR compilation logic
- `cli/ProjectDetection.kt` - Project root detection
- `cli/Main.kt` - Entry point and dispatcher

#### 5.2 Add sealed traits

```kotlin
sealed interface Command {
    data class Parse(...) : Command
    data class Check(...) : Command
    data class Exec(...) : Command
    data class Compile(...) : Command
}
```

#### 5.3 Testing Gate

```bash
# Test all CLI commands
funk parse examples/hello.funk
funk check examples/hello.funk
funk exec examples/hello.funk
funk compile examples/hello.funk

# Test with various options
funk exec -c "let x = 5"
funk compile --output test.jar examples/hello.funk

✓ All commands work
```

#### 5.4 Commit
```
git commit -m "Refactor CLI: Split into focused components

- Split Main.kt into 4 focused files
- Add sealed trait for Command types
- All CLI commands work ✓"
```

---

## Phase 6: Final Integration Testing

### 6.1 Full system test

```bash
# Clean build
gradle clean build

# Full test suite
gradle test

# Expected: ALL ~2500+ tests pass
```

### 6.2 Comprehensive CLI testing

```bash
# Parse
funk parse examples/hello.funk
funk parse examples/effects_demo.funk

# Check
funk check examples/hello.funk
funk check stdlib/std/effects/log.funk

# Exec
funk exec examples/hello.funk
funk exec -c "import std::effects::log; log::info {message = \"Refactoring complete!\"}"

# Compile
funk compile examples/hello.funk
java -jar examples/hello.jar

✓ Everything works
```

### 6.3 Performance check

Ensure refactoring didn't slow things down:
```bash
time funk check stdlib/std/effects/log.funk
# Should be similar to before refactoring
```

### 6.4 Documentation update

- Update any outdated docs
- Ensure architecture reflects new structure

### 6.5 Final commit
```
git commit -m "Refactoring complete: All modules organized and tested

Summary:
- AST: 1 file → 6 files with sealed traits
- Parser: 1 file → 6 files with sealed traits
- Typechecker: 1 file (18K lines) → 10+ files with sealed traits
- Interpreter: 1 file → 6 files with sealed traits
- CLI: 1 file → 4 files with sealed traits

Benefits:
- Exhaustive pattern matching (no more missed cases)
- Better code organization (single responsibility)
- Easier to maintain and understand
- All 2500+ tests pass ✓
- All functionality preserved ✓
- No performance regression ✓"
```

---

## Success Criteria

✅ All files under 1000 lines (target: 500-800 lines)
✅ All when expressions are exhaustive (no else branches)
✅ No "critical fix" or "hack" comments
✅ All sealed traits properly used
✅ ALL tests pass (2500+)
✅ All CLI commands work
✅ JAR compilation works
✅ No performance regression
✅ Code is more readable and maintainable

---

**Next Step**: Start Phase 1 - AST Module Refactoring
