# Mezze Macro System Documentation - Index

Complete documentation for Mezze's compile-time metaprogramming system.

## Start Here

**New to Mezze macros?** Read in this order:

1. **[QUICK_REFERENCE.md](./QUICK_REFERENCE.md)** - TL;DR syntax and examples (5 min read)
2. **[README.md](./README.md)** - High-level overview (10 min read)
3. **[DESIGN_DECISIONS.md](./DESIGN_DECISIONS.md)** - Why we designed it this way (20 min read)

**Ready to implement?**

4. **[IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)** - Step-by-step guide with timeline

## Core Documentation

### User Documentation

- **[README.md](./README.md)** - High-level overview and examples
- **[QUICK_REFERENCE.md](./QUICK_REFERENCE.md)** - Syntax reference and common patterns
- **[01_PATTERN_MACROS.md](./01_PATTERN_MACROS.md)** - Pattern macro system (`!()` syntax)
- **[02_TAGGED_TEMPLATES.md](./02_TAGGED_TEMPLATES.md)** - DSL embedding with pattern macros
- **[03_COMPTIME_AND_REFLECTION.md](./03_COMPTIME_AND_REFLECTION.md)** - Compile-time execution
- **[04_SYNTAX_EXPANSION.md](./04_SYNTAX_EXPANSION.md)** - The `!()` expansion operator
- **[07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md)** - Examples and FAQ

### Developer Documentation

- **[DESIGN_DECISIONS.md](./DESIGN_DECISIONS.md)** - Design rationale and alternatives
- **[IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)** - Implementation roadmap
- **[SCOPE_ANALYSIS.md](./SCOPE_ANALYSIS.md)** - Detailed scope and impact analysis
- **[SUSPENDED_TYPE_SYNTAX_ANALYSIS.md](./SUSPENDED_TYPE_SYNTAX_ANALYSIS.md)** - Technical analysis
- **[CONVERSATION_SUMMARY.md](./CONVERSATION_SUMMARY.md)** - Design conversation notes
- **[05_IMPLEMENTATION.md](./05_IMPLEMENTATION.md)** - Implementation guide
- **[06_STDLIB_API.md](./06_STDLIB_API.md)** - Standard library API reference
- **[PATTERN_MATCHING_API.md](./PATTERN_MATCHING_API.md)** - Pattern matching helpers for macros

## Quick Navigation

### By Topic

**Syntax**:
- Pattern macros: [01_PATTERN_MACROS.md](./01_PATTERN_MACROS.md)
- DSL embedding: [02_TAGGED_TEMPLATES.md](./02_TAGGED_TEMPLATES.md)
- Suspended types: [QUICK_REFERENCE.md#suspended-types](./QUICK_REFERENCE.md#suspended-types)
- Expr type: [QUICK_REFERENCE.md#expr-is-a-phantom-type](./QUICK_REFERENCE.md#expr-is-a-phantom-type)

**Implementation**:
- Phase 1 (Suspended types): [IMPLEMENTATION_PLAN.md#phase-1](./IMPLEMENTATION_PLAN.md#phase-1-suspended-type-syntax-foundation)
- Phase 2 (Macros): [IMPLEMENTATION_PLAN.md#phase-2](./IMPLEMENTATION_PLAN.md#phase-2-expr-type-and-basic-macros)
- AST changes: [SUSPENDED_TYPE_SYNTAX_ANALYSIS.md](./SUSPENDED_TYPE_SYNTAX_ANALYSIS.md)

**Examples**:
- Built-in macros: [07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md)
- Complete examples: [QUICK_REFERENCE.md#complete-example](./QUICK_REFERENCE.md#complete-example)

### By Role

**Language Users**:
1. [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) - Quick syntax lookup
2. [01_PATTERN_MACROS.md](./01_PATTERN_MACROS.md) - How to use macros
3. [07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md) - Common patterns

**Macro Authors**:
1. [PATTERN_MATCHING_API.md](./PATTERN_MATCHING_API.md) - Pattern matching helpers
2. [06_STDLIB_API.md](./06_STDLIB_API.md) - AST builder API
3. [01_PATTERN_MACROS.md](./01_PATTERN_MACROS.md) - Macro declaration
4. [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) - Common patterns

**Language Implementers**:
1. [SCOPE_ANALYSIS.md](./SCOPE_ANALYSIS.md) - Impact analysis and effort estimates
2. [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md) - Build roadmap
3. [DESIGN_DECISIONS.md](./DESIGN_DECISIONS.md) - Design rationale
4. [SUSPENDED_TYPE_SYNTAX_ANALYSIS.md](./SUSPENDED_TYPE_SYNTAX_ANALYSIS.md) - Technical details
5. [05_IMPLEMENTATION.md](./05_IMPLEMENTATION.md) - Implementation guide

**Language Designers** (reviewing the design):
1. [DESIGN_DECISIONS.md](./DESIGN_DECISIONS.md) - Core principles
2. [CONVERSATION_SUMMARY.md](./CONVERSATION_SUMMARY.md) - Design journey
3. [README.md](./README.md) - Overview

## Key Concepts

### Macros Are Functions
```mezze
# Runtime function
let add = {x, y} -> x + y

# Compile-time function (macro)
macro list: {tokens: TokenStream} -> Expr (List 'a) = ...
```

### Expr Is a Phantom Type
```mezze
type Expr 'a = Expression  # Simple type alias!

# Macros return AST wrapped in Expr
macro five: {tokens: TokenStream} -> Expr Int = ...
```

### Suspended Types for Effects
```mezze
# Pure macro
macro list: {tokens: TokenStream} -> Expr (List 'a) = ...

# Effectful macro
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...
```

### No Runtime Errors
**ALL errors are compile-time errors!**
- Macro execution errors → compile-time
- Type mismatches → compile-time
- Effect handling errors → compile-time

## Implementation Status

### ✅ Already Complete
- AST has `SuspendType`
- Type checker uses `SuspendType` internally
- Effect system works

### ⏳ In Progress
- Parser support for suspended types (Phase 1)
- Expr type definition (Phase 2)
- Macro declarations and expansion (Phase 2)
- AST builder functions (Phase 3)
- Built-in macros (Phase 3)

### 📅 Planned
- Quasi-quoting
- Reflection API
- Derive macros
- Template validation

## Timeline

| Phase | Task | Time | Status |
|-------|------|------|--------|
| 1 | Suspended type syntax | ~10 hours | ⏳ Planned |
| 2 | Expr and macro infrastructure | ~17 hours | ⏳ Planned |
| 3 | Standard library macros | ~10 hours | ⏳ Planned |
| 4 | Testing and documentation | ~12 hours | ⏳ Planned |
| **Total** | | **~49 hours** | **~1 week** |

## File Structure

```
docs/macros/
├── INDEX.md                              # This file
├── README.md                             # High-level overview
├── QUICK_REFERENCE.md                    # Quick syntax guide
├── DESIGN_DECISIONS.md                   # Design rationale
├── IMPLEMENTATION_PLAN.md                # Build roadmap
├── SUSPENDED_TYPE_SYNTAX_ANALYSIS.md     # Technical analysis
├── CONVERSATION_SUMMARY.md               # Design notes
├── 01_PATTERN_MACROS.md                  # Pattern macros
├── 02_TAGGED_TEMPLATES.md                # Tagged templates
├── 03_COMPTIME_AND_REFLECTION.md         # CompileTime
├── 04_SYNTAX_EXPANSION.md                # !() syntax
├── 05_IMPLEMENTATION.md                  # Implementation guide
├── 06_STDLIB_API.md                      # Stdlib API
└── 07_EXAMPLES_AND_FAQ.md                # Examples and FAQ
```

## Code Locations

### AST
```
ast/src/main/kotlin/com/mezze/language/ast/MezzeAST.kt
```
- Line 523-549: `SuspendType` (already exists!)
- Line 1806+: Type annotations (add `SuspendTypeAnnotation`)
- Line 1910+: `convertTypeAnnotation` (add suspended type case)

### Parser
```
parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt
```
- Line 4070+: Effect row parsing (extract to function)
- Add: `suspendedTypeAnnotation` parser
- Add: `parseEffectRow` function
- Add: `MACRO` token type

### Type Checker
```
typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt
```
- Line 3528+: `applySubstitution` (fix SuspendType case)
- Add: Macro declaration type checking
- Add: Macro expansion

### Standard Library
```
stdlib/std/macro/
├── expr.mz              # type Expr 'a = Expression
├── ast_builders.mz      # AST builder functions
└── builtins.mz          # Built-in macros (list!, dbg!, etc.)
```

## Getting Help

### Questions About Usage
See [07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md)

### Questions About Design
See [DESIGN_DECISIONS.md](./DESIGN_DECISIONS.md) and [CONVERSATION_SUMMARY.md](./CONVERSATION_SUMMARY.md)

### Questions About Implementation
See [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md) and [SUSPENDED_TYPE_SYNTAX_ANALYSIS.md](./SUSPENDED_TYPE_SYNTAX_ANALYSIS.md)

### Questions About Syntax
See [QUICK_REFERENCE.md](./QUICK_REFERENCE.md)

## Contributing

When implementing macros:
1. Read [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md)
2. Follow the phases sequentially
3. Test at each step
4. Update documentation as you go

## Related Documentation

- **Language Guide**: `docs/LANGUAGE_GUIDE.md`
- **Effect System**: `docs/effects/`
- **Type System**: `docs/types/`

---

**Ready to start?** Begin with [QUICK_REFERENCE.md](./QUICK_REFERENCE.md) for syntax, then [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md) to build it!
