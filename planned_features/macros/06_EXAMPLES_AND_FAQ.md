# Examples and FAQ

Complete examples of macro usage and answers to common questions.

## Complete Examples

### Example 1: List! Macro (Full Implementation)

**File: `stdlib/std/macro/builtins.mz`**

```mezze
use std::macro::pattern_macro::PatternMacro
use std::macro::core::{
    TokenStream, Expression,
    parse_separated, parse_expr,
    call, qualified, record, compile_error
}

pub impl PatternMacro for List having 'result = Expression where {
    expand = {tokens} ->
        match parse_separated {tokens, separator = ","} is
            Err {error} -> compile_error {msg = "List! syntax error: $(error)"}

            Ok {items} when list::is_empty {list = items} ->
                qualified {name = "list::empty"}

            Ok {items} -> do
                let exprs = items |> list::map {list = @, f = {item ->
                    match parse_expr {tokens = item} is
                        Ok {expr} -> expr
                        Err {error} -> compile_error {msg = "Invalid expression: $(error)"}
                }}

                list::fold_left {
                    list = exprs,
                    init = qualified {name = "list::empty"},
                    f = {acc, expr} ->
                        call {
                            fn = qualified {name = "list::append"},
                            args = record {fields = [
                                {"xs", acc},
                                {"x", expr}
                            ]}
                        }
                }
}
```

**Usage:**

```mezze
use std::macro::builtins::List

# Empty list
let empty = List!()
# Expands to: list::empty

# List with literals
let numbers = List!(1, 2, 3, 4, 5)
# Expands to:
# list::append {
#   xs = list::append {
#     xs = list::append {
#       xs = list::append {
#         xs = list::append {xs = list::empty, x = 1},
#         x = 2
#       },
#       x = 3
#     },
#     x = 4
#   },
#   x = 5
# }

# List with variables
let x = 10
let y = 20
let vars = List!(x, y, x + y)
# Variables maintain their scope - automatic hygiene!

# List with expressions
let computed = List!(
    compute_value {},
    get_user_input {},
    load_from_file {path = "data.txt"}
)
```

### Example 2: Map! Macro (Full Implementation)

```mezze
use std::macro::pattern_macro::PatternMacro
use std::macro::core::{
    TokenStream, Expression,
    parse_separated, parse_pair, parse_expr,
    call, qualified, record, compile_error
}

pub impl PatternMacro for Map having 'result = Expression where {
    expand = {tokens} ->
        match parse_separated {tokens, separator = ","} is
            Err {error} -> compile_error {msg = "Map! syntax error: $(error)"}

            Ok {items} when list::is_empty {list = items} ->
                call {
                    fn = qualified {name = "map::empty"},
                    args = record {fields = []}
                }

            Ok {items} -> do
                let pairs = items |> list::map {list = @, f = {item ->
                    match parse_pair {tokens = item, separator = "=>"} is
                        Err {error} -> compile_error {
                            msg = "Expected 'key => value', got: $(error)"
                        }
                        Ok {left, right} ->
                            match {parse_expr {tokens = left}, parse_expr {tokens = right}} is
                                {Ok {key}, Ok {value}} -> {key, value}
                                {Err {error}, _} -> compile_error {msg = "Invalid key: $(error)"}
                                {_, Err {error}} -> compile_error {msg = "Invalid value: $(error)"}
                }}

                list::fold_left {
                    list = pairs,
                    init = call {
                        fn = qualified {name = "map::empty"},
                        args = record {fields = []}
                    },
                    f = {acc, pair} ->
                        call {
                            fn = qualified {name = "map::insert"},
                            args = record {fields = [
                                {"map", acc},
                                {"key", pair.key},
                                {"value", pair.value}
                            ]}
                        }
                }
}
```

**Usage:**

```mezze
use std::macro::builtins::Map

# Empty map
let empty = Map!()

# Map with string keys
let config = Map!(
    "host" => "localhost",
    "port" => 8080,
    "timeout" => 30
)

# Map with computed values
let user_id = get_current_user {}
let data = Map!(
    "user" => user_id,
    "timestamp" => current_time {},
    "data" => load_data {}
)
```

### Example 3: AssertEq! Macro (Full Implementation)

```mezze
use std::macro::pattern_macro::PatternMacro
use std::macro::core::{
    TokenStream, Expression,
    parse_separated, parse_expr,
    call, qualified, record, identifier, string_lit,
    if_expr, do_expr, let_stmt, expr_stmt,
    meta_file, meta_line, stringify_expr,
    compile_error
}

pub impl PatternMacro for AssertEq having 'result = Expression where {
    expand = {tokens} ->
        match parse_separated {tokens, separator = ","} is
            Err {error} -> compile_error {msg = "AssertEq! syntax error: $(error)"}

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

            _ -> compile_error {
                msg = "AssertEq! expects 2 or 3 arguments (left, right, optional message)"
            }
}

let build_assertion = {left, right, msg} -> do
    # These are automatically hygienic!
    let left_var = "left"
    let right_var = "right"

    # Build error message
    let error_msg = match msg is
        Some {custom} -> custom
        None ->
            call {
                fn = qualified {name = "format"},
                args = record {fields = [
                    {"template", string_lit {
                        value = "Assertion failed at {}:{}: {} != {}"
                    }},
                    {"args", list_literal {elements = [
                        meta_file {},
                        meta_line {},
                        string_lit {value = stringify_expr {expr = left}},
                        string_lit {value = stringify_expr {expr = right}}
                    ]}}
                ]}
            }

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
                        args = record {fields = [{"msg", error_msg}]}
                    },
                    else_branch = record {fields = []}
                }
            }
        ],
        result = record {fields = []}
    }
```

**Usage:**

```mezze
use std::macro::builtins::AssertEq

let test_addition = {} -> do
    AssertEq!(1 + 1, 2)
    AssertEq!(2 + 2, 4)
    AssertEq!(10 - 5, 5, "subtraction should work")
    {}

# If assertion fails:
# panic: Assertion failed at test.mz:5: 10 - 5 != 4
```

### Example 4: Dbg! Macro (Full Implementation)

```mezze
use std::macro::pattern_macro::PatternMacro
use std::macro::core::{
    TokenStream, Expression,
    parse_expr,
    call, qualified, record, identifier, string_lit,
    do_expr, let_stmt, expr_stmt,
    meta_file, meta_line, stringify_expr,
    compile_error
}

pub impl PatternMacro for Dbg having 'result = Expression where {
    expand = {tokens} ->
        match parse_expr {tokens} is
            Err {error} -> compile_error {msg = "Dbg! syntax error: $(error)"}

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
                                            {"template", string_lit {
                                                value = "[{}:{}] {} = {:?}"
                                            }},
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
}
```

**Usage:**

```mezze
use std::macro::builtins::Dbg

let compute = {x} -> do
    let a = Dbg!(x + 1)
    # Output: [example.mz:4] x + 1 = 11

    let b = Dbg!(a * 2)
    # Output: [example.mz:7] a * 2 = 22

    Dbg!(a + b)
    # Output: [example.mz:10] a + b = 33
    # Returns: 33

compute {x = 10}  # Returns 33
```

### Example 5: Sql Template (Full Implementation)

```mezze
use std::macro::tagged_template::TaggedTemplate
use std::macro::core::{Expression, record, string_lit, list_literal, compile_error}

pub type SqlQuery = {
    query: String,
    params: [Expression]
}

pub impl TaggedTemplate for Sql having 'result = Expression where {
    expand = {content, interpolations} -> do
        # Validate SQL at compile-time
        match validate_sql_syntax {sql = content} is
            Err {error} -> compile_error {msg = "Invalid SQL: $(error)"}
            Ok _ -> {}

        # Replace ${var} with ? placeholders
        let param_names = interpolations |> list::map {list = @, f = {pair -> pair.0}}
        let param_exprs = interpolations |> list::map {list = @, f = {pair -> pair.1}}

        let query_str = replace_interpolations {content, names = param_names}

        # Return SqlQuery record
        record {fields = [
            {"query", string_lit {value = query_str}},
            {"params", list_literal {elements = param_exprs}}
        ]}
}

let validate_sql_syntax = {sql} ->
    # Simple validation - check for basic syntax
    if string::contains {str = sql, pattern = "SELECT"} |> not
    then if string::contains {str = sql, pattern = "INSERT"} |> not
    then if string::contains {str = sql, pattern = "UPDATE"} |> not
    then if string::contains {str = sql, pattern = "DELETE"} |> not
    then Err {error = "Not a valid SQL statement"}
    else Ok {}
    else Ok {}
    else Ok {}
    else Ok {}

let replace_interpolations = {content, names} ->
    names |> list::fold_left {
        list = @,
        init = content,
        f = {acc, name} ->
            string::replace {
                str = acc,
                pattern = "${$(name)}",
                replacement = "?"
            }
    }
```

**Usage:**

```mezze
use std::macro::builtins::Sql

let get_user = {id} -> do
    let query = Sql```SELECT name, email FROM users WHERE id = ${id}```

    # Type: {query: String, params: [Expression]}
    # Value: {query = "SELECT name, email FROM users WHERE id = ?", params = [id]}

    execute_query {query = query.query, params = query.params}

# SQL syntax validated at compile-time!
# Typo would be caught:
# let bad = Sql```SELCT name FROM users```
# Error: Invalid SQL: Not a valid SQL statement
```

### Example 6: CompileTime for Configuration

```mezze
use std::macro::comptime::CompileTime

# Load config at compile-time
let app_config = CompileTime.eval {code = {} ->
    match read_file {path = "config.json"} is
        Ok {content} ->
            match parse_json {json = content} is
                Ok {config} -> config
                Err {error} -> panic {msg = "Invalid config.json: $(error)"}
        Err {error} -> panic {msg = "Cannot read config.json: $(error)"}
}

# Use configuration (all values known at compile-time!)
let server_host = app_config.server.host    # "localhost"
let server_port = app_config.server.port    # 8080
let db_connection = app_config.database.url # "postgresql://..."

let main = {} -> do
    Log.info {msg = "Starting server on $(server_host):$(server_port)"}
    start_server {host = server_host, port = server_port}
```

## Frequently Asked Questions

### Q: How does automatic hygiene work?

**A:** The compiler tags every identifier with a syntax context showing where it came from:

```mezze
# User code:
let tmp = 5
DoTwice!(tmp)

# Macro generates:
let tmp = <captured>  # Uses the user's tmp
tmp                   # Uses the user's tmp

# But any NEW identifiers get expansion context:
let tmp = ...  # This tmp gets tagged as tmp#expansion_1
tmp            # References tmp#expansion_1, not user's tmp
```

During name resolution, `tmp#expansion_1` and `tmp#user_code` are treated as completely separate variables.

### Q: Can macros call other macros?

**A:** Yes! Macro-generated code can contain macro calls:

```mezze
impl PatternMacro for Vec having 'result = Expression where {
    expand = {tokens} ->
        # Generate a List! macro call
        MacroCall {
            macroName = "List",
            tokens = tokens,  # Pass through
            ...
        }
}

# Usage:
Vec!(1, 2, 3)  # Expands to List!(1, 2, 3), which then expands further
```

### Q: How do I debug macro expansion?

**A:** Use compiler flags to see expanded code:

```bash
mezze check --show-macro-expansion example.mz
```

Output shows before/after expansion:

```
Macro expansion: List!(1, 2, 3)
  ↓
list::append {
  xs = list::append {
    xs = list::append {xs = list::empty, x = 1},
    x = 2
  },
  x = 3
}
```

### Q: Can I use effects in macro code?

**A:** Yes, but only pure effects during compile-time execution. I/O effects need special compile-time handlers:

```mezze
# Pure effects work:
let result = CompileTime.eval {code = {} ->
    list::map {list = [1,2,3], f = {x -> x * 2}}
}

# I/O requires compile-time handler:
let content = CompileTime.eval {code = {} ->
    FileSystem.read {path = "data.txt"}  # Uses compile-time FileSystem handler
}
```

### Q: What happens if macro expansion fails?

**A:** Compilation stops with a clear error message:

```mezze
List!(1, 2, )  # Syntax error in macro input

# Error:
# Macro expansion error: List! syntax error: unexpected token ')'
#   --> example.mz:5:13
#    |
#  5 | List!(1, 2, )
#    |             ^ here
```

### Q: Can macros generate type definitions?

**A:** Not yet - macros currently only generate expressions and statements. Type definitions may be added in the future.

### Q: How do I make a macro that works with any separator?

**A:** Use `parse_one_of` to try multiple patterns:

```mezze
impl PatternMacro for Flexible having 'result = Expression where {
    expand = {tokens} ->
        parse_one_of {
            tokens,
            parsers = [
                {t} -> parse_separated {tokens = t, separator = ","},
                {t} -> parse_separated {tokens = t, separator = ";"},
                {t} -> parse_separated {tokens = t, separator = "|"}
            ]
        }
}

# All work:
Flexible!(a, b, c)
Flexible!(a; b; c)
Flexible!(a | b | c)
```

### Q: Can I use `!()` with any function for positional args?

**A:** Yes! The parser automatically desugars it:

```mezze
let add = {x, y} -> x + y

# These are equivalent:
add {x = 1, y = 2}
add!(1, 2)

# Parser sees add!(...), looks up 'add', finds it's a function,
# desugars to named args
```

### Q: What's the performance impact of macros?

**A:**
- **Compile-time:** Longer compilation (macro expansion happens during type checking)
- **Runtime:** Zero cost! Macros are fully expanded before code generation
- Generated code is the same as if you wrote it by hand

### Q: Can macros access types?

**A:** Not during expansion. Macros run before type checking, so type information isn't available yet. However, the *generated* code is type-checked normally.

### Q: How do I test macros?

**A:** Write tests that use the macro and verify the behavior:

```mezze
let test_list_macro = {} -> do
    let empty = List!()
    AssertEq!(list::length {list = empty}, 0)

    let nums = List!(1, 2, 3)
    AssertEq!(list::length {list = nums}, 3)
    AssertEq!(list::at {list = nums, index = 0}, 1)

    {}
```

You can also test the expansion directly (implementation detail):

```mezze
let test_list_expansion = {} -> do
    let tokens = tokenize "1, 2, 3"
    let expanded = List.expand {tokens}

    # Verify structure of expanded AST
    ...
```

### Q: Can I see the implementation of built-in macros?

**A:** Yes! They're in `stdlib/std/macro/builtins.mz`. They're normal Mezze code.

### Q: What's the difference between `!()` macros and ``` templates?

**A:**
- `!()` - Receives **unparsed tokens**, builds arbitrary AST
- ``` - Receives **string content + parsed interpolations**, usually builds specific types

Use `!()` for DSLs that look like Mezze code (List, Map).
Use ``` for DSLs with their own syntax (SQL, HTML).

### Q: Can I shadow a macro locally?

**A:** Yes, but it affects imports, not the macro itself:

```mezze
use std::macro::builtins::List

let List = my_custom_list_function  # Shadows the macro name

List!(1, 2, 3)  # Error: List is now a function, not a macro
```

Best practice: Don't shadow macro names.

### Q: Are there plans for procedural macros (like Rust)?

**A:** Pattern macros ARE procedural macros - they're functions that transform AST. The difference from Rust is we use builder functions instead of quote/unquote syntax.

### Q: Can macros be recursive?

**A:** Yes, but be careful of infinite expansion:

```mezze
impl PatternMacro for Repeat having 'result = Expression where {
    expand = {tokens} ->
        # Generates: Repeat!(tokens)  -- infinite loop!
        ...
}
```

The compiler may add recursion limits to prevent this.

### Q: Why not use s-expressions like Lisp?

**A:** Mezze already has syntax (records, lists, functions). Macros work with that syntax, not a special macro language. This makes macros feel like "more Mezze" rather than "a different language."

## Summary

The Mezze macro system provides:

✅ **Three macro types** - Pattern macros, tagged templates, compile-time execution
✅ **Automatic hygiene** - No manual gensym needed
✅ **Type safety** - All generated code is type-checked
✅ **Explicit** - Use normal Mezze code, not special syntax
✅ **Powerful** - Can generate arbitrary code
✅ **Safe** - Compile-time errors, not runtime surprises

Write macros like normal Mezze code, and they just work!
