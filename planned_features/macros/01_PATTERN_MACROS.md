# Pattern Macros

Pattern macros transform token sequences into AST at compile-time. They're functions with the signature `{tokens: TokenStream} -> Expr ('result <effects>)`.

## Declaration Syntax

```mezze
macro name: {tokens: TokenStream} -> Expr ('result <effects>) = {tokens} ->
    # Parse tokens
    # Generate AST (using quasi-quoting or manual AST builders)
    # Return Expr wrapper around expression
```

**Key points:**
- Declared with `macro` keyword (not `let`)
- Lowercase naming (function convention)
- Signature must be `{tokens: TokenStream} -> Expr ('result <effects>)`
- Returns `Expr` type which wraps generated AST
- **Both quasi-quoting and manual AST building are statically typed**

## Basic Example: list

```mezze
macro list: {tokens: TokenStream} -> Expr (List 'a <>) = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}

        Ok {items} when list::is_empty {list = items} ->
            qualified {name = "list::empty"}  # Returns: Expr (List 'a <>)

        Ok {items} -> do
            let exprs = items |> list::map {list = @, f = {item ->
                match parse_expr {tokens = item} is
                    Ok {expr} -> expr  # expr: Expr 'a
                    Err {error} -> compile_error {msg = "Invalid expression: $(error)"}
            }}

            # Type inference works here!
            # Each call returns Expr (List 'a <>)
            list::fold_left {
                list = exprs,
                init = qualified {name = "list::empty"},  # Expr (List 'a <>)
                f = {acc, expr} ->
                    call {
                        fn = qualified {name = "list::append"},  # Expr ({xs: List 'a, x: 'a} -> List 'a)
                        args = record {fields = [
                            {"xs", acc},   # Expr (List 'a)
                            {"x", expr}    # Expr 'a
                        ]}  # Expr {xs: List 'a, x: 'a}
                    }  # Returns: Expr (List 'a <>)
            }

# Usage:
list!()           # Returns: Expr (List 'a <>), expands to list::empty
list!(1, 2, 3)    # Returns: Expr (List Int <>), type-checked!
```

## How It Works

```
User writes:    list!(1, 2, 3)
                    ↓
Parser:         Collects tokens: [1, ,, 2, ,, 3]
                    ↓
Type checker:   Calls list.expand({tokens = [1, ,, 2, ,, 3]})
                    ↓
Macro executes: 1. parse_separated → [[1], [2], [3]]
                2. parse_expr each → [Expr(1), Expr(2), Expr(3)]
                3. fold into nested calls
                    ↓
Returns AST:    FunctionCall(
                  fn = QualifiedName("list::append"),
                  args = Record([...])
                )
                    ↓
Type checker:   Validates generated AST
                    ↓
Result:         Typed expression of type List Int
```

## Static Typing with Expr

Macros are **fully statically typed** using the `Expr` type wrapper. Both quasi-quoting and manual AST building use standard type inference.

### Type Inference with Manual AST Builders

AST builders are regular functions with typed signatures:

```mezze
# AST builder signatures
int_lit: {value: Int} -> Expr Int
string_lit: {value: String} -> Expr String
bool_lit: {value: Bool} -> Expr Bool

record: {fields: [(String, Expr 'a)]} -> Expr {field_types...}
do_expr: {statements: [Expr {}], result: Expr 'a} -> Expr 'a
if_expr: {cond: Expr Bool, then: Expr 'a, else: Expr 'a} -> Expr 'a

call: {fn: Expr ('a -> 'b), args: Expr 'a} -> Expr 'b
```

Type inference works automatically:

```mezze
# Simple literal
int_lit {value = 5}
# Infers: Expr Int ✓

# Record construction
record {fields = [
    {"x", int_lit {value = 5}},
    {"y", int_lit {value = 10}}
]}
# Type checker sees string literals "x" and "y"
# Infers: Expr {x: Int, y: Int} ✓

# Do block
do_expr {
    statements = [],
    result = int_lit {value = 42}
}
# Infers: Expr Int ✓

# If expression
if_expr {
    cond = bool_lit {value = true},
    then = int_lit {value = 1},
    else = int_lit {value = 2}
}
# Type checker unifies branches
# Infers: Expr Int ✓
```

### Dependent Types for Literals

The type checker performs simple **dependent typing** for compile-time constants:

```mezze
# Record field names from string literals
record {fields = [
    {"name", string_lit {value = "Alice"}},  # Field name: "name"
    {"age", int_lit {value = 30}}             # Field name: "age"
]}
# Type checker extracts literal values "name" and "age"
# Infers: Expr {name: String, age: Int} ✓

# Qualified name lookup
qualified {name = "list::map"}
# Type checker:
# 1. Extracts string literal "list::map"
# 2. Looks up name in environment
# 3. Finds type: {f: 'a -> 'b, list: List 'a} -> List 'b
# Infers: Expr ({f: 'a -> 'b, list: List 'a} -> List 'b) ✓
```

**Requirements:**
- Field names must be **string literals** (compile-time constants)
- Qualified names must be **string literals**
- Dynamic strings fail with clear error:

```mezze
let field = compute_name {}
record {fields = [(field, int_lit {value = 5})]}
# Error: Cannot infer record type - field name must be a string literal
```

### Type Inference with Quasi-Quoting

Quasi-quoting uses normal type checking on quoted code:

```mezze
macro increment: {tokens: TokenStream} -> Expr (Int <>) = {tokens} ->
    match parse_expr {tokens} is
        Ok {expr} ->  # expr: Expr Int
            quote {code = ~expr + 1}
            # Type checker sees: Int + Int
            # Infers: Expr (Int <>) ✓
```

The `~` splice operator unwraps `Expr` in quoted context:
- `~: Expr 'a -> 'a` (inside quote only)
- Type checker treats spliced expressions as normal code
- Effect inference works automatically

### Effect Inference

Effects are inferred from generated code:

```mezze
macro log_and_compute: {tokens: TokenStream} -> Expr (Int <Log, ...>) = {tokens} ->
    match parse_expr {tokens} is
        Ok {expr} ->
            quote {code = do
                perform Log in Log.debug {msg = "computing"}  # Has Log effect
                ~expr + 1
            }
            # Type checker infers effects from quote
            # Returns: Expr (Int <Log, ...>) ✓
```

### Type Variable Preservation

Type variables flow through macro code naturally:

```mezze
macro identity: {tokens: TokenStream} -> Expr ('a <>) = {tokens} ->
    match parse_expr {tokens} is
        Ok {expr} ->  # expr: Expr 'a (unknown type)
            quote {code = ~expr}
            # Preserves type variable 'a
            # Returns: Expr ('a <>) ✓

# Usage:
identity!(5)       # Infers: Expr (Int <>), returns Int
identity!("hi")    # Infers: Expr (String <>), returns String
```

### Where Expr Disappears

`Expr` is a compile-time only type:

```mezze
# At definition time:
macro list: {tokens: TokenStream} -> Expr (List Int <>) = ...
# Macro body is type-checked with Expr types

# At expansion time:
let numbers = list!(1, 2, 3)
# 1. Macro returns: Expr (List Int <>)
# 2. Type checker unwraps Expr, extracts AST
# 3. Validates AST, assigns type: List Int
# 4. Variable numbers has type: List Int ✓

# At runtime:
# - Expr doesn't exist
# - Only the generated code remains
# - Zero overhead!
```

## Parser Primitives

All available in macro scope:

### Basic Parsers

```mezze
# Parse single expression (returns typed Expr)
parse_expr: {tokens: TokenStream} -> Result (Expr 'a) String

# Parse type annotation
parse_type: {tokens: TokenStream} -> Result Type String

# Parse identifier
parse_ident: {tokens: TokenStream} -> Result String String

# Parse string literal
parse_string: {tokens: TokenStream} -> Result String String

# Parse integer literal
parse_int: {tokens: TokenStream} -> Result Int String
```

### Parser Combinators

```mezze
# Parse items separated by delimiter
parse_separated: {
    tokens: TokenStream,
    separator: String  # ",", ";", ":", etc.
} -> Result [TokenStream] String

# Parse key-value pair
parse_pair: {
    tokens: TokenStream,
    separator: String  # "=>", "->", ":", etc.
} -> Result {left: TokenStream, right: TokenStream} String

# Parse binary operation
parse_binop: {
    tokens: TokenStream,
    op: String  # "+", "-", "*", etc.
} -> Result {left: Expr 'a, right: Expr 'b} String

# Parse repeat pattern (value; count)
parse_repeat_pattern: {tokens: TokenStream} ->
    Result {value: Expr 'a, count: Expr Int} String

# Try multiple parsers
parse_one_of: {
    tokens: TokenStream,
    parsers: [{TokenStream} -> Result 'a String]
} -> Result 'a String

# Parse optional pattern
parse_optional: {
    tokens: TokenStream,
    parser: {TokenStream} -> Result 'a String
} -> Result (Option 'a) String
```

### Token Operations

```mezze
# Match specific token
match_token: {tokens: TokenStream, token: String} ->
    Result {matched: String, rest: TokenStream} String

# Peek at next token
peek_token: {tokens: TokenStream} -> Option String

# Consume tokens while predicate holds
consume_while: {
    tokens: TokenStream,
    predicate: {String} -> Bool
} -> {consumed: [String], rest: TokenStream}
```

## AST Builders

All AST builders are **statically typed functions** that return `Expr` types.

### Expressions

```mezze
# Identifiers
identifier: {name: String} -> Expr 'a
qualified: {name: String} -> Expr 'a  # Type inferred from name lookup

# Literals
string_lit: {value: String} -> Expr String
int_lit: {value: Int} -> Expr Int
float_lit: {value: Float} -> Expr Float
bool_lit: {value: Bool} -> Expr Bool
array_literal: {elements: [Expr 'a]} -> Expr (List 'a)

# Function calls
call: {fn: Expr ('a -> 'b), args: Expr 'a} -> Expr 'b

# Records (type inferred from field name literals)
record: {fields: [(String, Expr 'a)]} -> Expr {field_types...}

# Control flow
if_expr: {
    cond: Expr Bool,
    then: Expr 'a,
    else: Expr 'a
} -> Expr 'a

match_expr: {
    scrutinee: Expr 'a,
    cases: [MatchCase]
} -> Expr 'b

# Do expressions
do_expr: {
    statements: [Expr {}],
    result: Expr 'a
} -> Expr 'a

# Lambda
lambda: {
    params: [String],
    body: Expr 'a
} -> Expr ({'params} -> 'a)

# Binary operations
binary_op: {
    op: String,
    left: Expr 'a,
    right: Expr 'b
} -> Expr 'c

# Member access
member: {
    obj: Expr 'record,
    field: String  # Must be string literal
} -> Expr 'field_type
```

### Statements

```mezze
let_stmt: {name: String, value: Expr 'a} -> Expr {}
expr_stmt: {expr: Expr 'a} -> Expr {}
```

### Meta Functions

```mezze
meta_file: {} -> Expr String   # Current file name
meta_line: {} -> Expr Int      # Current line number
stringify_expr: {expr: Expr 'a} -> String  # AST to string
compile_error: {msg: String} -> 'a  # Emit compile error and abort
```

### Quasi-Quoting

```mezze
# Quote code (type-checks quoted code)
quote: {code: 'a} -> Expr ('a <effects>)

# Splice operator (only inside quote)
(~): Expr 'a -> 'a  # Unwraps Expr in quoted context

# Splice list operator (only inside quote)
(~@): [Expr 'a] -> ['a]  # Unwraps list of Expr in quoted context
```

## Example: map Macro

```mezze
macro map: {tokens: TokenStream} -> Expr (Map 'k 'v <>) = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}

        Ok {items} when list::is_empty {list = items} ->
            call {
                fn = qualified {name = "map::empty"},  # Expr ({} -> Map 'k 'v)
                args = record {fields = []}             # Expr {}
            }  # Returns: Expr (Map 'k 'v <>)

        Ok {items} -> do
            let pairs = items |> list::map {list = @, f = {item ->
                match parse_pair {tokens = item, separator = "=>"} is
                    Err {error} -> compile_error {msg = "Expected 'key => value'"}
                    Ok {left, right} ->
                        match {parse_expr {tokens = left}, parse_expr {tokens = right}} is
                            {Ok {key}, Ok {value}} -> {key, value}  # {Expr 'k, Expr 'v}
                            {Err {error}, _} -> compile_error {msg = "Invalid key: $(error)"}
                            {_, Err {error}} -> compile_error {msg = "Invalid value: $(error)"}
            }}

            # Type inference: Expr (Map 'k 'v <>)
            list::fold_left {
                list = pairs,
                init = call {
                    fn = qualified {name = "map::empty"},
                    args = record {fields = []}
                },  # Expr (Map 'k 'v <>)
                f = {acc, pair} ->
                    call {
                        fn = qualified {name = "map::insert"},  # Expr ({map: Map 'k 'v, key: 'k, value: 'v} -> Map 'k 'v)
                        args = record {fields = [
                            {"map", acc},       # Expr (Map 'k 'v)
                            {"key", pair.key},  # Expr 'k
                            {"value", pair.value}  # Expr 'v
                        ]}  # Expr {map: Map 'k 'v, key: 'k, value: 'v}
                    }  # Expr (Map 'k 'v <>)
            }

# Usage:
map!()                        # Returns: Expr (Map 'k 'v <>)
map!("x" => 5, "y" => 10)    # Returns: Expr (Map String Int <>), type-checked!
```

## Example: assert_eq Macro

```mezze
macro assert_eq: {tokens: TokenStream} -> Expr ({} <>) = {tokens} ->
    match parse_separated {tokens, separator = ","} is
        Err {error} -> compile_error {msg = error}

        Ok {items} when list::length {list = items} == 2 ->
            build_assertion {
                left = parse_expr {tokens = list::at {list = items, index = 0}},
                right = parse_expr {tokens = list::at {list = items, index = 1}},
                msg = None
            }

        Ok {items} when list::length {list = items} == 3 ->
            build_assertion {
                left = parse_expr {tokens = list::at {list = items, index = 0}},
                right = parse_expr {tokens = list::at {list = items, index = 1}},
                msg = Some {value = parse_expr {tokens = list::at {list = items, index = 2}}}
            }

        _ -> compile_error {msg = "assert_eq! expects 2 or 3 arguments"}

let build_assertion = {left, right, msg} ->
    let left_var = "left"    # Automatically hygienic!
    let right_var = "right"  # Automatically hygienic!

    do_expr {
        statements = [
            let_stmt {name = left_var, value = left},
            let_stmt {name = right_var, value = right},
            expr_stmt {expr =
                if_expr {
                    condition = call {
                        fn = qualified {name = "Eq::ne"},
                        args = record {fields = [
                            {"l", identifier {name = left_var}},
                            {"r", identifier {name = right_var}}
                        ]}
                    },
                    then_branch = call {
                        fn = qualified {name = "panic"},
                        args = record {fields = [
                            {"msg", match msg is
                                None -> string_lit {value = "Assertion failed"}
                                Some {value} -> value
                            }
                        ]}
                    },
                    else_branch = record {fields = []}
                }
            }
        ],
        result = record {fields = []}
    }

# Usage:
assert_eq!(a, b)
assert_eq!(x + 1, y, "values should match")
```

## Example: dbg Macro (Using Quasi-Quoting)

```mezze
use std::effects::log::{Log}

macro dbg: {tokens: TokenStream} -> Expr ('a <Log, ...>) = {tokens} ->
    match parse_expr {tokens} is
        Err {error} -> compile_error {msg = error}

        Ok {expr} -> do
            let tmp = "dbg_tmp"  # Automatically hygienic!

            do_expr {
                statements = [
                    let_stmt {name = tmp, value = expr},
                    expr_stmt {expr =
                        call {
                            fn = qualified {name = "Log::debug"},
                            args = record {fields = [
                                {"msg", call {
                                    fn = qualified {name = "format"},
                                    args = record {fields = [
                                        {"template", string_lit {value = "[{}:{}] {} = {:?}"}},
                                        {"args", list_literal {elements = [
                                            meta_file {},
                                            meta_line {},
                                            string_lit {value = stringify_expr {expr}},
                                            identifier {name = tmp}
                                        ]}}
                                    ]}
                                }}
                            ]}
                        }
                    }
                ],
                result = identifier {name = tmp}
            }

# Usage:
let x = dbg!(compute_value {})
# Prints: [main.mz:5] compute_value {} = 42
# Returns: 42
```

## Automatic Hygiene

Variables created in macros are automatically hygienic:

```mezze
macro with_temp = {tokens} ->
    let tmp = "tmp"  # This becomes a unique identifier!
    do_expr {
        statements = [let_stmt {name = tmp, value = expr}],
        result = identifier {name = tmp}
    }

# User code:
let tmp = 5
with_temp!(tmp + 1)  # No conflict! Macro's "tmp" is different
```

**How it works:**
- Compiler tags all identifiers with expansion ID
- Identifiers created in macros get macro expansion ID
- Identifiers from parsed input keep user code ID
- Name resolution respects these IDs

## Error Handling

Always handle parser Results:

```mezze
# ✅ Good: Handle errors
match parse_expr {tokens} is
    Ok {expr} -> use_expr {expr}
    Err {error} -> compile_error {msg = error}

# ❌ Bad: Ignore errors
let expr = parse_expr {tokens}  # Type: Result Expression String
use_expr {expr}  # Error: expected Expression, got Result!
```

## Type Safety

Generated code is type-checked:

```mezze
list!(1, "two", 3)

# Error: Type mismatch in list
#   Expected: List Int
#   Found: String at position 2
#
#   Original call:
#     --> example.mz:1:9
#    |
#  1 | list!(1, "two", 3)
#    |         ^^^^^ String here
#
#   Generated code:
#     list::append {xs = ..., x = "two"}  # ERROR: expected Int
```

## Best Practices

1. **Always handle errors** - Use `compile_error` for clear messages
2. **Parse incrementally** - Use combinators to build complex patterns
3. **Validate input** - Check argument counts, syntax
4. **Use qualified names** - `list::append`, not `append`
5. **Trust hygiene** - Don't try to manually generate unique names
6. **Test thoroughly** - Empty, single, multiple item cases

## Common Patterns

### Comma-separated list

```mezze
match parse_separated {tokens, separator = ","} is
    Ok {items} -> items |> list::map {list = @, f = {item ->
        parse_expr {tokens = item}
    }}
    Err {error} -> compile_error {msg = error}
```

### Key-value pairs

```mezze
let pairs = items |> list::map {list = @, f = {item ->
    match parse_pair {tokens = item, separator = "=>"} is
        Ok {left, right} ->
            {key = parse_expr {tokens = left}, value = parse_expr {tokens = right}}
        Err {error} -> compile_error {msg = error}
}}
```

### Conditional expansion

```mezze
match parse_separated {tokens, separator = ","} is
    Ok {items} when list::length {list = items} == 2 ->
        # Handle 2 arguments
    Ok {items} when list::length {list = items} == 3 ->
        # Handle 3 arguments
    _ -> compile_error {msg = "Expected 2 or 3 arguments"}
```

## Summary

Pattern macros are:
- ✅ Functions with `{tokens: TokenStream} -> 'result` signature
- ✅ Declared with `macro` keyword
- ✅ Invoked with `!()` syntax
- ✅ Executed during type-checking
- ✅ Automatically hygienic
- ✅ Fully type-checked

They provide a clean, type-safe way to do syntax transformations without special macro languages or syntax.
