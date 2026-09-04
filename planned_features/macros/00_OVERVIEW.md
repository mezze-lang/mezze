# Mezze Macro System Overview

Mezze's macro system provides compile-time metaprogramming through a simple, unified design: **macros are just functions that run at compile-time**.

## Core Concept

```mezze
# Runtime function
let add = {x, y} -> x + y

# Compile-time function (macro)
macro list = {tokens} -> build_list_from_tokens {tokens}
```

The only difference: **when they execute**. Everything else is the same.

## Two Types of Macros

Macros are distinguished by their **signature** (not by syntax):

### 1. Pattern Macros

Take unparsed tokens, return generated code.

**Signature:** `{tokens: TokenStream} -> 'result`

```mezze
macro list: {tokens: TokenStream} -> List 'a = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        [] -> qualified {name = "list::empty"}
        items -> build_list_construction {items}

# Usage:
list!(1, 2, 3)  # Expands to: list::append {xs = ..., x = 3}
```

### 2. Tagged Template Macros

Take template content with interpolations, return generated code.

**Signature:** `{content: String, interpolations: [{key: String, val: Expression}]} -> 'result`

```mezze
macro sql: {content: String, interpolations: [{key: String, val: Expression}]} -> SqlQuery =
    {content, interpolations} ->
        validate_sql {content}
        build_parameterized_query {content, interpolations}

# Usage:
sql```SELECT * FROM users WHERE id = ${user_id}```
```

## Built-in CompileTime

For executing arbitrary code at compile-time:

```mezze
# Built-in function (not a macro)
comptime: {eval: {} -> 'a} -> 'a

# Usage:
let config = comptime {eval = {} ->
    read_file {path = "config.json"} |> parse_json {@}
}
```

## The `!()` Syntax: Expansion for Functions and Macros

The `!()` syntax is Mezze's **expansion operator**. It works with both functions and macros:

### With Functions (Rare - Positional Arguments)

```mezze
let add = {x, y} -> x + y

# Named arguments (default, recommended)
add {x = 1, y = 2}

# Positional expansion (rare, for simple cases)
add!(1, 2)  # Expands to: add {x = 1, y = 2}
```

### With Macros (Common - Standard Invocation)

```mezze
macro list = {tokens} -> ...

# Macro invocation
list!(1, 2, 3)  # Expands to: generated code
```

### Why Reuse `!()` for Both?

Because **macros are just functions**. The `!()` syntax reinforces this:
- For functions: Expands positional args to named args
- For macros: Expands tokens to generated code

Both are **function invocations with expansion**.

## Calling Convention Guidelines

```
Default to {} for functions:
  ✅ connect {host = "localhost", port = 8080}

Use !() rarely for functions (1-2 obvious args):
  ✅ print!("Hello")
  ✅ add!(1, 2)

Use !() commonly for macros (standard syntax):
  ✅ list!(1, 2, 3)
  ✅ assert_eq!(x, y)
```

## Key Features

### Automatic Hygiene

Variables created in macros can't shadow user code:

```mezze
macro with_temp = {tokens} ->
    let tmp = "tmp"  # Automatically hygienic!
    do_expr {
        statements = [let_stmt {name = tmp, value = expr}],
        result = identifier {name = tmp}
    }

# User code:
let tmp = 5
with_temp!(tmp + 1)  # No conflict!
```

### Type Safety

All macro-generated code is fully type-checked:

```mezze
let x = list!(1, "two", 3)

# Error: Type mismatch in list
#   Expected: List Int
#   Found: String at position 2
#
#   Original call: list!(1, "two", 3)
#   Generated code: list::append {xs = ..., x = "two"}  # String, expected Int
```

### Reflection for Derive

Use `comptime` with reflection to generate implementations:

```mezze
type Point = {x: Int, y: Int}

comptime {eval = {} ->
    derive_eq {for_type = Point}
    derive_show {for_type = Point}
}

# Or with helper macro:
derive!(Eq, Show for Point)
```

## Compilation Pipeline

```
Source Code
    ↓
┌─────────────────────────┐
│ PARSING                 │
│ - Create AST            │
│ - PositionalCall nodes  │
│   for !() syntax        │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ TYPE CHECKING           │
│ Phase 1: Expand         │
│ - Functions: !() → {}   │
│ - Macros: Execute       │
│ - Replace with AST      │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ TYPE CHECKING           │
│ Phase 2: Validate       │
│ - Check expanded code   │
│ - Report errors         │
└──────────┬──────────────┘
           ↓
  Typed AST (runtime)
```

## Design Principles

1. **Macros are functions** - No artificial distinction
2. **Signature determines type** - TokenStream vs template
3. **Type safety everywhere** - All code is checked
4. **Automatic hygiene** - No manual gensym
5. **Great error messages** - Show original and expanded
6. **`{}` is default** - `!()` for simple/expansion cases
7. **Unified syntax** - Same `!()` for functions and macros

## Standard Library Organization

```
stdlib/std/macro/
  ├── primitives.mz     # Parser combinators, AST builders
  ├── reflection.mz     # type_info, derive_* functions
  └── builtins.mz       # list, map, assert_eq, dbg, sql, html
```

## Quick Reference

```mezze
# Pattern macro
macro list: {tokens: TokenStream} -> List 'a = {tokens} -> ...
list!(1, 2, 3)

# Tagged template
macro sql: {content: String, interpolations: ...} -> SqlQuery = ...
sql```SELECT * FROM users WHERE id = ${id}```

# CompileTime
let config = comptime {eval = {} -> load_config {}}

# Reflection & Derive
comptime {eval = {} -> derive_eq {for_type = Point}}
derive!(Eq, Show for Point)  # Helper macro
```

## Next Steps

Read the following documents for details:
- `01_PATTERN_MACROS.md` - Pattern macro system
- `02_TAGGED_TEMPLATES.md` - Tagged template system
- `03_COMPTIME_AND_REFLECTION.md` - CompileTime and derive
- `04_SYNTAX_EXPANSION.md` - The `!()` syntax in depth
- `05_IMPLEMENTATION.md` - Implementation guide
- `06_STDLIB_API.md` - Complete API reference
- `07_EXAMPLES_AND_FAQ.md` - Examples and FAQ
