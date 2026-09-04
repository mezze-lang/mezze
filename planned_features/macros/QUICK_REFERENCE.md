# Mezze Macro System - Quick Reference

## TL;DR

Macros are **compile-time functions** that return **statically-typed AST**:

```mezze
type Expr 'a = Expression  # Simple phantom type!

macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    parse_and_build_list {tokens}

let numbers = list!(1, 2, 3)  # Type: List Int
```

## Key Concepts

### 1. Macros Are Functions
```mezze
# Runtime function
let add = {x, y} -> x + y

# Compile-time function (macro)
macro list: {tokens: TokenStream} -> Expr (List 'a) = ...
```

### 2. Expr Is a Phantom Type
```mezze
type Expr 'a = Expression

# 'a is the TYPE of generated code
macro five: {tokens: TokenStream} -> Expr Int = ...
#                                         ^^^^ Generates Int
```

### 3. Two-Phase Type Checking
```
Phase 1 (optional): Type check macro body
Phase 2 (required): Type check generated code
```

### 4. No Runtime Errors
**ALL errors are compile-time errors!**
- Macro execution errors → compile-time
- Generated code errors → compile-time
- Type mismatches → compile-time

### 5. Suspended Types for Effects
```mezze
# Pure macro
macro five: {tokens: TokenStream} -> Expr Int = ...

# Effectful macro
macro log_value: {tokens: TokenStream} -> Expr (Int <Log>) = ...

# Effect-polymorphic macro
macro generic: {tokens: TokenStream} -> Expr ('a <...>) = ...
```

## Syntax Reference

### Suspended Types
```mezze
# In variable declarations
let x: Int <Log> = ...
let config: {port: Int} <FileSystem> = ...

# In function return types
let f: {} -> Int <Log> = ...

# In macro signatures
macro name: {tokens: TokenStream} -> Expr (Int <Log>) = ...

# Multiple effects
let x: Int <Log, FileSystem> = ...

# Effect polymorphism
let x: Int <...> = ...
```

### Macro Declarations
```mezze
# Pattern macro - general use
macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    # Parse tokens and build AST
    ...

# Pattern macro - DSL embedding (e.g., SQL)
macro sql: {tokens: TokenStream} -> Expr (SqlQuery <>) = {tokens} ->
    # Parse string literal with interpolations
    # Validate and build query
    ...
```

### Macro Invocations
```mezze
# Pattern macro - list construction
list!(1, 2, 3)

# Pattern macro - DSL embedding
sql!("SELECT * FROM users WHERE id = $(user_id)")

# CompileTime execution
comptime {eval = {} -> load_config {}}
```

## AST Builder Functions

```mezze
# Literals
int_lit: {value: Int} -> Expr Int
string_lit: {value: String} -> Expr String
bool_lit: {value: Bool} -> Expr Bool

# Identifiers
identifier: {name: String} -> Expr 'a
qualified: {name: String} -> Expr 'a

# Function call
call: {fn: Expr ('a -> 'b), args: Expr 'a} -> Expr 'b

# Records
record: {fields: [(String, Expr 'a)]} -> Expr 'b

# Lists
list_literal: {elements: [Expr 'a]} -> Expr (List 'a)

# Control flow
if_expr: {condition: Expr Bool, then_branch: Expr 'a, else_branch: Expr 'a} -> Expr 'a
match_expr: {expression: Expr 'a, cases: [(Pattern, Expr 'b)]} -> Expr 'b

# Do blocks
do_expr: {statements: [Expr {}], result: Expr 'a} -> Expr 'a

# Lambdas
lambda: {params: [String], body: Expr 'a} -> Expr ({...} -> 'a)
```

## Complete Example

```mezze
# Define the macro
macro list: {tokens: TokenStream} -> Expr (List 'a <...>) = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}
        Ok {items} when list::is_empty {list = items} ->
            qualified {name = "list::empty"}
        Ok {items} ->
            list_literal {elements = items}

# Use the macro
let numbers = list!(1, 2, 3, 4, 5)
# Expands to: list_literal {elements = [1, 2, 3, 4, 5]}
# Type: List Int

# With effects
let logs = list!(
    perform Log in Log.info {msg = "a"},
    perform Log in Log.info {msg = "b"}
)
# Type: List {} <Log>
```

## Type System Rules

### Expr Unwrapping
```
Macro returns: Expr (List Int)
         ↓
Type checker unwraps Expr
         ↓
Variable gets: List Int
```

### Effect Subtyping
```
Int <Log>            ⊂  Int <Log, ...>  ✅ (polymorphic)
Int <Log>            ⊄  Int             ❌ (unhandled effects)
Int <Log>            ⊄  Int <FileSystem> ❌ (different effect)
Int <Log, FileSystem> ⊄  Int <Log>      ❌ (extra effect)
```

### Suspended Type Composition
```mezze
# Base types
Int
String
{x: Int, y: Int}

# With effects
Int <Log>
String <FileSystem>
{x: Int, y: Int} <State>

# In Expr
Expr Int
Expr (String <FileSystem>)
Expr ({x: Int} <State>)
```

## Common Patterns

### Error Handling in Macros
```mezze
macro my_macro: {tokens: TokenStream} -> Expr 'a = {tokens} ->
    match parse {tokens} is
        Err {error} -> compile_error {msg = error}  # Compile-time error!
        Ok {result} -> build_ast {result}
```

### Effect-Aware Macros
```mezze
macro log_and_compute: {tokens: TokenStream} -> Expr (Int <Log>) = {tokens} ->
    do_expr {
        statements = [
            expr_stmt {call {
                fn = qualified {name = "Log::info"},
                args = record {fields = [{"msg", string_lit {value = "computing"}}]}
            }}
        ],
        result = int_lit {value = 42}
    }
```

### Quasi-Quoting (Future)
```mezze
macro increment: {tokens: TokenStream} -> Expr Int = {tokens} ->
    match parse_expr {tokens} is
        Ok {expr} -> quote {code = ~expr + 1}
        #                   ^^^^^ splice operator
```

## Implementation Status

- ✅ AST has SuspendType
- ✅ Type checker uses SuspendType internally
- ⏳ Parser support for suspended types (Phase 1)
- ⏳ Expr type definition (Phase 2)
- ⏳ Macro declaration and expansion (Phase 2)
- ⏳ AST builder functions (Phase 3)
- ⏳ Built-in macros (Phase 3)
- ⏳ Documentation and tests (Phase 4)

## Files

### Documentation
- `DESIGN_DECISIONS.md` - Design rationale and alternatives
- `IMPLEMENTATION_PLAN.md` - Step-by-step implementation guide
- `SUSPENDED_TYPE_SYNTAX_ANALYSIS.md` - Parser and type checker changes needed
- `QUICK_REFERENCE.md` - This file

### Code
- AST: `ast/src/main/kotlin/com/mezze/language/ast/MezzeAST.kt`
- Parser: `parser/src/main/kotlin/com/mezze/language/parser/MezzeParser.kt`
- Type Checker: `typechecker/src/main/kotlin/com/mezze/language/typechecker/TypeChecker.kt`
- Stdlib: `stdlib/std/macro/`

## Timeline

**Phase 1** (Suspended Types): ~10 hours
**Phase 2** (Expr and Macros): ~17 hours
**Phase 3** (Standard Library): ~10 hours
**Phase 4** (Testing/Docs): ~12 hours

**Total**: ~49 hours (~1 week)

## Next Steps

1. Review DESIGN_DECISIONS.md
2. Review IMPLEMENTATION_PLAN.md
3. Start Phase 1.1: Add SuspendTypeAnnotation to AST
4. Follow implementation plan sequentially

## Questions?

See detailed design docs for rationale and alternatives considered.
