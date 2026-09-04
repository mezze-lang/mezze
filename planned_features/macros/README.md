# Mezze Macro System Documentation

Complete documentation for Mezze's compile-time metaprogramming system.

## Quick Start

Mezze provides compile-time metaprogramming through a simple, unified design: **macros are just functions that run at compile-time**.

**Three types of macros:**

1. **Pattern Macros** - Transform token patterns to AST: `list!(1, 2, 3)`
2. **Tagged Templates** - Embed DSLs with interpolation: `sql```SELECT * FROM users WHERE id = ${id}````
3. **CompileTime** - Execute arbitrary code during compilation: `comptime {eval = {} -> ...}`

All macros are **automatically hygienic** (no variable capture) and **fully type-checked**.

## Documentation Index

Read in this order:

1. **[00_OVERVIEW.md](./00_OVERVIEW.md)** - High-level overview and design principles
2. **[01_PATTERN_MACROS.md](./01_PATTERN_MACROS.md)** - Pattern macro system (`!()` syntax)
3. **[02_TAGGED_TEMPLATES.md](./02_TAGGED_TEMPLATES.md)** - Tagged template system (``` syntax)
4. **[03_COMPTIME_AND_REFLECTION.md](./03_COMPTIME_AND_REFLECTION.md)** - Compile-time execution and reflection
5. **[04_SYNTAX_EXPANSION.md](./04_SYNTAX_EXPANSION.md)** - The `!()` syntax in depth
6. **[05_IMPLEMENTATION.md](./05_IMPLEMENTATION.md)** - Implementation guide for compiler developers
7. **[06_STDLIB_API.md](./06_STDLIB_API.md)** - Complete standard library API reference
8. **[07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md)** - Complete examples and FAQ

## Quick Examples

### Pattern Macro

```mezze
macro list: {tokens: TokenStream} -> Expr (List 'a <>) = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}
        Ok {items} when list::is_empty {list = items} ->
            qualified {name = "list::empty"}  # Returns: Expr (List 'a <>)
        Ok {items} -> build_list_construction {items}

# Usage:
let numbers = list!(1, 2, 3, 4, 5)
# Macro returns: Expr (List Int <>)
# numbers has type: List Int ✓
```

### Tagged Template

```mezze
macro sql: {content: String, interpolations: ...} -> Expr (SqlQuery <>) =
    {content, interpolations} ->
        validate_sql {sql = content}
        build_parameterized_query {content, interpolations}

# Usage:
let user_id = 42
let query = sql```SELECT name, email FROM users WHERE id = ${user_id}```
# Macro returns: Expr (SqlQuery <>)
# query has type: SqlQuery ✓
```

### CompileTime Execution

```mezze
# Built-in function (allows any effects, handles them internally)
comptime: {eval: {} -> 'a <...>} -> 'a

# Usage:
let config = comptime {eval = {} ->
    read_file {path = "config.json"} |> parse_json {@}
}
# Loaded at compile-time, result is pure value!
```

### Derive Macros

```mezze
type Point = {x: Int, y: Int}

comptime {eval = {} ->
    derive_eq {for_type = Point}
    derive_show {for_type = Point}
}

# Or use helper macro:
derive!(Eq, Show for Point)
```

### Positional Arguments (Function Expansion)

```mezze
let add = {x, y} -> x + y

add!(1, 2)  # Expands to: add {x = 1, y = 2}
```

## Key Features

### Macros as Functions

Macros are just functions with special signatures:

```mezze
# Runtime function
let add = {x, y} -> x + y

# Compile-time function (pattern macro)
macro list = {tokens} -> build_list_from_tokens {tokens}

# Same invocation syntax!
add!(1, 2)        # Function expansion
list!(1, 2, 3)    # Macro expansion
```

### Automatic Hygiene

Variables created in macros can't shadow user variables:

```mezze
macro with_temp = {tokens} ->
    let tmp = "tmp"  # Automatically hygienic!
    do_expr {
        statements = [let_stmt {name = tmp, value = expr}],
        result = identifier {name = tmp}
    }

# User code:
let tmp = 5
with_temp!(tmp + 1)  # No conflict - macro's "tmp" is separate
```

### Type Safety

All macro-generated code is type-checked:

```mezze
list!(1, "two", 3)
# Error: Type mismatch in list
#   Expected: List Int
#   Found: String at position 2
```

### Compile-Time Validation

Templates are validated at compile-time:

```mezze
sql```SELCT * FROM users```
# Error: Invalid SQL: syntax error near 'SELCT'
#   help: Did you mean 'SELECT'?
```

### Great Error Messages

```mezze
map!("x" => 5, "y")
# Error: Expected 'key => value', got: unexpected end
#   --> example.mz:3:20
#    |
#  3 | map!("x" => 5, "y")
#    |                  ^ here
```

### Static Typing with Expr

Macros are **fully statically typed** using the `Expr` type wrapper:

```mezze
# Pattern macro signature
macro list: {tokens: TokenStream} -> Expr (List 'a <>) = {tokens} ->
    # Both approaches are statically typed:

    # 1. Quasi-quoting (type inference from quoted code)
    quote {code = [1, 2, 3]}  # Infers: Expr (List Int <>)

    # 2. Manual AST building (type inference from builder functions)
    array_literal {elements = [
        int_lit {value = 1},   # Expr Int
        int_lit {value = 2},   # Expr Int
        int_lit {value = 3}    # Expr Int
    ]}  # Infers: Expr (List Int <>)
```

**Key insight:** AST builders are typed functions, so normal type inference works:
- `int_lit: {value: Int} -> Expr Int`
- `record: {fields: [(String, Expr 'a)]} -> Expr {fields...}`
- `do_expr: {statements: [Expr {}], result: Expr 'a} -> Expr 'a`

The type checker infers the record type from **string literal** field names:
```mezze
record {fields = [
    {"x", int_lit {value = 5}},
    {"y", int_lit {value = 10}}
]}
# Infers: Expr {x: Int, y: Int} ✓
```

**At expansion time**, `Expr` is unwrapped and the generated code is validated:
```mezze
let numbers = list!(1, 2, 3)
# Macro returns: Expr (List Int <>)
# Type checker unwraps Expr, validates, assigns type: List Int
# Variable numbers has type: List Int ✓
```

## Design Principles

1. **Macros are functions** - No artificial distinction
2. **Signature determines type** - TokenStream vs template
3. **Type safety everywhere** - Macros are statically typed with `Expr`
4. **Automatic hygiene** - No manual gensym
5. **Great error messages** - Show original and expanded
6. **`{}` is default** - `!()` for simple/expansion cases
7. **Unified syntax** - Same `!()` for functions and macros
8. **Two approaches, both typed** - Quasi-quoting OR manual AST building

## The !() Syntax

The `!()` syntax is Mezze's **expansion operator**:

**With Functions (Rare - Positional Arguments):**
```mezze
let add = {x, y} -> x + y

add {x = 1, y = 2}  # Named (default, recommended)
add!(1, 2)          # Positional (rare, for simple cases)
```

**With Macros (Common - Standard Invocation):**
```mezze
list!(1, 2, 3)      # Pattern macro
sql```...```        # Tagged template
```

**Why reuse `!()`?**
Because macros are just functions. The `!()` syntax reinforces this:
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
  ✅ sql```SELECT...```
```

## Standard Library Organization

```
stdlib/std/macro/
  ├── primitives.mz     # Parser combinators, AST builders
  ├── reflection.mz     # type_info, derive_* functions
  └── builtins.mz       # list, map, assert_eq, dbg, sql, html
```

## Quick Reference

```mezze
# Pattern macro (statically typed with Expr)
macro list: {tokens: TokenStream} -> Expr (List 'a <>) = {tokens} -> ...
list!(1, 2, 3)

# Tagged template (statically typed with Expr)
macro sql: {content: String, interpolations: ...} -> Expr (SqlQuery <>) = ...
sql```SELECT * FROM users WHERE id = ${id}```

# CompileTime (allows any effects, handled internally)
comptime: {eval: {} -> 'a <...>} -> 'a
let config = comptime {eval = {} -> load_config {}}

# Reflection & Derive
comptime {eval = {} -> derive_eq {for_type = Point}}
derive!(Eq, Show for Point)  # Helper macro
```

## Implementation Status

- [ ] Parser changes (AST nodes for MacroCall, TaggedTemplate)
- [ ] Type checker macro expansion phase
- [ ] Context-aware name resolution (automatic hygiene)
- [ ] Interpreter compile-time execution mode
- [ ] Standard library (std/macro/*)
- [ ] Built-in macros (list!, map!, assert_eq!, dbg!)
- [ ] Comprehensive tests
- [ ] Error message improvements

## Getting Help

For questions or issues:
- Read the FAQ in [07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md)
- Check the API reference in [06_STDLIB_API.md](./06_STDLIB_API.md)
- Look at complete examples in [07_EXAMPLES_AND_FAQ.md](./07_EXAMPLES_AND_FAQ.md)

## Contributing

When implementing macros:
1. Always handle parse errors with clear messages
2. Validate input (argument counts, syntax)
3. Use qualified names (`module::function`)
4. Trust automatic hygiene (don't try to generate unique names)
5. Test edge cases (empty, single, multiple items)
6. Document expected syntax in docstrings

## Future Extensions

Possible future features:
- Macro hygiene escape hatches (deliberate capture)
- Type-generating macros (not just expression/statement)
- Attribute macros (like Rust's `#[derive]`)
- Build scripts using CompileTime
- IDE support (syntax highlighting in templates)

---

**Happy meta-programming!** 🚀
