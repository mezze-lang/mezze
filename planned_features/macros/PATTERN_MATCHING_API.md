# Pattern Matching API for Macros

This document describes the pattern matching and parsing helpers available for writing macros in Mezze. These helpers provide declarative, composable patterns on top of the low-level `TokenStream` API.

## Philosophy

**Two levels of abstraction:**
1. **Low-level**: Direct `TokenStream` manipulation (maximum power)
2. **High-level**: Pattern matching helpers (ease of use)

You can mix and match both approaches in the same macro!

## Module Structure

```
std::macro::patterns
├── Core patterns         # Common parsing patterns
├── Combinators          # Compose parsers together
├── Token utilities      # Work with individual tokens
└── Error helpers        # Better error messages
```

---

## Core Pattern Functions

### `comma_separated`

Parse comma-separated expressions.

**Signature:**
```mezze
pub let comma_separated: {
    tokens: TokenStream
} -> Result {items: [Expr 'a]} String
```

**Examples:**
```mezze
# Input: 1, 2, 3
comma_separated {tokens}
# => Ok {items = [int_lit(1), int_lit(2), int_lit(3)]}

# Input: foo, bar, baz
comma_separated {tokens}
# => Ok {items = [identifier("foo"), identifier("bar"), identifier("baz")]}

# Input: (empty)
comma_separated {tokens}
# => Ok {items = []}

# Input: 1 2 3  (no commas)
comma_separated {tokens}
# => Err {error = "Expected comma after expression"}
```

**Common usage:**
```mezze
macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    match comma_separated {tokens} is
        Ok {items} when list::is_empty {list = items} ->
            qualified {name = "list::empty"}
        Ok {items} ->
            list_literal {elements = items}
        Err {error} ->
            compile_error {msg = error}
```

---

### `separated_by`

Parse items separated by a custom separator.

**Signature:**
```mezze
pub let separated_by: {
    tokens: TokenStream,
    separator: String,       # "=>", ":", ";", "|", etc.
    item_parser: {TokenStream} -> Result 'a String
} -> Result {items: ['a]} String
```

**Examples:**
```mezze
# Input: a | b | c
separated_by {
    tokens,
    separator = "|",
    item_parser = parse_expr
}
# => Ok {items = [identifier("a"), identifier("b"), identifier("c")]}

# Input: foo : Int ; bar : String
separated_by {
    tokens,
    separator = ";",
    item_parser = parse_type_binding
}
# => Ok {items = [binding("foo", Int), binding("bar", String)]}
```

**Common usage:**
```mezze
macro match_patterns: {tokens: TokenStream} -> Expr 'a = {tokens} ->
    # Parse: pattern | pattern | pattern
    match separated_by {tokens, separator = "|", item_parser = parse_pattern} is
        Ok {patterns} -> build_match {patterns}
        Err {error} -> compile_error {msg = error}
```

---

### `key_value_pairs`

Parse key-value pairs with a separator.

**Signature:**
```mezze
pub let key_value_pairs: {
    tokens: TokenStream,
    separator: String       # "=>", ":", "=", etc.
} -> Result {pairs: [{key: Expr 'k, value: Expr 'v}]} String
```

**Examples:**
```mezze
# Input: "foo" => 1, "bar" => 2
key_value_pairs {tokens, separator = "=>"}
# => Ok {pairs = [
#     {key = string_lit("foo"), value = int_lit(1)},
#     {key = string_lit("bar"), value = int_lit(2)}
# ]}

# Input: name: "Alice", age: 30
key_value_pairs {tokens, separator = ":"}
# => Ok {pairs = [
#     {key = identifier("name"), value = string_lit("Alice")},
#     {key = identifier("age"), value = int_lit(30)}
# ]}

# Input: x = 1, y = 2
key_value_pairs {tokens, separator = "="}
# => Ok {pairs = [
#     {key = identifier("x"), value = int_lit(1)},
#     {key = identifier("y"), value = int_lit(2)}
# ]}
```

**Common usage:**
```mezze
macro map: {tokens: TokenStream} -> Expr (Map 'k 'v) = {tokens} ->
    match key_value_pairs {tokens, separator = "=>"} is
        Ok {pairs} ->
            let pair_list = pairs |> list::map {list = @, f = {p} ->
                tuple {elements = [p.key, p.value]}
            }
            call {
                fn = qualified {name = "map::from_list"},
                args = record {fields = [{"list", list_literal {elements = pair_list}}]}
            }
        Err {error} ->
            compile_error {msg = error}
```

---

### `delimited`

Parse content between delimiters.

**Signature:**
```mezze
pub let delimited: {
    tokens: TokenStream,
    open: String,    # "(", "[", "{", "<"
    close: String    # ")", "]", "}", ">"
} -> Result {
    inner: TokenStream,
    remaining: TokenStream
} String
```

**Examples:**
```mezze
# Input: (1, 2, 3) rest
delimited {tokens, open = "(", close = ")"}
# => Ok {
#     inner = TokenStream("1, 2, 3"),
#     remaining = TokenStream("rest")
# }

# Input: [foo, bar] baz
delimited {tokens, open = "[", close = "]"}
# => Ok {
#     inner = TokenStream("foo, bar"),
#     remaining = TokenStream("baz")
# }

# Input: {x: Int} -> String
delimited {tokens, open = "{", close = "}"}
# => Ok {
#     inner = TokenStream("x: Int"),
#     remaining = TokenStream("-> String")
# }

# Input: (unclosed
delimited {tokens, open = "(", close = ")"}
# => Err {error = "Unclosed delimiter '(', expected ')'"}
```

**Common usage:**
```mezze
macro tuple: {tokens: TokenStream} -> Expr (Tuple 'a 'b) = {tokens} ->
    match delimited {tokens, open = "(", close = ")"} is
        Ok {inner, remaining} ->
            # Parse the content inside parentheses
            match comma_separated {inner} is
                Ok {items} -> tuple {elements = items}
                Err {error} -> compile_error {msg = error}
        Err {error} ->
            compile_error {msg = error}
```

---

### `parse_string_literal`

Parse a string literal with interpolations.

**Signature:**
```mezze
pub let parse_string_literal: {
    tokens: TokenStream
} -> Result {
    template: String,
    interpolations: [{name: String, expr: Expr 'a, position: Int}]
} String
```

**Examples:**
```mezze
# Input: "hello world"
parse_string_literal {tokens}
# => Ok {
#     template = "hello world",
#     interpolations = []
# }

# Input: "Hello, $(name)!"
parse_string_literal {tokens}
# => Ok {
#     template = "Hello, $(name)!",
#     interpolations = [{
#         name = "name",
#         expr = identifier("name"),
#         position = 7
#     }]
# }

# Input: "$(x) + $(y) = $(z)"
parse_string_literal {tokens}
# => Ok {
#     template = "$(x) + $(y) = $(z)",
#     interpolations = [
#         {name = "x", expr = identifier("x"), position = 0},
#         {name = "y", expr = identifier("y"), position = 7},
#         {name = "z", expr = identifier("z"), position = 14}
#     ]
# }

# Input: 42  (not a string)
parse_string_literal {tokens}
# => Err {error = "Expected string literal, found Int"}
```

**Common usage:**
```mezze
macro sql: {tokens: TokenStream} -> Expr (SqlQuery <>) = {tokens} ->
    match parse_string_literal {tokens} is
        Ok {template, interpolations} ->
            # Validate SQL syntax
            match validate_sql {sql = template} is
                Ok {} -> build_parameterized_query {template, interpolations}
                Err {error} -> compile_error {msg = "Invalid SQL: $(error)"}
        Err {error} ->
            compile_error {msg = error}
```

---

### `parse_expr`

Parse a single expression.

**Signature:**
```mezze
pub let parse_expr: {
    tokens: TokenStream
} -> Result {expr: Expr 'a, remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: 42 + x
parse_expr {tokens}
# => Ok {
#     expr = binary_op(int_lit(42), Plus, identifier("x")),
#     remaining = TokenStream("")
# }

# Input: foo(1, 2)
parse_expr {tokens}
# => Ok {
#     expr = call(identifier("foo"), [int_lit(1), int_lit(2)]),
#     remaining = TokenStream("")
# }

# Input: {x, y} -> x + y
parse_expr {tokens}
# => Ok {
#     expr = lambda([x, y], binary_op(...)),
#     remaining = TokenStream("")
# }
```

**Common usage:**
```mezze
macro dbg: {tokens: TokenStream} -> Expr 'a = {tokens} ->
    match parse_expr {tokens} is
        Ok {expr, _} ->
            # Generate: do
            #   let __tmp = expr
            #   print("Debug: $(__tmp)")
            #   __tmp
            do_expr {
                statements = [
                    let_stmt {name = "__tmp", value = expr},
                    print_stmt {value = identifier("__tmp")}
                ],
                result = identifier("__tmp"}
            }
        Err {error} ->
            compile_error {msg = error}
```

---

### `parse_identifier`

Parse an identifier token.

**Signature:**
```mezze
pub let parse_identifier: {
    tokens: TokenStream
} -> Result {name: String, remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: foo bar
parse_identifier {tokens}
# => Ok {name = "foo", remaining = TokenStream("bar")}

# Input: _private_var
parse_identifier {tokens}
# => Ok {name = "_private_var", remaining = TokenStream("")}

# Input: 123  (not an identifier)
parse_identifier {tokens}
# => Err {error = "Expected identifier, found Int"}
```

---

### `parse_type`

Parse a type annotation.

**Signature:**
```mezze
pub let parse_type: {
    tokens: TokenStream
} -> Result {type: TypeAnnotation, remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: Int
parse_type {tokens}
# => Ok {type = IdentifierType("Int"), remaining = TokenStream("")}

# Input: List Int
parse_type {tokens}
# => Ok {
#     type = TypeApplication("List", [IdentifierType("Int")]),
#     remaining = TokenStream("")
# }

# Input: {x: Int, y: Int}
parse_type {tokens}
# => Ok {
#     type = RecordType([("x", Int), ("y", Int)]),
#     remaining = TokenStream("")
# }

# Input: Int <Log>
parse_type {tokens}
# => Ok {
#     type = SuspendType(Int, [Log]),
#     remaining = TokenStream("")
# }
```

---

## Parser Combinators

### `optional`

Try to parse something, return `None` if it fails.

**Signature:**
```mezze
pub let optional: {
    tokens: TokenStream,
    parser: {TokenStream} -> Result 'a String
} -> Result {value: Option 'a, remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: : Int (has type annotation)
optional {tokens, parser = parse_type_annotation}
# => Ok {value = Some(Int), remaining = TokenStream("")}

# Input: (no type annotation)
optional {tokens, parser = parse_type_annotation}
# => Ok {value = None, remaining = TokenStream("")}
```

**Common usage:**
```mezze
macro let_binding: {tokens: TokenStream} -> Expr {} = {tokens} ->
    # Parse: name [: Type] = expr
    match parse_identifier {tokens} is
        Ok {name, remaining} ->
            # Optional type annotation
            match optional {remaining, parser = parse_type_annotation} is
                Ok {value = Some {type_ann}, remaining} ->
                    # Has type annotation
                    ...
                Ok {value = None, remaining} ->
                    # No type annotation
                    ...
```

---

### `repeated`

Parse zero or more occurrences of a pattern.

**Signature:**
```mezze
pub let repeated: {
    tokens: TokenStream,
    item_parser: {TokenStream} -> Result 'a String,
    separator: Option String  # Optional separator between items
} -> Result {items: ['a], remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: 1 2 3 4 5
repeated {tokens, item_parser = parse_int, separator = None}
# => Ok {items = [1, 2, 3, 4, 5], remaining = TokenStream("")}

# Input: a, b, c
repeated {tokens, item_parser = parse_identifier, separator = Some(",")]
# => Ok {items = ["a", "b", "c"], remaining = TokenStream("")}

# Input: (empty)
repeated {tokens, item_parser = parse_expr, separator = None}
# => Ok {items = [], remaining = TokenStream("")}
```

**Common usage:**
```mezze
macro variadic: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    # Parse any number of expressions
    match repeated {tokens, item_parser = parse_expr, separator = None} is
        Ok {items, _} -> list_literal {elements = items}
        Err {error} -> compile_error {msg = error}
```

---

### `repeated1`

Parse one or more occurrences (at least one required).

**Signature:**
```mezze
pub let repeated1: {
    tokens: TokenStream,
    item_parser: {TokenStream} -> Result 'a String,
    separator: Option String
} -> Result {items: ['a], remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: 1 2 3
repeated1 {tokens, item_parser = parse_int, separator = None}
# => Ok {items = [1, 2, 3], remaining = TokenStream("")}

# Input: (empty)
repeated1 {tokens, item_parser = parse_int, separator = None}
# => Err {error = "Expected at least one item"}
```

---

### `sequence`

Parse a sequence of patterns in order.

**Signature:**
```mezze
pub let sequence: {
    tokens: TokenStream,
    parsers: [{TokenStream} -> Result 'a String]
} -> Result {results: ['a], remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: foo = 42
sequence {
    tokens,
    parsers = [
        parse_identifier,    # "foo"
        {t} -> expect_token {t, text = "="},
        parse_expr           # 42
    ]
}
# => Ok {
#     results = ["foo", "=", int_lit(42)],
#     remaining = TokenStream("")
# }

# Input: (x: Int, y: Int)
sequence {
    tokens,
    parsers = [
        {t} -> expect_token {t, text = "("},
        parse_param_list,
        {t} -> expect_token {t, text = ")"}
    ]
}
```

**Common usage:**
```mezze
macro field_access: {tokens: TokenStream} -> Expr 'a = {tokens} ->
    # Parse: expr.field
    match sequence {tokens, parsers = [
        parse_expr,
        {t} -> expect_token {t, text = "."},
        parse_identifier
    ]} is
        Ok {results, _} ->
            let object = results[0]
            let field = results[2]
            member_access {object, field}
        Err {error} ->
            compile_error {msg = error}
```

---

### `try_parsers`

Try multiple parsers in order, return first success.

**Signature:**
```mezze
pub let try_parsers: {
    tokens: TokenStream,
    parsers: [{TokenStream} -> Result 'a String]
} -> Result 'a String
```

**Examples:**
```mezze
# Input: 42 (try int then string)
try_parsers {
    tokens,
    parsers = [
        parse_int,      # Success!
        parse_string
    ]
}
# => Ok {value = 42}

# Input: "hello" (int fails, string succeeds)
try_parsers {
    tokens,
    parsers = [
        parse_int,      # Fails
        parse_string    # Success!
    ]
}
# => Ok {value = "hello"}

# Input: true (both fail)
try_parsers {
    tokens,
    parsers = [
        parse_int,
        parse_string
    ]
}
# => Err {error = "All parsers failed"}
```

**Common usage:**
```mezze
macro literal: {tokens: TokenStream} -> Expr 'a = {tokens} ->
    # Try different literal types
    try_parsers {
        tokens,
        parsers = [
            {t} -> parse_int {t} |> map_result {result = @, f = int_lit},
            {t} -> parse_string {t} |> map_result {result = @, f = string_lit},
            {t} -> parse_bool {t} |> map_result {result = @, f = bool_lit}
        ]
    } |> match @ is
        Ok {lit} -> lit
        Err {error} -> compile_error {msg = "Expected literal value"}
```

---

### `map_result`

Transform a successful parse result.

**Signature:**
```mezze
pub let map_result: {
    result: Result 'a String,
    f: {'a} -> 'b
} -> Result 'b String
```

**Examples:**
```mezze
# Transform integer to AST node
parse_int {tokens}
|> map_result {result = @, f = {n} -> int_lit {value = n}}

# Extract field from record result
key_value_pairs {tokens, separator = "=>"}
|> map_result {result = @, f = {pairs} ->
    pairs |> list::map {list = @, f = {p} -> p.key}
}
```

---

### `flat_map_result`

Transform a result and flatten (for chaining parsers).

**Signature:**
```mezze
pub let flat_map_result: {
    result: Result 'a String,
    f: {'a} -> Result 'b String
} -> Result 'b String
```

**Examples:**
```mezze
# Parse identifier, then parse type annotation
parse_identifier {tokens}
|> flat_map_result {result = @, f = {name, remaining} ->
    parse_type {remaining}
    |> map_result {result = @, f = {type, _} ->
        {name, type}
    }
}
```

---

## Token Utilities

### `peek_token`

Look at the next token without consuming it.

**Signature:**
```mezze
pub let peek_token: {
    tokens: TokenStream
} -> Option Token
```

**Examples:**
```mezze
# Input: foo bar
peek_token {tokens}
# => Some(Token{type = Identifier, text = "foo", ...})

# Input: (empty)
peek_token {tokens}
# => None
```

---

### `next_token`

Get the next token and advance the stream.

**Signature:**
```mezze
pub let next_token: {
    tokens: TokenStream
} -> Result {token: Token, remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: foo bar
next_token {tokens}
# => Ok {
#     token = Token{type = Identifier, text = "foo"},
#     remaining = TokenStream("bar")
# }

# Input: (empty)
next_token {tokens}
# => Err {error = "Unexpected end of input"}
```

---

### `expect_token`

Expect a specific token type or text.

**Signature:**
```mezze
pub let expect_token: {
    tokens: TokenStream,
    type: Option TokenType,    # Expected token type
    text: Option String        # Expected token text
} -> Result {token: Token, remaining: TokenStream} String
```

**Examples:**
```mezze
# Input: => rest (expect fat arrow)
expect_token {tokens, type = None, text = Some("=>")}
# => Ok {token = FatArrow, remaining = TokenStream("rest")}

# Input: 42 rest (expect identifier)
expect_token {tokens, type = Some(Identifier), text = None}
# => Err {error = "Expected Identifier, found Int"}

# Input: foo bar (expect "baz")
expect_token {tokens, type = None, text = Some("baz")}
# => Err {error = "Expected 'baz', found 'foo'"}
```

**Common usage:**
```mezze
macro arrow_function: {tokens: TokenStream} -> Expr ({} -> 'a) = {tokens} ->
    # Parse: params -> body
    match parse_params {tokens} is
        Ok {params, remaining} ->
            # Expect arrow
            match expect_token {remaining, text = Some("->")} is
                Ok {_, remaining} ->
                    match parse_expr {remaining} is
                        Ok {body, _} ->
                            lambda {params, body}
```

---

### `skip_whitespace`

Skip whitespace and newline tokens.

**Signature:**
```mezze
pub let skip_whitespace: {
    tokens: TokenStream
} -> TokenStream
```

**Examples:**
```mezze
# Input: "   \n  foo"
skip_whitespace {tokens}
# => TokenStream("foo")

# Input: "foo"
skip_whitespace {tokens}
# => TokenStream("foo")  (unchanged)
```

---

### `is_empty`

Check if token stream is empty.

**Signature:**
```mezze
pub let is_empty: {
    tokens: TokenStream
} -> Bool
```

**Examples:**
```mezze
is_empty {tokens}  # => True or False
```

---

### `token_count`

Count remaining tokens.

**Signature:**
```mezze
pub let token_count: {
    tokens: TokenStream
} -> Int
```

---

## Error Helpers

### `compile_error`

Generate a compile-time error.

**Signature:**
```mezze
pub let compile_error: {
    msg: String,
    location: Option SourceLocation
} -> never  # Never returns!
```

**Examples:**
```mezze
# Simple error
compile_error {msg = "Invalid syntax"}

# Error with location
compile_error {
    msg = "Expected identifier",
    location = Some(tokens.current_location {})
}
```

---

### `format_error`

Format a helpful error message.

**Signature:**
```mezze
pub let format_error: {
    msg: String,
    tokens: TokenStream,
    expected: String,
    hint: Option String
} -> String
```

**Examples:**
```mezze
format_error {
    msg = "Invalid map syntax",
    tokens,
    expected = "key => value pairs separated by commas",
    hint = Some("Example: map!(\"a\" => 1, \"b\" => 2)")
}
# => "Invalid map syntax
#     Expected: key => value pairs separated by commas
#     Found: ...
#     Hint: Example: map!(\"a\" => 1, \"b\" => 2)"
```

---

## Complete Examples

### Example 1: List Macro

```mezze
use std::macro::patterns::{comma_separated, compile_error}

macro list: {tokens: TokenStream} -> Expr (List 'a) = {tokens} ->
    match comma_separated {tokens} is
        Ok {items} when list::is_empty {list = items} ->
            qualified {name = "list::empty"}
        Ok {items} ->
            list_literal {elements = items}
        Err {error} ->
            compile_error {msg = error, location = None}

# Usage:
list!(1, 2, 3)
list!(foo, bar, baz)
list!()
```

### Example 2: Map Macro

```mezze
use std::macro::patterns::{key_value_pairs, compile_error}

macro map: {tokens: TokenStream} -> Expr (Map 'k 'v) = {tokens} ->
    match key_value_pairs {tokens, separator = "=>"} is
        Ok {pairs} ->
            let pair_list = pairs |> list::map {list = @, f = {p} ->
                tuple {elements = [p.key, p.value]}
            }
            call {
                fn = qualified {name = "map::from_list"},
                args = record {fields = [
                    {"list", list_literal {elements = pair_list}}
                ]}
            }
        Err {error} ->
            compile_error {
                msg = format_error {
                    msg = error,
                    tokens,
                    expected = "key => value pairs",
                    hint = Some("Example: map!(\"a\" => 1, \"b\" => 2)")
                },
                location = None
            }

# Usage:
map!("foo" => 1, "bar" => 2)
map!(x => 10, y => 20)
```

### Example 3: Assert Macro (Multiple Patterns)

```mezze
use std::macro::patterns::{
    parse_expr, expect_token, try_parsers,
    sequence, compile_error
}

macro assert: {tokens: TokenStream} -> Expr {} = {tokens} ->
    # Try two patterns: with and without message
    try_parsers {
        tokens,
        parsers = [
            # Pattern 1: assert!(expr)
            {t} -> parse_expr {t} |> map_result {result = @, f = {expr, _} ->
                OneArg {expr}
            },

            # Pattern 2: assert!(expr, message)
            {t} -> sequence {
                t,
                parsers = [
                    parse_expr,
                    {t} -> expect_token {t, text = Some(",")},
                    parse_expr
                ]
            } |> map_result {result = @, f = {results, _} ->
                TwoArgs {expr = results[0], msg = results[2]}
            }
        ]
    } |> match @ is
        Ok {OneArg {expr}} ->
            # Generate: if not expr then panic!()
            if_expr {
                condition = unary_op {op = Not, expr},
                then_branch = call {
                    fn = qualified {name = "panic"},
                    args = record {fields = []}
                },
                else_branch = record {fields = []}
            }

        Ok {TwoArgs {expr, msg}} ->
            # Generate: if not expr then panic!(msg)
            if_expr {
                condition = unary_op {op = Not, expr},
                then_branch = call {
                    fn = qualified {name = "panic"},
                    args = record {fields = [{"msg", msg}]}
                },
                else_branch = record {fields = []}
            }

        Err {error} ->
            compile_error {
                msg = format_error {
                    msg = error,
                    tokens,
                    expected = "expression or expression with message",
                    hint = Some("assert!(x > 0) or assert!(x > 0, \"x must be positive\")")
                },
                location = None
            }

# Usage:
assert!(x > 0)
assert!(x > 0, "x must be positive")
```

### Example 4: SQL Macro (String Parsing)

```mezze
use std::macro::patterns::{parse_string_literal, compile_error}
use std::sql::{validate_sql, build_parameterized_query}

macro sql: {tokens: TokenStream} -> Expr (SqlQuery <>) = {tokens} ->
    match parse_string_literal {tokens} is
        Ok {template, interpolations} ->
            # Validate SQL syntax
            match validate_sql {sql = template} is
                Err {error} ->
                    compile_error {
                        msg = "Invalid SQL: $(error)",
                        location = None
                    }
                Ok {} -> {}

            # Check interpolations are safe
            interpolations |> list::iter {list = @, f = {interp} ->
                match is_sql_safe_type {expr = interp.expr} is
                    False -> compile_error {
                        msg = "Unsafe interpolation type for SQL parameter: $(interp.name)",
                        location = Some(interp.expr.location)
                    }
                    True -> {}
            }

            # Build parameterized query
            build_parameterized_query {template, interpolations}

        Err {error} ->
            compile_error {
                msg = format_error {
                    msg = error,
                    tokens,
                    expected = "string literal",
                    hint = Some("sql!(\"SELECT * FROM users WHERE id = $(id)\")")
                },
                location = None
            }

# Usage:
sql!("SELECT * FROM users WHERE id = $(user_id)")
sql!("INSERT INTO products (name, price) VALUES ($(name), $(price))")
```

### Example 5: Range Macro (Custom Syntax)

```mezze
use std::macro::patterns::{
    parse_expr, expect_token, peek_token,
    optional, compile_error
}

macro range: {tokens: TokenStream} -> Expr (Range Int) = {tokens} ->
    # Parse: start..end or start..=end
    match parse_expr {tokens} is
        Ok {start, remaining} ->
            # Expect ".."
            match expect_token {remaining, text = Some("..")} is
                Ok {_, remaining} ->
                    # Check for "=" (inclusive range)
                    match peek_token {remaining} is
                        Some {token} when token.text == "=" ->
                            let remaining = remaining.advance {}
                            let inclusive = True

                            # Parse end
                            match parse_expr {remaining} is
                                Ok {end, _} ->
                                    call {
                                        fn = qualified {name = "range::new_inclusive"},
                                        args = record {fields = [
                                            {"start", start},
                                            {"end", end}
                                        ]}
                                    }
                                Err {error} ->
                                    compile_error {msg = error, location = None}

                        _ ->
                            # Exclusive range
                            match parse_expr {remaining} is
                                Ok {end, _} ->
                                    call {
                                        fn = qualified {name = "range::new"},
                                        args = record {fields = [
                                            {"start", start},
                                            {"end", end}
                                        ]}
                                    }
                                Err {error} ->
                                    compile_error {msg = error, location = None}

                Err {error} ->
                    compile_error {
                        msg = format_error {
                            msg = error,
                            tokens,
                            expected = "..",
                            hint = Some("range!(1..10) or range!(1..=10)")
                        },
                        location = None
                    }

        Err {error} ->
            compile_error {msg = error, location = None}

# Usage:
range!(1..10)    # Exclusive: 1, 2, ..., 9
range!(1..=10)   # Inclusive: 1, 2, ..., 10
```

---

## Best Practices

### 1. Use Pattern Helpers for Common Cases

```mezze
# ✅ Good: Use helpers
macro list: {tokens} -> Expr (List 'a) = {tokens} ->
    comma_separated {tokens}

# ❌ Avoid: Manual parsing when helpers exist
macro list: {tokens} -> Expr (List 'a) = {tokens} ->
    let items = []
    let current = tokens
    # ... manual loop ...
```

### 2. Provide Clear Error Messages

```mezze
# ✅ Good: Helpful error
compile_error {
    msg = format_error {
        msg = "Invalid syntax",
        tokens,
        expected = "comma-separated expressions",
        hint = Some("Example: list!(1, 2, 3)")
    },
    location = Some(tokens.current_location {})
}

# ❌ Bad: Vague error
compile_error {msg = "Error"}
```

### 3. Validate Early

```mezze
# ✅ Good: Validate immediately
match parse_string_literal {tokens} is
    Ok {template, _} ->
        match validate_sql {sql = template} is
            Err {error} -> compile_error {msg = error, location = None}
            Ok {} -> # continue...
```

### 4. Use Combinators for Complex Patterns

```mezze
# ✅ Good: Compose parsers
let parse_assignment = sequence {
    tokens,
    parsers = [
        parse_identifier,
        {t} -> expect_token {t, text = Some("=")},
        parse_expr
    ]
}
```

### 5. Test Edge Cases

```mezze
# Test empty input
list!()

# Test single item
list!(1)

# Test trailing comma
list!(1, 2, 3,)

# Test complex expressions
list!(foo(), bar {x = 1}, baz)
```

---

## Summary

The pattern matching API provides three levels:

1. **High-level patterns** - `comma_separated`, `key_value_pairs` (easiest)
2. **Combinators** - `optional`, `repeated`, `sequence` (flexible)
3. **Low-level tokens** - `peek_token`, `next_token` (full control)

**Choose the right level for your macro:**
- Simple list/map/etc → Use high-level patterns
- Custom syntax → Use combinators
- Complex DSL → Mix all three approaches

This gives you maximum flexibility while keeping common cases simple!
