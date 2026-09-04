# Standard Library API Reference

Complete API reference for the macro system standard library.

## Module: `std::macro::core`

Core types, parsers, and AST builders for macro implementations.

### Types

```mezze
# Token stream passed to macro expand functions
pub type TokenStream = __builtin_TokenStream

# AST node types (only valid during macro expansion)
pub type Expression = __builtin_Expression
pub type Statement = __builtin_Statement
pub type Pattern = __builtin_Pattern
```

### Parsing Functions

#### `parse_expr`

Parse a single expression from token stream.

```mezze
pub let parse_expr: {tokens: TokenStream} -> Result Expression String
```

**Example:**
```mezze
let expr = parse_expr {tokens}
match expr is
    Ok {value} -> value
    Err {error} -> compile_error {msg = error}
```

#### `parse_separated`

Parse items separated by a delimiter.

```mezze
pub let parse_separated: {
    tokens: TokenStream,
    separator: String  # ",", ";", ":", etc.
} -> Result [TokenStream] String
```

**Example:**
```mezze
# Parse "1, 2, 3" into ["1", "2", "3"]
match parse_separated {tokens, separator = ","} is
    Ok {items} -> items |> list::map {list = @, f = parse_expr}
    Err {error} -> compile_error {msg = error}
```

#### `parse_pair`

Parse pair pattern with separator.

```mezze
pub let parse_pair: {
    tokens: TokenStream,
    separator: String  # "=>", "->", ":", etc.
} -> Result {left: TokenStream, right: TokenStream} String
```

**Example:**
```mezze
# Parse "key => value" into {left = "key", right = "value"}
match parse_pair {tokens, separator = "=>"} is
    Ok {left, right} ->
        {key = parse_expr {tokens = left}, value = parse_expr {tokens = right}}
    Err {error} -> compile_error {msg = error}
```

#### `parse_one_of`

Try multiple parsers in order, return first success.

```mezze
pub let parse_one_of: {
    tokens: TokenStream,
    parsers: [{TokenStream} -> Result 'a String]
} -> Result 'a String
```

**Example:**
```mezze
parse_one_of {
    tokens,
    parsers = [
        {t} -> parse_pair {tokens = t, separator = "=>"},
        {t} -> parse_expr {tokens = t}
    ]
}
```

### AST Construction - Identifiers

#### `identifier`

Create simple identifier.

```mezze
pub let identifier: {name: String} -> Expression
```

**Example:**
```mezze
identifier {name = "myVar"}  # Creates identifier node
```

#### `qualified`

Create qualified identifier (module path).

```mezze
pub let qualified: {name: String} -> Expression
```

**Example:**
```mezze
qualified {name = "list::append"}  # Creates module::member identifier
```

### AST Construction - Literals

#### `string_lit`

Create string literal.

```mezze
pub let string_lit: {value: String} -> Expression
```

#### `int_lit`

Create integer literal.

```mezze
pub let int_lit: {value: Int} -> Expression
```

#### `float_lit`

Create floating-point literal.

```mezze
pub let float_lit: {value: Float} -> Expression
```

#### `bool_lit`

Create boolean literal.

```mezze
pub let bool_lit: {value: Bool} -> Expression
```

### AST Construction - Composite Expressions

#### `call`

Create function call.

```mezze
pub let call: {
    fn: Expression,
    args: Expression  # Usually a record
} -> Expression
```

**Example:**
```mezze
call {
    fn = qualified {name = "list::append"},
    args = record {fields = [
        {"xs", identifier {name = "myList"}},
        {"x", int_lit {value = 42}}
    ]}
}
```

#### `record`

Create record literal.

```mezze
pub let record: {
    fields: [(String, Expression)]
} -> Expression
```

**Example:**
```mezze
record {fields = [
    {"name", string_lit {value = "Alice"}},
    {"age", int_lit {value = 30}}
]}
```

#### `list_literal`

Create list literal.

```mezze
pub let list_literal: {
    elements: [Expression]
} -> Expression
```

**Example:**
```mezze
list_literal {elements = [
    int_lit {value = 1},
    int_lit {value = 2},
    int_lit {value = 3}
]}
```

#### `lambda`

Create lambda expression.

```mezze
pub let lambda: {
    params: [String],
    body: Expression
} -> Expression
```

**Example:**
```mezze
lambda {
    params = ["x", "y"],
    body = call {
        fn = qualified {name = "NumAdd::add"},
        args = record {fields = [
            {"l", identifier {name = "x"}},
            {"r", identifier {name = "y"}}
        ]}
    }
}
```

### AST Construction - Control Flow

#### `if_expr`

Create if expression.

```mezze
pub let if_expr: {
    condition: Expression,
    then_branch: Expression,
    else_branch: Expression
} -> Expression
```

**Example:**
```mezze
if_expr {
    condition = bool_lit {value = True},
    then_branch = int_lit {value = 1},
    else_branch = int_lit {value = 2}
}
```

#### `match_expr`

Create match expression.

```mezze
pub type MatchCase = {
    pattern: Pattern,
    guard: Option Expression,
    body: Expression
}

pub let match_expr: {
    scrutinee: Expression,
    cases: [MatchCase]
} -> Expression
```

#### `do_expr`

Create do expression (sequence of statements).

```mezze
pub let do_expr: {
    statements: [Statement],
    result: Expression
} -> Expression
```

**Example:**
```mezze
do_expr {
    statements = [
        let_stmt {name = "x", value = int_lit {value = 5}},
        expr_stmt {expr = call {fn = qualified {name = "print"}, args = ...}}
    ],
    result = identifier {name = "x"}
}
```

### AST Construction - Statements

#### `let_stmt`

Create let binding statement.

```mezze
pub let let_stmt: {
    name: String,
    value: Expression
} -> Statement
```

**Example:**
```mezze
let_stmt {
    name = "tmp",
    value = int_lit {value = 42}
}
```

#### `expr_stmt`

Create expression statement (side effect).

```mezze
pub let expr_stmt: {
    expr: Expression
} -> Statement
```

### Meta Functions

#### `meta_file`

Get current file name.

```mezze
pub let meta_file: {} -> Expression
```

Returns a string literal with the current file name.

#### `meta_line`

Get current line number.

```mezze
pub let meta_line: {} -> Expression
```

Returns an integer literal with the current line number.

#### `meta_column`

Get current column number.

```mezze
pub let meta_column: {} -> Expression
```

#### `stringify_expr`

Convert expression to string representation.

```mezze
pub let stringify_expr: {expr: Expression} -> String
```

**Example:**
```mezze
let expr = parse_expr {tokens}
let code_str = stringify_expr {expr}  # "x + 1"
```

### Compile-Time Errors

#### `compile_error`

Emit compile-time error.

```mezze
pub let compile_error: {msg: String} -> 'a
```

**Example:**
```mezze
if list::is_empty {list = items}
then compile_error {msg = "Expected at least one item"}
else ...
```

## Module: `std::macro::pattern_macro`

Pattern macro effect definition.

```mezze
use std::macro::core::{TokenStream, Expression}

pub effect PatternMacro having 'result where {
    expand: {tokens: TokenStream} -> 'result <PatternMacro>
}
```

**Usage:**
```mezze
use std::macro::pattern_macro::PatternMacro

pub impl PatternMacro for MyMacro having 'result = Expression where {
    expand = {tokens} -> ...
}
```

## Module: `std::macro::tagged_template`

Tagged template effect definition.

```mezze
use std::macro::core::Expression

pub effect TaggedTemplate having 'result where {
    expand: {
        content: String,
        interpolations: [(String, Expression)]
    } -> 'result <TaggedTemplate>
}
```

**Usage:**
```mezze
use std::macro::tagged_template::TaggedTemplate

pub impl TaggedTemplate for MyTemplate having 'result = Expression where {
    expand = {content, interpolations} -> ...
}
```

## Module: `std::macro::comptime`

Compile-time execution effect.

```mezze
pub effect CompileTime having 'result where {
    eval: {code: {} -> 'result} -> 'result <CompileTime>
}

# Default handler: execute immediately
pub impl CompileTime for Execute having 'result = 'a where {
    eval = {code} -> code {}
}
```

**Usage:**
```mezze
use std::macro::comptime::CompileTime

let value = CompileTime.eval {code = {} -> expensive_computation {}}
```

## Module: `std::macro::builtins`

Built-in macro implementations.

### List! Macro

```mezze
# Usage:
List!()           # Empty list
List!(1, 2, 3)    # List with elements

# Type: Expression (expands to list::append calls)
```

### Map! Macro

```mezze
# Usage:
Map!()                        # Empty map
Map!("x" => 5, "y" => 10)    # Map with entries

# Type: Expression (expands to map::insert calls)
```

### AssertEq! Macro

```mezze
# Usage:
AssertEq!(a, b)                 # Assert equal
AssertEq!(a, b, "custom msg")   # With custom message

# Type: Expression (expands to conditional panic)
```

### Dbg! Macro

```mezze
# Usage:
let x = Dbg!(compute_value {})  # Debug print and return value

# Prints: [file.mz:10] compute_value {} = 42
# Returns: 42

# Type: Expression (expands to Log.debug call)
```

## Helper Patterns

### Parse comma-separated list

```mezze
match parse_separated {tokens, separator = ","} is
    Err {error} -> compile_error {msg = error}
    Ok {items} when list::is_empty {list = items} ->
        # Handle empty case
        ...
    Ok {items} -> do
        let exprs = items |> list::map {list = @, f = {item ->
            parse_expr {tokens = item}
        }}
        # Use exprs...
```

### Parse key-value pairs

```mezze
match parse_separated {tokens, separator = ","} is
    Ok {items} -> do
        let pairs = items |> list::map {list = @, f = {item ->
            match parse_pair {tokens = item, separator = "=>"} is
                Ok {left, right} ->
                    {
                        key = parse_expr {tokens = left},
                        value = parse_expr {tokens = right}
                    }
                Err {error} -> compile_error {msg = error}
        }}
        # Use pairs...
```

### Build nested function calls

```mezze
list::fold_left {
    list = items,
    init = base_expr,
    f = {acc, item} ->
        call {
            fn = qualified {name = "module::function"},
            args = record {fields = [
                {"acc", acc},
                {"item", item}
            ]}
        }
}
```

### Conditional expansion

```mezze
match parse_separated {tokens, separator = ","} is
    Ok {items} when list::length {list = items} == 2 ->
        # Handle 2 arguments
        ...
    Ok {items} when list::length {list = items} == 3 ->
        # Handle 3 arguments
        ...
    _ -> compile_error {msg = "Expected 2 or 3 arguments"}
```

## Complete Example: Vec! Macro

```mezze
use std::macro::pattern_macro::PatternMacro
use std::macro::core::{
    TokenStream, Expression,
    parse_separated, parse_pair, parse_expr,
    call, qualified, record, compile_error
}

pub impl PatternMacro for Vec having 'result = Expression where {
    expand = {tokens} ->
        # Try parsing as "value; count" first
        match parse_pair {tokens, separator = ";"} is
            Ok {left, right} -> do
                let value = parse_expr {tokens = left}
                let count = parse_expr {tokens = right}

                call {
                    fn = qualified {name = "vec::repeat"},
                    args = record {fields = [
                        {"value", value},
                        {"count", count}
                    ]}
                }

            # Otherwise parse as comma-separated list
            Err _ ->
                match parse_separated {tokens, separator = ","} is
                    Err {error} -> compile_error {msg = error}

                    Ok {items} when list::is_empty {list = items} ->
                        call {
                            fn = qualified {name = "vec::new"},
                            args = record {fields = []}
                        }

                    Ok {items} -> do
                        let exprs = items |> list::map {list = @, f = {item ->
                            parse_expr {tokens = item}
                        }}

                        list::fold_left {
                            list = exprs,
                            init = call {
                                fn = qualified {name = "vec::new"},
                                args = record {fields = []}
                            },
                            f = {acc, expr} ->
                                call {
                                    fn = qualified {name = "vec::push"},
                                    args = record {fields = [
                                        {"vec", acc},
                                        {"value", expr}
                                    ]}
                                }
                        }
}
```

**Usage:**
```mezze
Vec!()           # Empty vector
Vec!(1, 2, 3)    # Vector with elements
Vec!(0; 10)      # Vector with 10 zeros
```

## Best Practices

1. **Always handle errors** - Use `compile_error` for clear messages
2. **Parse incrementally** - Use parser combinators to build complex patterns
3. **Validate input** - Check argument counts, types, syntax
4. **Use qualified names** - Avoid ambiguity with `module::function`
5. **Provide examples** - Document expected syntax in comments
6. **Trust hygiene** - Don't try to manually generate unique names
7. **Test thoroughly** - Test empty, single, and multiple item cases

## Common Pitfalls

1. **Forgetting to handle errors** - Always match on `Result` types
2. **Using unqualified names** - May be ambiguous or undefined
3. **Not validating input** - Check argument counts and syntax
4. **Returning wrong type** - `expand` must return `Expression` (or other 'result type)
5. **Infinite recursion** - Macros calling themselves recursively

## Performance Tips

1. **Minimize parsing** - Parse once, cache results
2. **Use fold for accumulation** - More efficient than repeated concatenation
3. **Avoid deep nesting** - Flatten when possible
4. **Reuse helper functions** - Don't duplicate parsing logic
5. **Keep expansions small** - Large generated code slows compilation

This completes the standard library API reference!
