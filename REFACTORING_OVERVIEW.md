# Funk Codebase Refactoring - Overview

**Branch**: `file-refactor`
**Status**: Planning Phase
**Goal**: Reorganize codebase into focused, maintainable files with exhaustive pattern matching

---

## Why Refactor?

### Current Problems

1. **Massive Files**
   - TypeChecker.kt: 18,307 lines
   - FunkParser.kt: 4,932 lines
   - FunkAST.kt: 2,572 lines
   - ASTToTruffleConverter.kt: 2,121 lines

2. **Non-Exhaustive Pattern Matching**
   - 788 `when` expressions across 58 files
   - Many use `else` branches that can hide bugs
   - Missing cases fall through silently

3. **Technical Debt Comments**
   - 524 instances of "TODO", "hack", "critical fix", "workaround"
   - Apologetic language instead of proper documentation
   - Fixes that are now proper code paths but still labeled as "hacks"

### Goals

✅ **Single Responsibility**: Each file has one clear purpose
✅ **Exhaustive Matching**: Use sealed traits, remove `else` catch-alls
✅ **Maintainability**: Files under 1000 lines (target: 500-800)
✅ **Professional Code**: Remove "hack" comments, proper documentation
✅ **Zero Breakage**: All tests pass, all functionality preserved

---

## Refactoring Phases

### Phase 1: AST Module ⏳
**Complexity**: Low (safest starting point)
**Files**: 1 → 6
**Lines**: 2,572 → ~400 per file
**Status**: Not Started

**Details**: See `docs/refactoring/AST_REFACTORING.md`

### Phase 2: Parser Module
**Complexity**: Medium
**Files**: 1 → 6
**Lines**: 4,932 → ~800 per file
**Status**: Not Started

**Details**: See `docs/refactoring/PARSER_REFACTORING.md` (to be created)

### Phase 3: Typechecker Module
**Complexity**: High (largest and most complex)
**Files**: 1 → 10+
**Lines**: 18,307 → ~1000-1500 per file
**Status**: Not Started

**Details**: See `docs/refactoring/TYPECHECKER_REFACTORING.md` (to be created)

### Phase 4: Interpreter Module
**Complexity**: Medium-High
**Files**: 1 → 6
**Lines**: 2,121 → ~350 per file
**Status**: Not Started

**Details**: See `docs/refactoring/INTERPRETER_REFACTORING.md` (to be created)

### Phase 5: CLI Module
**Complexity**: Low
**Files**: 1 → 4
**Lines**: 1,299 → ~300 per file
**Status**: Not Started

**Details**: See `docs/refactoring/CLI_REFACTORING.md` (to be created)

### Phase 6: Final Integration
**Complexity**: Medium
**Focus**: Testing and documentation
**Status**: Not Started

---

## Testing Strategy

### After EVERY Phase:

```bash
# 1. Full build
gradle clean build

# 2. Run all tests
gradle test
# Expected: ~2500+ tests pass (0 failures)

# 3. CLI validation
funk parse examples/hello.funk
funk check examples/hello.funk
funk exec examples/hello.funk
funk compile examples/hello.funk
java -jar examples/hello.jar

# 4. Smoke tests
funk exec -c "import std::effects::log; log::info {message = \"test\"}"
```

**Critical Rule**: If ANY test fails, stop and fix before proceeding!

---

## Key Principles

### 1. Sealed Traits Everywhere
```kotlin
// Before
interface Expression
data class Lambda(...) : Expression
// when (expr) { ... else -> error() }

// After
sealed interface Expression
data class Lambda(...) : Expression
// when (expr) { ... } // Compiler ensures exhaustive
```

### 2. No `else` in Pattern Matching
```kotlin
// Before (dangerous - can hide bugs)
when (type) {
    is FunctionType -> ...
    is VariantType -> ...
    else -> type  // What if we add RecordType later?
}

// After (safe - compiler catches missing cases)
when (type) {
    is FunctionType -> ...
    is VariantType -> ...
    is RecordType -> ...
    is TypeApplication -> ...
    // Must handle ALL cases
}
```

### 3. Clean Documentation
```kotlin
// Before
// CRITICAL FIX: This is a hack to work around issue #123
// TODO: Fix this properly later

// After
/**
 * Handles type expansion for parameterized types.
 * Required because TypeApplications must be expanded before
 * unification to avoid constraint validation errors.
 */
```

### 4. Single File Responsibility
- Each file should do ONE thing
- Target: 500-800 lines per file
- Max: 1000 lines before considering split

### 5. Incremental and Safe
- One module at a time
- Test after every change
- Commit after every successful phase
- Never break existing functionality

---

## Success Metrics

| Metric | Before | Target | Status |
|--------|--------|--------|--------|
| Largest file | 18,307 lines | < 1,500 lines | ⏳ |
| Files > 1000 lines | 4 files | 0 files | ⏳ |
| Non-exhaustive when | 788 | 0 | ⏳ |
| "hack" comments | 524 | 0 | ⏳ |
| Test pass rate | ~100% | 100% | ⏳ |
| All CLI commands | ✓ | ✓ | ⏳ |

---

## Timeline

| Phase | Duration | Priority |
|-------|----------|----------|
| Phase 1: AST | 2-3 hours | High |
| Phase 2: Parser | 3-4 hours | High |
| Phase 3: Typechecker | 8-12 hours | Critical |
| Phase 4: Interpreter | 4-6 hours | High |
| Phase 5: CLI | 1-2 hours | Medium |
| Phase 6: Integration | 2-3 hours | High |
| **Total** | **20-30 hours** | |

---

## Current Status

**Phase**: Planning Complete
**Next Step**: Phase 1 - AST Module Refactoring
**Document**: `docs/refactoring/AST_REFACTORING.md`

---

## Notes

- This is a **code organization refactoring**, not a feature development
- **Zero functionality changes** - only moving code around
- **All tests must pass** after each phase
- **Commit frequently** - after each successful file split
- Focus on **quality over speed** - this is about making the codebase maintainable

---

Last Updated: 2026-01-25
