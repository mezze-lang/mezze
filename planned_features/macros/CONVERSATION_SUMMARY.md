# Macro System Design - Conversation Summary

## The Journey

This document captures the key questions and insights from the design conversation that led to Mezze's macro system design.

## Key Questions and Answers

### Q1: Should macros return AST or values?

**Answer**: Macros return AST (Expression nodes), not values.

**Why**: This is what macros actually do - they generate code that runs later, not compute values now.

**Implementation**: Use `Expr` as a phantom type wrapper:
```mezze
type Expr 'a = Expression  # Wraps AST

macro list: {tokens: TokenStream} -> Expr (List 'a) = ...
#                                    ^^^^^^^^^^^^^^^^
#                                    Returns AST that produces List 'a
```

---

### Q2: Do we need dependent types for static typing?

**Answer**: No! Post-expansion type checking is sufficient.

**Initial concern**: To infer record types from field name strings, we'd need dependent types:
```mezze
record {fields = [{"x", int_lit {value = 5}}]}
# How does type checker know this is {x: Int} without dependent types?
```

**Solution**: Type check AFTER expansion:
```mezze
# Macro generates AST
record {fields = [{"x", int_lit {value = 5}}]}

# After expansion, type checker sees literal code:
{x = 5}

# Normal type inference works!
# Infers: {x: Int} ✓
```

**Key insight**: We don't need the type during macro execution, only after expansion.

---

### Q3: Should we use Expr wrapper or just bare types?

**Answer**: Use `Expr` wrapper for accuracy and consistency.

**Considered**: `macro list: {tokens: TokenStream} -> List 'a = ...` (no Expr)

**Problem**: Inconsistent with functions:
- Functions return VALUES: `let add = {x, y} -> x + y` returns Int, not Expr Int
- Macros return AST: Should be explicit about this!

**Solution**: Make it explicit with Expr:
```mezze
macro list: {tokens: TokenStream} -> Expr (List 'a) = ...
```

**Benefit**: Type signature accurately describes what's being returned (AST producing List 'a).

---

### Q4: Can Expr be a regular phantom type, or does it need compiler magic?

**Answer**: Regular phantom type! No compiler magic needed!

**Implementation**:
```mezze
# Just a type alias in stdlib
type Expr 'a = Expression
```

The "magic" is in macro expansion, not the type:
```
1. Parser sees: list!(1, 2, 3)
2. Type checker executes macro function
3. Macro returns: Expression (AST node)
4. Type checker validates AST matches Expr (List Int)
5. Unwraps Expr, assigns: x has type List Int
```

**Key insight**: `Expr` is just a compile-time marker. It disappears after expansion.

---

### Q5: How do we handle effects in macro return types?

**Answer**: Add standalone suspended type syntax.

**Problem**: Can't write `Int <Log>` currently - effects only allowed in function types:
```mezze
# ❌ Doesn't work
macro log_value: {tokens: TokenStream} -> Int <Log> = ...

# ✅ Currently must write
macro log_value: {tokens: TokenStream} -> ({} -> Int <Log>) = ...
```

**Solution**: Allow `<effects>` on any type:
```mezze
# ✅ New syntax
let x: Int <Log> = ...
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...
```

**Lucky break**: AST already has `SuspendType`! Type checker already uses it! Just need parser support.

---

### Q6: Can macros return functions?

**Answer**: Yes! And this works naturally with existing syntax.

**Examples**:
```mezze
# Immediate execution
macro list: {tokens: TokenStream} -> Expr (List 'a <...>) = ...
let items = list!(1, 2, 3)  # Runs immediately

# Lazy execution
macro lazy: {tokens: TokenStream} -> Expr ({} -> Int <Log>) = ...
let computation = lazy!(expensive {})  # Returns function
let result = computation {}             # Execute later
```

**Key insight**: Function types work naturally - no special handling needed!

---

### Q7: Does Rust's approach give us any insights?

**Answer**: Yes! Rust macros can expand to anything, including functions.

**Rust**:
```rust
macro_rules! lazy {
    ($expr:expr) => {
        || $expr  // Returns closure
    }
}
```

**Mezze equivalent**:
```mezze
macro lazy: {tokens: TokenStream} -> Expr ({} -> 'a) = {tokens} ->
    lambda {params = [], body = parse_expr {tokens}}
```

**Insight**: Macros should be able to return ANY type, including:
- Pure types: `Int`, `String`
- Suspended types: `Int <Log>`
- Function types: `{} -> Int`
- Combinations: `{} -> (Int <Log>)`

---

### Q8: What about the Expr (Int <Log>) syntax?

**Answer**: It works naturally if we add suspended type syntax!

**The realization**:
1. We need `Type <Effects>` syntax anyway (useful beyond macros)
2. Once we have it, `Expr (Int <Log>)` works automatically!
3. `Expr` is just a type constructor that takes any type parameter
4. `Int <Log>` becomes a valid type
5. `Expr (Int <Log>)` composes naturally!

**No special cases needed!**

---

## Evolution of Understanding

### Initial Thought (Wrong)
"We need complex dependent types to make macro static typing work."

### First Correction
"Actually, post-expansion type checking is enough. No dependent types needed!"

### Second Realization
"Macros return AST, not values. We should use Expr wrapper to make this explicit."

### Third Insight
"Expr can be a simple phantom type. No compiler magic!"

### Fourth Discovery
"We need suspended type syntax anyway. Once we have it, Expr (Type <Effects>) works automatically!"

### Final Understanding
**Macros are compile-time functions that return statically-typed AST using a simple phantom type wrapper. Suspended types compose naturally with Expr because they're just regular types.**

### Final Simplification
**One macro type instead of two**: Use pattern macros with `!()` syntax for everything, including DSL embedding. For example: `sql!("SELECT * FROM users WHERE id = $(id)")` instead of a separate tagged template syntax with triple backticks.

## Design Principles That Emerged

### 1. Simplicity Over Cleverness
Use simple phantom types instead of complex dependent types.

### 2. Consistency
Macros are functions, so use function syntax and semantics.

### 3. Composability
Design features that compose naturally:
- Suspended types work everywhere
- Expr works with any type
- Effects compose with type parameters

### 4. No Special Cases
Avoid special compiler handling when possible:
- Expr is just a type alias
- Suspended types are just types
- Macros are just functions (that run at compile-time)

### 5. Fundamental Safety
**No runtime errors.** ALL errors are compile-time.

This principle informed every decision:
- Macro execution errors → compile-time
- Type mismatches → compile-time
- Effect handling errors → compile-time

## Technical Breakthroughs

### Breakthrough #1: Post-Expansion Type Checking
Don't type check macro bodies in detail. Just:
1. Execute macro (any way it works)
2. Type check generated AST
3. Verify it matches declared return type

Simple and effective!

### Breakthrough #2: Expr as Phantom Type
No need for special compiler support:
```mezze
type Expr 'a = Expression  # That's it!
```

The type system does the rest.

### Breakthrough #3: Suspended Types Everywhere
Adding `Type <Effects>` syntax:
- Useful for regular code (documenting effects)
- Enables macros to return effectful code
- Composes with Expr naturally
- AST and type checker already support it!

Just needs parser changes.

## Implementation Insights

### What Already Works
- ✅ AST has SuspendType
- ✅ Type checker uses SuspendType
- ✅ Type inference works
- ✅ Effect system works

### What Needs Work
- ⏳ Parser support for `Type <Effects>` syntax
- ⏳ Macro declaration parsing
- ⏳ Macro expansion
- ⏳ AST builder functions
- ⏳ Standard library macros

### Key Risk: Compile-Time Execution
Executing macros at compile-time needs an interpreter.

**Options**:
1. Reuse existing Mezze interpreter
2. Minimal evaluator for AST builders
3. Implement built-ins in Kotlin initially

**Recommendation**: Start with #3, migrate to #1 later.

## Bugs Found During Design

### Bug #1: applySubstitution Missing SuspendType
**Location**: TypeChecker.kt:3528-3573

**Issue**: Uses `else -> type` and doesn't handle SuspendType explicitly.

**Impact**: Type variable substitution in suspended types might not work correctly.

**Fix**: Add explicit case for SuspendType.

### Bug #2: Possible Missing Cases in Exhaustive Whens
**Location**: Various places in TypeChecker.kt

**Issue**: When expressions on MezzeType might miss SuspendType case.

**Fix**: Audit all `when (type)` expressions, add SuspendType cases.

## What We Learned

### 1. Simple is Better
The final design is MUCH simpler than initial ideas:
- No dependent types
- No special compiler magic for Expr
- Just add one syntax feature (suspended types)

### 2. Think in Phases
Type checking in two phases makes everything simpler:
- Phase 1: Basic macro validation
- Phase 2: Generated code validation

### 3. Reuse Existing Infrastructure
Suspended types already exist in AST and type checker. Just expose them!

### 4. Fundamental Principles Guide Design
"No runtime errors" principle informed every decision and made the design cleaner.

## Final Design Summary

```mezze
# Simple phantom type (no magic!)
type Expr 'a = Expression

# Suspended types (just parser changes!)
let x: Int <Log> = ...

# Macros are functions
macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    parse_and_build_list {tokens}

# Everything composes naturally
let items = list!(1, 2, 3)  # Type: List Int ✓
```

**Three key pieces**:
1. Expr phantom type
2. Suspended type syntax
3. Macro expansion infrastructure

**Result**: Statically-typed macros with effect tracking, zero runtime cost, no compiler magic.

## Resources Created

1. **DESIGN_DECISIONS.md** - Why we chose this approach
2. **IMPLEMENTATION_PLAN.md** - How to build it
3. **SUSPENDED_TYPE_SYNTAX_ANALYSIS.md** - Technical analysis
4. **QUICK_REFERENCE.md** - Syntax guide
5. **CONVERSATION_SUMMARY.md** - This file

## Next Steps

1. Review all documentation
2. Start implementation following IMPLEMENTATION_PLAN.md
3. Phase 1: Suspended type syntax (~10 hours)
4. Phase 2: Expr and macros (~17 hours)
5. Phase 3: Standard library (~10 hours)
6. Phase 4: Testing and docs (~12 hours)

**Total**: ~49 hours of focused work

---

**The journey from "maybe we need dependent types" to "just a simple phantom type" was worth it!**
