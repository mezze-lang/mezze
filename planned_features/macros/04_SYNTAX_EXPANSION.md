# The !() Syntax: Expansion Operator

The `!()` syntax is Mezze's **expansion operator**. It works with both functions and macros to provide concise invocation syntax.

## Core Concept: Expansion

When you write `name!(args)`, Mezze **expands** it during type-checking:

```
name!(args)
    ↓ EXPANDS TO ↓
(different code)
```

What it expands to depends on what `name` is:
- **Function**: Expands to named arguments
- **Macro**: Expands to generated code

## Expansion with Functions

### Basic Usage

```mezze
let add = {x, y} -> x + y

# Named arguments (default, recommended)
add {x = 1, y = 2}

# Positional expansion
add!(1, 2)
    ↓ EXPANDS TO ↓
add {x = 1, y = 2}
```

### How It Works

The compiler matches arguments by **position** with parameter names:

```
Function:      let add = {x, y} -> ...
                         ↑  ↑
Positional:    add!(1, 2)
                    ↓  ↓
Expanded:      add {x = 1, y = 2}
```

### Strict Rules for Functions

The `!()` syntax has strict requirements:

#### 1. Must Provide ALL Named Parameters

```mezze
let add = {x, y} -> x + y

add!(1, 2)    # ✅ Correct
add!(1)       # ❌ Error: missing y

# Error: !() requires all named arguments
#   Expected 2 arguments (x, y), got 1
#   help: !() syntax requires ALL arguments (no partial application)
#   help: For partial application, use named arguments: add {x = 1}
```

**No currying with `!()`!** Use `{}` for partial application:

```mezze
let add5 = add {x = 5}      # ✅ Partial application
let result = add5 {y = 3}   # ✅ Works

let add5 = add!(5)          # ❌ Error: requires all arguments
```

#### 2. Can't Provide Extra Arguments

```mezze
let add = {x, y} -> x + y

add!(1, 2, 3)  # ❌ Error: too many arguments

# Error: !() has too many arguments
#   Expected 2 arguments (x, y), got 3
```

#### 3. Open Records: Ignores `...`

For functions with open records `{name1, name2, ...}`, the `!()` syntax **only maps to explicitly named parameters**:

```mezze
let add = {x, y, ...} -> x + y

# Named arguments - all valid:
add {x = 1, y = 2}              # ✅ Just named params
add {x = 1, y = 2, z = 3}       # ✅ Named + extra fields
add {x = 1}                     # ✅ Partial application

# Positional arguments - strict:
add!(1, 2)                      # ✅ Maps to: add {x = 1, y = 2}
add!(1, 2, 3)                   # ❌ Error: too many (... doesn't count!)

# Error: !() has too many arguments
#   Expected 2 arguments (x, y), got 3
#   note: Function signature: {x: Int, y: Int, ...} -> Int
#   help: !() maps to named parameters only (... is ignored)
#   help: For extra fields, use named arguments: add {x = 1, y = 2, z = 3}
```

The rule: **Count only explicitly named parameters, ignore `...`**.

### When to Use !() with Functions

```
✅ Use !() when:
  - 1-2 simple arguments
  - Argument order is completely obvious
  - Examples: print!("msg"), add!(1, 2), max!(x, y)

❌ Avoid !() when:
  - Many arguments (>2)
  - Arguments need names for clarity
  - Partial application needed
  - Extra fields via open records
  - Examples: connect!(...) → use {} instead
```

**Default to `{}`, use `!()` rarely for functions.**

## Expansion with Macros

### Basic Usage

```mezze
macro list: {tokens: TokenStream} -> Expr (List 'a <>) = {tokens} ->
    parse_separated {tokens, separator = ","} |> build_list {...}

# Macro invocation
list!(1, 2, 3)
    ↓ MACRO RETURNS ↓
Expr (List Int <>)  # Statically typed!
    ↓ UNWRAPS TO ↓
list::append {
    xs = list::append {
        xs = list::append {
            xs = list::empty,
            x = 1
        },
        x = 2
    },
    x = 3
}
```

### How It Works

1. Parser collects tokens inside `!()`
2. Type-checker executes the macro function
3. Macro returns generated AST
4. Generated code replaces the macro call

### Tagged Templates

Templates use ``` instead of `!()`:

```mezze
macro sql: {content: String, interpolations: ...} -> SqlQuery = ...

sql```SELECT * FROM users WHERE id = ${user_id}```
    ↓ EXPANDS TO ↓
{
    query = "SELECT * FROM users WHERE id = ?",
    params = [user_id]
}
```

### When to Use !() with Macros

```
✅ Always use !() or ``` for macros

Pattern macros: list!(1, 2, 3)
Tagged templates: sql```SELECT...```

This is the standard syntax for macro invocation.
```

### Static Typing with Expr

Macros are **fully statically typed** using the `Expr` type:

```mezze
# Macro signature uses Expr
macro list: {tokens: TokenStream} -> Expr (List 'a <>) = {tokens} ->
    # Macro body is type-checked
    # Returns Expr-wrapped AST
    ...

# At call site
let numbers = list!(1, 2, 3)
# 1. Macro executes, returns: Expr (List Int <>)
# 2. Type checker unwraps Expr
# 3. Validates generated AST
# 4. Assigns type: List Int
# numbers has type: List Int ✓
```

**What is Expr?**
- Compile-time wrapper type around AST
- Contains type information: `Expr (List Int <>)`
- Enables static type checking of macro bodies
- Unwrapped during expansion (zero runtime cost)

**Type Inference:**
Both quasi-quoting and manual AST building are statically typed:

```mezze
# Quasi-quoting (type inferred from code)
macro increment: {tokens: TokenStream} -> Expr (Int <>) = {tokens} ->
    match parse_expr {tokens} is
        Ok {expr} -> quote {code = ~expr + 1}
        # Type checker sees: Int + Int
        # Infers: Expr (Int <>) ✓

# Manual AST building (type inferred from builders)
macro five: {tokens: TokenStream} -> Expr (Int <>) = {tokens} ->
    int_lit {value = 5}
    # int_lit: {value: Int} -> Expr Int
    # Infers: Expr Int ✓
```

**Effect Inference:**
Effects are also statically inferred:

```mezze
macro log_value: {tokens: TokenStream} -> Expr (Int <Log, ...>) = {tokens} ->
    quote {code = do
        perform Log in Log.debug {msg = "computing"}
        42
    }
    # Type checker infers: Expr (Int <Log, ...>) ✓
```

**Where Expr Disappears:**
`Expr` exists only at compile-time:

```
Macro Definition Time:
  macro list: ... -> Expr (List 'a <>) = ...
  ↓ Macro body is type-checked with Expr types

Macro Expansion Time:
  list!(1, 2, 3)
  ↓ Macro returns: Expr (List Int <>)
  ↓ Type checker unwraps Expr
  ↓ Validates AST
  ↓ Assigns: List Int

Runtime:
  - No Expr types exist
  - Only generated code remains
  - Zero overhead!
```

## Unified Mental Model

```
╔══════════════════════════════════════════════════════════════╗
║                  !() IS EXPANSION                            ║
╠══════════════════════════════════════════════════════════════╣
║                                                              ║
║  With Functions:                                             ║
║    add!(1, 2) → add {x = 1, y = 2}                          ║
║    When: Type-checking                                       ║
║    What: Positional → Named arguments                        ║
║    Use: Rarely (simple cases)                                ║
║                                                              ║
║  With Macros:                                                ║
║    list!(1, 2, 3) → list::append {xs = ..., x = 3}          ║
║    When: Type-checking                                       ║
║    What: Tokens → Generated code                             ║
║    Use: Commonly (standard syntax)                           ║
║                                                              ║
║  Both are function calls with expansion!                     ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
```

## Why Reuse !() for Both?

The `!()` syntax reinforces that **macros are just functions**:

```mezze
# Runtime function
let add = {x, y} -> x + y

# Compile-time function (macro)
macro list = {tokens} -> build_list {tokens}

# Same syntax for both!
add!(1, 2)        # Function call with expansion
list!(1, 2, 3)    # Macro call with expansion
```

**Key insight:** Macros aren't a special different thing. They're functions that run at compile-time and return `Expr`-wrapped AST.

## Comparison: {} vs !()

```mezze
let add = {x, y, ...} -> x + y

┌─────────────────────────┬─────────────────────────────────┐
│ Named Arguments {}      │ Positional Expansion !()        │
├─────────────────────────┼─────────────────────────────────┤
│ add {x = 1, y = 2}      │ add!(1, 2)                      │
│ ✅ Works                 │ ✅ → add {x = 1, y = 2}         │
├─────────────────────────┼─────────────────────────────────┤
│ add {x = 1, y = 2, z=3} │ add!(1, 2, 3)                   │
│ ✅ Extra fields allowed  │ ❌ Error: too many args         │
├─────────────────────────┼─────────────────────────────────┤
│ add {x = 1}             │ add!(1)                         │
│ ✅ Partial application   │ ❌ Error: missing y             │
├─────────────────────────┼─────────────────────────────────┤
│ Flexibility: HIGH       │ Flexibility: LOW (strict)       │
│ Use: Default            │ Use: Simple/obvious cases       │
└─────────────────────────┴─────────────────────────────────┘
```

## Compilation Timeline

```
Source Code: add!(1, 2)
    ↓
┌─────────────────────────┐
│ PARSING                 │
│ - Creates               │
│   PositionalCall node   │
└──────────┬──────────────┘
           ↓
  AST: PositionalCall("add", [1, 2])
           ↓
┌─────────────────────────┐
│ TYPE CHECKING           │
│ - Look up "add"         │
│ - Is it function?       │
│   → Validate args       │
│   → Expand to {}        │
│ - Is it macro?          │
│   → Execute macro       │
│   → Get generated AST   │
└──────────┬──────────────┘
           ↓
  Expanded: add {x = 1, y = 2}
           ↓
┌─────────────────────────┐
│ TYPE CHECKING (cont.)   │
│ - Check expanded code   │
└──────────┬──────────────┘
           ↓
  Typed AST
```

**Key:** Expansion happens during **type-checking**, not parsing!

This allows:
- Accurate signature lookup
- Better error messages
- Unified handling of functions and macros

## Error Messages

### Function Errors

```mezze
add!(1)  # Missing argument

# Error: !() requires all named arguments
#   --> example.mz:1:1
#  |
#1 | add!(1)
#  | ^^^^^^^ expected 2 arguments (x, y), got 1
#  |
#  note: Function signature: {x: Int, y: Int} -> Int
#  help: !() maps to named parameters only
#  help: For partial application, use: add {x = 1}
```

```mezze
add!(1, 2, 3)  # Too many arguments

# Error: !() has too many arguments
#   --> example.mz:1:1
#  |
#1 | add!(1, 2, 3)
#  | ^^^^^^^^^^^^^ expected 2 arguments (x, y), got 3
#  |
#  note: Function signature: {x: Int, y: Int} -> Int
```

### Macro Errors

```mezze
list!(1, "two", 3)  # Type error in generated code

# Error: Type mismatch in list
#   Expected: List Int
#   Found: String at position 2
#
#   Original call:
#     --> example.mz:1:9
#    |
#  1 | list!(1, "two", 3)
#    |         ^^^^^ second element is String (incompatible!)
#
#   Expanded to:
#     list::append {
#       xs = list::append {xs = list::empty, x = 1},  -- Int
#       x = "two"                                      -- String (ERROR)
#     }
#
#   help: All elements in a list must have the same type
```

## Style Guide

```
Mezze Style: When to Use !()

For Functions (rare):
  ✅ Use when:
    - 1-2 simple arguments
    - Argument order is obvious
    - Examples: print!("msg"), add!(1, 2)

  ❌ Avoid when:
    - Many arguments (>2)
    - Arguments need clarity
    - Partial application
    - Extra fields
    - Examples: connect!(...) → use {} instead

For Macros (common):
  ✅ Always use:
    - Pattern macros: list!(1, 2, 3)
    - Tagged templates: sql```...```
    - Standard macro syntax

Guideline: Default to {}, use !() for:
  - Simple function calls (rarely)
  - Macro invocations (commonly)
```

## Teaching: !() as "Syntax Expansion"

```
Think of !() as asking Mezze to "expand this for me":

  name!(args)
      ↓
  (expanded code)

If name is a function:
  → Expand to named arguments

If name is a macro:
  → Expand to generated code

Both are expansions of function calls.
This is why macros and functions share the !() syntax.
```

## Summary

✅ **!() is the expansion operator**
✅ **Works for functions and macros**
✅ **Expands during type-checking**
✅ **Strict for functions** (all args, no `...`)
✅ **Standard for macros** (common usage)
✅ **Reinforces unity** (macros are functions)
✅ **Type-safe** (validates everything)
✅ **Fully statically typed** (macros use `Expr` wrapper)

The `!()` syntax is simple, predictable, and philosophically meaningful: it says "macros are just functions that expand at compile-time and return statically-typed AST."
