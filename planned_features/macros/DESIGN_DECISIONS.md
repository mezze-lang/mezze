# Mezze Macro System - Design Decisions

## Executive Summary

Mezze's macro system is based on a simple, elegant principle: **macros are compile-time functions that return statically-typed AST**. This document captures the key design decisions and rationale.

## Core Principles

### 1. Macros Are Functions

**Decision**: Macros are regular functions with special signatures, not a separate language construct.

```mezze
# Runtime function
let add = {x, y} -> x + y

# Compile-time function (macro)
macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    parse_and_build_list {tokens}
```

**Rationale**:
- Simpler mental model - no artificial distinction
- Reuses existing function type system
- Same invocation syntax (`!()`) for both
- Easier to learn and teach

### 2. No Runtime Errors, Only Compile-Time Errors

**Decision**: Mezze NEVER has runtime errors. All errors are either compile-time errors or explicit effects.

**This is fundamental to the language design.**

```mezze
# ❌ NO runtime errors like:
# - NullPointerException
# - IndexOutOfBounds
# - TypeCastException

# ✅ Instead:
# - Compile-time type errors
# - Explicit effects: Option, Result, exception effect
```

**Implications for macros**:
- Macro execution errors → compile-time errors
- Generated code validation → compile-time errors
- Invalid syntax in templates → compile-time errors
- Type mismatches → compile-time errors

### 3. Static Typing with Expr Wrapper

**Decision**: Use `Expr` as a phantom type wrapper to enable static typing of macro return values.

```mezze
# Expr is a simple phantom type
type Expr 'a = Expression

# Macro signature describes the TYPE of generated code
macro list: {tokens: TokenStream} -> Expr (List 'a) = ...
#                                    ^^^^^^^^^^^^^^^^
#                                    Type of generated AST
```

**Why Expr?**
1. **Accuracy** - Macros literally return AST (Expression), not values
2. **Type safety** - Can describe the type of generated code
3. **No magic** - Just a regular phantom type, no special compiler support
4. **Composability** - Works with all types, including suspended types

**Alternatives considered**:
- ❌ No wrapper, just type signature → inconsistent (functions return values, macros return AST)
- ❌ Complex dependent types → too complicated, not needed
- ✅ Simple phantom type → perfect fit!

### 4. Two-Phase Type Checking

**Decision**: Type check macros in two phases:

**Phase 1 (Optional)**: Type check macro implementation
- Validate macro function body
- Check AST builder calls are correct
- Enabled if macro has full type signature

**Phase 2 (Required)**: Type check generated code
- Expand macro at call site
- Type check generated AST
- Verify it matches declared return type
- All errors are compile-time errors

```mezze
# Phase 1: Check macro body (optional)
macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    let items = parse_items {tokens}  # Type checked
    list_literal {elements = items}   # Type checked
    # ✅ Returns Expr (List 'a)

# Phase 2: Check generated code (required)
let x = list!(1, 2, 3)
# 1. Expand macro → get AST
# 2. Type check AST → infers List Int
# 3. Verify: List Int matches Expr (List 'a) ✓
# 4. Unwrap Expr, assign: x has type List Int
```

### 5. Suspended Types for Effects

**Decision**: Add standalone suspended type syntax to enable effectful macro returns.

**Current problem**:
```mezze
# ❌ Can't write this - no syntax for standalone suspended types
macro log_value: {tokens: TokenStream} -> Int <Log> = ...

# ✅ Can only write this - effects only in function types
macro log_value: {tokens: TokenStream} -> ({} -> Int <Log>) = ...
```

**Solution**: Allow `<effects>` on any type, not just function return types:

```mezze
# ✅ New syntax - suspended types anywhere
let x: Int <Log> = ...
let config: {port: Int} <FileSystem> = ...

# ✅ In macro signatures
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...

# ✅ Composes with Expr naturally
type Expr 'a = Expression  # Simple phantom type
# Expr (Int <Log>) works because (Int <Log>) is a valid type!
```

**Why this works**:
- AST already has `SuspendType` (used internally by type checker)
- Type checker already handles suspended types
- Just need parser support for user-written suspended types
- No special compiler magic needed!

### 6. Expr is NOT Special Compiler Magic

**Critical insight**: `Expr` is just a regular type alias, not a compiler built-in:

```mezze
# Defined in standard library (not compiler!)
type Expr 'a = Expression
```

The magic happens in **macro expansion**, not in the `Expr` type:

```
User writes:    let x = list!(1, 2, 3)
                       ↓
Compiler:       1. Recognizes macro call (list is a macro)
                2. Executes macro function
                3. Gets result: Expression (AST node)
                4. Type checks AST → infers List Int
                5. Verifies: List Int matches Expr (List 'a) ✓
                6. Assigns: x has type List Int
                       ↓
Result:         Expr unwrapped, x has type List Int
```

**Key**: `Expr` exists only at compile-time. It's a type-level marker that says "this function returns AST."

## Design Comparisons

### Rust vs Mezze Macros

| Aspect | Rust | Mezze |
|--------|------|------|
| **Macro type** | Pattern-based text transformers | Typed functions |
| **Return type** | Untyped (token stream) | Typed with `Expr` |
| **Type checking** | After expansion only | Two-phase (optional + required) |
| **Effects** | N/A | Statically tracked with `Expr (Type <Effects>)` |
| **Mental model** | Special syntax | Just functions |

**Advantages of Mezze's approach**:
- ✅ Static typing of macros
- ✅ Better error messages (type errors in macro body)
- ✅ Effects are tracked
- ✅ Simpler model (functions, not special constructs)

### Why Not Dependent Types?

**Considered**: Using dependent types to infer record types from field name strings:
```mezze
# Imaginary dependent type approach
record {fields = [{"x", int_lit {value = 5}}]}
# Type depends on string literal value "x"
```

**Rejected because**:
- ❌ Too complex for the language
- ❌ Affects entire type system
- ❌ Not needed - post-expansion type checking is sufficient

**Our approach**: Type check after expansion:
```mezze
# Macro generates AST
record {fields = [{"x", int_lit {value = 5}}]}

# After expansion, type checker sees:
{x = 5}
# Infers: {x: Int} ✓
```

No dependent types needed!

## Syntax Design

### The !() Expansion Operator

**Decision**: Reuse `!()` syntax for both function positional arguments and macro invocation.

**Rationale**: Reinforces that macros are functions:
- Functions: `add!(1, 2)` expands to `add {x = 1, y = 2}`
- Macros: `list!(1, 2, 3)` expands to generated code

Both are **function invocations with expansion**.

**Guidelines**:
- For functions: Use `!()` rarely (1-2 obvious args only)
- For macros: Use `!()` commonly (standard syntax)
- Default to `{}` for functions

### DSL Embedding

**Decision**: Use pattern macros with string literals for DSL embedding:

```mezze
sql!("SELECT * FROM users WHERE id = $(user_id)")
html!("<div>$(content)</div>")
```

**Rationale**:
- Single, consistent macro type across the language
- Reuses existing string interpolation syntax `$(...)`
- Simpler to parse and implement than separate tagged template syntax
- Same `!()` invocation for all macros

## Implementation Strategy

### Staged Rollout

**Phase 1**: Core macro infrastructure
- Parser changes for suspended types
- `Expr` type definition
- Macro declaration parsing
- Basic expansion

**Phase 2**: Standard library macros
- `list!`, `map!`, `set!`
- `assert_eq!`, `dbg!`
- Basic DSL macros

**Phase 3**: Advanced features
- Quasi-quoting with `quote {code = ...}`
- Reflection and derive macros
- `comptime` for compile-time execution

**Phase 4**: Ecosystem
- Documentation and examples
- IDE support
- Community macros

### Testing Strategy

**Unit tests**:
- Parser tests for suspended types
- Type checker tests for `Expr` unwrapping
- Effect subtyping tests

**Integration tests**:
- End-to-end macro expansion
- Error message quality
- Type inference with macros

**Property tests**:
- Hygiene (no variable capture)
- Type preservation (generated code is well-typed)

## Open Questions

### 1. Macro Import/Export

**Question**: How do macros interact with the module system?

**Options**:
- Macros are just functions → normal `pub` visibility
- Macros need special export (like Rust's `#[macro_export]`)

**Likely answer**: Normal visibility rules (macros are functions).

### 2. Macro Recursion

**Question**: Can macros call themselves recursively?

**Current answer**: Yes, during macro expansion. Need cycle detection.

### 3. Debugging Macros

**Question**: How do users debug macro expansion?

**Ideas**:
- `--expand-macros` compiler flag to show expanded code
- Debug mode that prints macro execution trace
- Source maps from expanded code back to macro call

## Future Extensions

**Possible later**:
1. **Procedural macros** - Full code generation in external language
2. **Attribute macros** - Like Rust's `#[derive]`
3. **Build scripts** - Using `comptime` for build-time code generation
4. **IDE integration** - Syntax highlighting in templates, macro expansion preview

**Not planned**:
1. ❌ Unhygienic macros (deliberate capture)
2. ❌ Type-generating macros (macros that define types)
3. ❌ Compile-time side effects (beyond file I/O for resources)

## Summary

Mezze's macro system is built on three simple ideas:

1. **Macros are functions** that run at compile-time
2. **Macros return `Expr`-wrapped AST** (statically typed)
3. **No runtime errors** - all macro errors are compile-time

This gives us:
- ✅ Type safety (macros are statically typed)
- ✅ Simplicity (macros are just functions)
- ✅ Power (full language available for metaprogramming)
- ✅ Safety (automatic hygiene, no runtime errors)

The key insight: **Don't make macros special.** They're compile-time functions with a special return type (`Expr`).
