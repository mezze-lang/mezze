# Standard Library API Reference

Complete reference for Mezze's macro system standard library.

## Module Organization

```
std/macro/
  ├── primitives.mz     # Parser combinators and AST builders
  ├── reflection.mz     # Type introspection functions
  └── builtins.mz       # Built-in macros (list, map, etc.)
```

## Parser Primitives

Located in `std/macro/primitives.mz`.

### Basic Parsers

#### parse_expr

Parse a single expression from tokens.

```mezze
parse_expr: {tokens: TokenStream} -> Result Expression String
```

**Example:**
```mezze
match parse_expr {tokens} is
    Ok {expr} -> use_expression {expr}
    Err {error} -> compile_error {msg = "Invalid expression: $(error)"}
```

#### parse_type

Parse a type annotation from tokens.

```mezze
parse_type: {tokens: TokenStream} -> Result Type String
```

**Example:**
```mezze
match parse_type {tokens} is
    Ok {type} -> validate_type {type}
    Err {error} -> compile_error {msg = "Invalid type: $(error)"}
```

#### parse_ident

Parse an identifier from tokens.

```mezze
parse_ident: {tokens: TokenStream} -> Result String String
```

**Example:**
```mezze
match parse_ident {tokens} is
    Ok {name} -> identifier {name}
    Err {error} -> compile_error {msg = "Expected identifier: $(error)"}
```

#### parse_string

Parse a string literal from tokens.

```mezze
parse_string: {tokens: TokenStream} -> Result String String
```

#### parse_int

Parse an integer literal from tokens.

```mezze
parse_int: {tokens: TokenStream} -> Result Int String
```

#### parse_float

Parse a floating-point literal from tokens.

```mezze
parse_float: {tokens: TokenStream} -> Result Float String
```

#### parse_bool

Parse a boolean literal from tokens.

```mezze
parse_bool: {tokens: TokenStream} -> Result Bool String
```

### Parser Combinators

#### parse_separated

Parse items separated by a delimiter.

```mezze
parse_separated: {
    tokens: TokenStream,
    separator: String
} -> Result [TokenStream] String
```

**Example:**
```mezze
# Parse comma-separated items
match parse_separated {tokens, separator = ","} is
    Ok {items} -> items |> list::map {list = @, f = {item ->
        parse_expr {tokens = item}
    }}
    Err {error} -> compile_error {msg = error}
```

**Supported separators:** `","`, `";"`, `":"`, `"|"`, `"&"`, etc.

#### parse_pair

Parse a key-value pair with a separator.

```mezze
parse_pair: {
    tokens: TokenStream,
    separator: String
} -> Result {left: TokenStream, right: TokenStream} String
```

**Example:**
```mezze
# Parse "key => value"
match parse_pair {tokens, separator = "=>"} is
    Ok {left, right} ->
        {
            key = parse_expr {tokens = left},
            value = parse_expr {tokens = right}
        }
    Err {error} -> compile_error {msg = error}
```

**Supported separators:** `"=>"`, `"->"`, `":"`, `"="`, etc.

#### parse_binop

Parse a binary operation.

```mezze
parse_binop: {
    tokens: TokenStream,
    op: String
} -> Result {left: Expression, right: Expression} String
```

**Example:**
```mezze
# Parse "x + y"
match parse_binop {tokens, op = "+"} is
    Ok {left, right} -> binary_op {op = "+", left, right}
    Err {error} -> compile_error {msg = error}
```

#### parse_repeat_pattern

Parse a repeat pattern like `value; count`.

```mezze
parse_repeat_pattern: {tokens: TokenStream} ->
    Result {value: Expression, count: Expression} String
```

**Example:**
```mezze
# Parse "0; 10" (repeat 0 ten times)
match parse_repeat_pattern {tokens} is
    Ok {value, count} -> build_repeat {value, count}
    Err {error} -> compile_error {msg = error}
```

#### parse_one_of

Try multiple parsers in order, return first success.

```mezze
parse_one_of: {
    tokens: TokenStream,
    parsers: [{TokenStream} -> Result 'a String]
} -> Result 'a String
```

**Example:**
```mezze
# Try parsing as int, then float, then string
match parse_one_of {tokens, parsers = [parse_int, parse_float, parse_string]} is
    Ok {value} -> handle_value {value}
    Err {error} -> compile_error {msg = "Expected number or string"}
```

#### parse_optional

Parse optional pattern (returns None if parsing fails).

```mezze
parse_optional: {
    tokens: TokenStream,
    parser: {TokenStream} -> Result 'a String
} -> Result (Option 'a) String
```

**Example:**
```mezze
# Parse optional type annotation
let type_annotation = match parse_optional {tokens, parser = parse_type} is
    Ok {Some {type}} -> type
    Ok {None} -> infer_type {}
    Err {error} -> compile_error {msg = error}
```

### Token Operations

#### match_token

Match a specific token.

```mezze
match_token: {tokens: TokenStream, token: String} ->
    Result {matched: String, rest: TokenStream} String
```

**Example:**
```mezze
# Match opening bracket
match match_token {tokens, token = "["} is
    Ok {matched, rest} -> parse_list_contents {tokens = rest}
    Err {error} -> compile_error {msg = "Expected '['"}
```

#### peek_token

Peek at the next token without consuming.

```mezze
peek_token: {tokens: TokenStream} -> Option String
```

**Example:**
```mezze
match peek_token {tokens} is
    Some {token} when token == ";" -> parse_statement {tokens}
    Some {token} -> parse_expression {tokens}
    None -> compile_error {msg = "Unexpected end of input"}
```

#### consume_while

Consume tokens while predicate holds.

```mezze
consume_while: {
    tokens: TokenStream,
    predicate: {String} -> Bool
} -> {consumed: [String], rest: TokenStream}
```

**Example:**
```mezze
# Consume all digits
let {consumed, rest} = consume_while {
    tokens,
    predicate = {token} -> is_digit {token}
}
```

## AST Builders

Located in `std/macro/primitives.mz`.

### Expressions

#### identifier

Create an identifier expression.

```mezze
identifier: {name: String} -> Expression
```

**Example:**
```mezze
identifier {name = "x"}  # Variable 'x'
```

#### qualified

Create a qualified name (module path).

```mezze
qualified: {name: String} -> Expression
```

**Example:**
```mezze
qualified {name = "list::append"}  # list::append
qualified {name = "std::io::print"}  # std::io::print
```

#### string_lit

Create a string literal.

```mezze
string_lit: {value: String} -> Expression
```

#### int_lit

Create an integer literal.

```mezze
int_lit: {value: Int} -> Expression
```

#### float_lit

Create a floating-point literal.

```mezze
float_lit: {value: Float} -> Expression
```

#### bool_lit

Create a boolean literal.

```mezze
bool_lit: {value: Bool} -> Expression
```

#### list_literal

Create a list literal.

```mezze
list_literal: {elements: [Expression]} -> Expression
```

**Example:**
```mezze
list_literal {elements = [
    int_lit {value = 1},
    int_lit {value = 2},
    int_lit {value = 3}
]}
# Generates: [1, 2, 3]
```

#### call

Create a function call.

```mezze
call: {fn: Expression, args: Expression} -> Expression
```

**Example:**
```mezze
call {
    fn = qualified {name = "list::append"},
    args = record {fields = [
        {"xs", identifier {name = "list"}},
        {"x", int_lit {value = 42}}
    ]}
}
# Generates: list::append {xs = list, x = 42}
```

#### record

Create a record literal.

```mezze
record: {fields: [(String, Expression)]} -> Expression
```

**Example:**
```mezze
record {fields = [
    {"x", int_lit {value = 1}},
    {"y", int_lit {value = 2}}
]}
# Generates: {x = 1, y = 2}
```

#### if_expr

Create an if-else expression.

```mezze
if_expr: {
    condition: Expression,
    then_branch: Expression,
    else_branch: Expression
} -> Expression
```

**Example:**
```mezze
if_expr {
    condition = binary_op {op = "==", left = identifier {name = "x"}, right = int_lit {value = 0}},
    then_branch = string_lit {value = "zero"},
    else_branch = string_lit {value = "non-zero"}
}
# Generates: if x == 0 then "zero" else "non-zero"
```

#### match_expr

Create a match expression.

```mezze
match_expr: {
    scrutinee: Expression,
    cases: [MatchCase]
} -> Expression

type MatchCase = {
    pattern: Pattern,
    guard: Option Expression,
    body: Expression
}
```

**Example:**
```mezze
match_expr {
    scrutinee = identifier {name = "value"},
    cases = [
        {pattern = int_pattern {value = 0}, guard = None, body = string_lit {value = "zero"}},
        {pattern = wildcard_pattern {}, guard = None, body = string_lit {value = "other"}}
    ]
}
```

#### do_expr

Create a do expression (block with statements).

```mezze
do_expr: {
    statements: [Statement],
    result: Expression
} -> Expression
```

**Example:**
```mezze
do_expr {
    statements = [
        let_stmt {name = "x", value = int_lit {value = 5}},
        let_stmt {name = "y", value = int_lit {value = 10}}
    ],
    result = binary_op {op = "+", left = identifier {name = "x"}, right = identifier {name = "y"}}
}
# Generates:
# do
#     let x = 5
#     let y = 10
#     x + y
```

#### lambda

Create a lambda expression.

```mezze
lambda: {params: [String], body: Expression} -> Expression
```

**Example:**
```mezze
lambda {
    params = ["x", "y"],
    body = binary_op {op = "+", left = identifier {name = "x"}, right = identifier {name = "y"}}
}
# Generates: {x, y} -> x + y
```

#### binary_op

Create a binary operation.

```mezze
binary_op: {op: String, left: Expression, right: Expression} -> Expression
```

**Example:**
```mezze
binary_op {
    op = "+",
    left = identifier {name = "x"},
    right = int_lit {value = 1}
}
# Generates: x + 1
```

**Supported operators:** `"+"`, `"-"`, `"*"`, `"/"`, `"%"`, `"=="`, `"!="`, `"<"`, `">"`, `"<="`, `">="`, `"&&"`, `"||"`

#### unary_op

Create a unary operation.

```mezze
unary_op: {op: String, operand: Expression} -> Expression
```

**Supported operators:** `"-"`, `"!"`

#### member_access

Create a member access expression.

```mezze
member_access: {obj: Expression, member: String} -> Expression
```

**Example:**
```mezze
member_access {
    obj = identifier {name = "point"},
    member = "x"
}
# Generates: point.x
```

### Statements

#### let_stmt

Create a let statement.

```mezze
let_stmt: {name: String, value: Expression} -> Statement
```

**Example:**
```mezze
let_stmt {name = "x", value = int_lit {value = 42}}
# Generates: let x = 42
```

#### expr_stmt

Create an expression statement.

```mezze
expr_stmt: {expr: Expression} -> Statement
```

### Meta Functions

#### meta_file

Get the current file name.

```mezze
meta_file: {} -> Expression
```

Returns a string literal with the current file name.

#### meta_line

Get the current line number.

```mezze
meta_line: {} -> Expression
```

Returns an integer literal with the current line number.

#### stringify_expr

Convert an expression AST to its string representation.

```mezze
stringify_expr: {expr: Expression} -> String
```

**Example:**
```mezze
let expr = binary_op {op = "+", left = identifier {name = "x"}, right = int_lit {value = 1}}
stringify_expr {expr}  # Returns: "x + 1"
```

#### compile_error

Emit a compile-time error.

```mezze
compile_error: {msg: String} -> 'a
```

**Example:**
```mezze
match validate_input {input} is
    Err {error} -> compile_error {msg = "Invalid input: $(error)"}
    Ok {value} -> value
```

## Template Helpers

Located in `std/macro/primitives.mz`.

### String Operations

#### replace_all

Replace all occurrences of a substring.

```mezze
replace_all: {text: String, from: String, to: String} -> String
```

#### replace_interpolations

Replace interpolations with values.

```mezze
replace_interpolations: {
    template: String,
    replacements: [{from: String, to: String}]
} -> String
```

#### extract_interpolations

Extract interpolation keys from template.

```mezze
extract_interpolations: {template: String} -> [String]
```

#### count_interpolations

Count interpolations in template.

```mezze
count_interpolations: {template: String} -> Int
```

### Validation Helpers

#### validate_sql

Validate SQL syntax.

```mezze
validate_sql: {sql: String} -> Result {} String
```

#### validate_html

Validate HTML structure.

```mezze
validate_html: {html: String} -> Result {} String
```

#### validate_regex

Validate regex pattern.

```mezze
validate_regex: {pattern: String} -> Result {} String
```

#### validate_json

Validate JSON structure.

```mezze
validate_json: {json: String} -> Result {} String
```

### Interpolation Helpers

#### get_interpolation

Get interpolation by key.

```mezze
get_interpolation: {
    interpolations: [{key: String, val: Expression}],
    key: String
} -> Option Expression
```

#### has_interpolation

Check if interpolation exists.

```mezze
has_interpolation: {
    interpolations: [{key: String, val: Expression}],
    key: String
} -> Bool
```

#### map_interpolations

Map over interpolations.

```mezze
map_interpolations: {
    interpolations: [{key: String, val: Expression}],
    f: {String, Expression} -> 'a
} -> ['a]
```

## Reflection API

Located in `std/macro/reflection.mz`.

### Type Inspection

#### type_info

Get type information.

```mezze
type_info: {for_type: Type} -> TypeInfo

type TypeInfo =
    | RecordType {fields: [{name: String, type: Type}]}
    | VariantType {constructors: [{name: String, payload: Option Type}]}
    | FunctionType {params: [Type], return: Type}
    | PrimitiveType {name: String}
    | GenericType {name: String, params: [Type]}
```

**Example:**
```mezze
match type_info {for_type = Point} is
    RecordType {fields} -> derive_eq_for_record {fields}
    VariantType {constructors} -> derive_eq_for_variant {constructors}
    _ -> compile_error {msg = "Cannot derive Eq for this type"}
```

#### type_name

Get type name.

```mezze
type_name: {type: Type} -> String
```

#### type_params

Get type parameters (for generics).

```mezze
type_params: {type: Type} -> [String]
```

#### fields_of

Get record fields.

```mezze
fields_of: {type: Type} -> [{name: String, type: Type}]
```

#### constructors_of

Get variant constructors.

```mezze
constructors_of: {type: Type} -> [{name: String, payload: Option Type}]
```

#### is_record

Check if type is a record.

```mezze
is_record: {type: Type} -> Bool
```

#### is_variant

Check if type is a variant.

```mezze
is_variant: {type: Type} -> Bool
```

#### is_function

Check if type is a function.

```mezze
is_function: {type: Type} -> Bool
```

#### is_generic

Check if type is generic.

```mezze
is_generic: {type: Type} -> Bool
```

### Code Generation

#### emit_impl

Emit a trait implementation.

```mezze
emit_impl: {
    trait: String,
    for_type: String,
    methods: [{name: String, params: [String], body: Expression}]
} -> {}
```

**Example:**
```mezze
emit_impl {
    trait = "Eq",
    for_type = "Point",
    methods = [{
        name = "eq",
        params = ["l", "r"],
        body = binary_op {
            op = "&&",
            left = binary_op {op = "==", left = member_access {obj = identifier {name = "l"}, member = "x"}, right = member_access {obj = identifier {name = "r"}, member = "x"}},
            right = binary_op {op = "==", left = member_access {obj = identifier {name = "l"}, member = "y"}, right = member_access {obj = identifier {name = "r"}, member = "y"}}
        }
    }]
}
```

#### emit_function

Emit a function declaration.

```mezze
emit_function: {
    name: String,
    params: [String],
    body: Expression
} -> {}
```

#### emit_type

Emit a type declaration.

```mezze
emit_type: {
    name: String,
    definition: TypeDefinition
} -> {}
```

## Derive Functions

Located in `std/macro/reflection.mz`.

### Equality

#### derive_eq

Derive Eq trait implementation.

```mezze
derive_eq: {for_type: Type} -> {}
```

Generates `eq: {l, r} -> Bool` implementation.

#### derive_ne

Derive not-equal implementation.

```mezze
derive_ne: {for_type: Type} -> {}
```

### Comparison

#### derive_ord

Derive Ord trait implementation.

```mezze
derive_ord: {for_type: Type} -> {}
```

Generates `compare: {l, r} -> Ordering` implementation.

#### derive_lt, derive_le, derive_gt, derive_ge

Derive comparison operators.

```mezze
derive_lt: {for_type: Type} -> {}
derive_le: {for_type: Type} -> {}
derive_gt: {for_type: Type} -> {}
derive_ge: {for_type: Type} -> {}
```

### String Conversion

#### derive_show

Derive Show trait implementation.

```mezze
derive_show: {for_type: Type} -> {}
```

Generates `show: {value} -> String` implementation.

**Example output:**
```
Point {x = 1, y = 2}
```

#### derive_debug

Derive Debug trait implementation (verbose output).

```mezze
derive_debug: {for_type: Type} -> {}
```

### Copying

#### derive_clone

Derive Clone trait implementation (deep copy).

```mezze
derive_clone: {for_type: Type} -> {}
```

#### derive_copy

Derive Copy trait implementation (shallow copy).

```mezze
derive_copy: {for_type: Type} -> {}
```

### Hashing

#### derive_hash

Derive Hash trait implementation.

```mezze
derive_hash: {for_type: Type} -> {}
```

### Serialization

#### derive_to_json

Derive JSON serialization.

```mezze
derive_to_json: {for_type: Type} -> {}
```

#### derive_from_json

Derive JSON deserialization.

```mezze
derive_from_json: {for_type: Type} -> {}
```

### Default Values

#### derive_default

Derive Default trait implementation.

```mezze
derive_default: {for_type: Type} -> {}
```

## Built-in Macros

Located in `std/macro/builtins.mz`.

### list!

Create a list literal.

```mezze
macro list: {tokens: TokenStream} -> List 'a
```

**Usage:**
```mezze
list!()           # Empty list
list!(1, 2, 3)    # [1, 2, 3]
```

### map!

Create a map literal.

```mezze
macro map: {tokens: TokenStream} -> Map 'k 'v
```

**Usage:**
```mezze
map!()                        # Empty map
map!("x" => 5, "y" => 10)    # {x: 5, y: 10}
```

### assert_eq!

Assert two values are equal.

```mezze
macro assert_eq: {tokens: TokenStream} -> {}
```

**Usage:**
```mezze
assert_eq!(a, b)
assert_eq!(x + 1, y, "values should match")
```

### dbg!

Debug print an expression.

```mezze
macro dbg: {tokens: TokenStream} -> 'a
```

**Usage:**
```mezze
let x = dbg!(compute_value {})
# Prints: [main.mz:5] compute_value {} = 42
# Returns: 42
```

### sql```

SQL template macro.

```mezze
macro sql: {content: String, interpolations: ...} -> SqlQuery
```

**Usage:**
```mezze
sql```SELECT * FROM users WHERE id = ${user_id}```
```

### html```

HTML template macro.

```mezze
macro html: {content: String, interpolations: ...} -> HtmlNode
```

**Usage:**
```mezze
html```<div><h1>${title}</h1></div>```
```

### regex```

Regex template macro (compile-time validated).

```mezze
macro regex: {content: String, interpolations: ...} -> Regex
```

**Usage:**
```mezze
regex```^[a-z0-9]+@[a-z0-9]+\.[a-z]{2,}$```
```

### json```

JSON template macro.

```mezze
macro json: {content: String, interpolations: ...} -> JsonValue
```

**Usage:**
```mezze
json```{"name": "${name}", "age": ${age}}```
```

## CompileTime Function

Built-in function for compile-time execution.

```mezze
comptime: {eval: {} -> 'a} -> 'a
```

**Usage:**
```mezze
let config = comptime {eval = {} ->
    read_file {path = "config.json"} |> parse_json {@}
}
```

## Summary

The standard library provides:
- **Parser primitives** - 15+ parsing functions
- **AST builders** - 20+ builder functions
- **Template helpers** - String operations, validation
- **Reflection API** - Type introspection
- **Derive functions** - 10+ automatic trait derivations
- **Built-in macros** - list, map, assert_eq, dbg, sql, html, etc.
- **CompileTime** - Arbitrary compile-time execution

All functions are type-safe and integrate seamlessly with Mezze's type system.
