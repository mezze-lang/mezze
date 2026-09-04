# Mezze Macro System v2

Complete specification for Mezze's compile-time metaprogramming system.

## Overview

Mezze provides compile-time metaprogramming through:

1. **Macros** - Syntax transformers: `Tokens -> Tokens`
2. **Comptime** - Compile-time code execution with effect erasure
3. **Reflection** - Access to type system information at compile time

## Syntax

| Syntax | Meaning |
|--------|---------|
| `macro name: {tokens: Tokens} -> Tokens = ...` | Define a macro |
| `name!(...)` | Call a macro |
| `` `(...) `` | Quote - capture code as `Tokens` |
| `~expr` | Interpolate - type-directed (see below) |

That's all the new syntax.

### The `~` Operator (Type-Directed)

Inside a quote, `~expr` behavior depends on the type of `expr`:

| Type of `expr` | `~expr` does |
|----------------|--------------|
| `Tokens` | Splice tokens into quote |
| `String`, `Int`, `Bool`, etc. | Convert value to literal tokens |

```mezze
let code = `(1 + 2)        // Tokens
let name = "x"             // String
let val = 42               // Int

`(let ~(ident {name}) = ~code)   // splices Tokens
`(let x = ~name)                  // becomes: let x = "x"
`(let y = ~val)                   // becomes: let y = 42
```

One operator, the typechecker knows what to do.

## Defining Macros

```mezze
macro debug: {tokens: Tokens} -> Tokens = {tokens} ->
    match parse_expr {tokens} is
        Err {e} ->
            compile_error_with_help {
                msg = "debug! requires a valid expression",
                span = span_of {tokens},
                help = "Usage: debug!(some_expression)"
            }
        Ok {_} ->
            let span = span_of {tokens}
            let expr_str = tokens_to_string {tokens}
            `(
                do
                    let __value = ~tokens
                    Log.debug {msg = "[$(~span.file):$(~span.line)] $(~expr_str) = $(__value)"}
                    __value
            )
```

## Calling Macros

```mezze
let x = 5
let y = debug!(x + 10)
# Prints: [src/main.mz:42] x + 10 = 15
# y = 15
```

## Quote, Splice, and Literal

### Quote: `` `(...) ``

Captures code as `Tokens` without executing it:

```mezze
let code = `(1 + 2)  # Tokens, not 3
```

### Splice: `~expr`

Inserts `Tokens` into a quote:

```mezze
let x = `(1 + 2)
let y = `(foo {val = ~x})  # becomes: foo {val = 1 + 2}
```

`~` only works inside `` `(...) `` and only with `Tokens` type.

### Type Rules

| Location | `~expr` |
|----------|---------|
| Outside quote | Syntax error |
| Inside quote, expr: Tokens | Splice tokens |
| Inside quote, expr: Int/String/Bool/etc. | Convert to literal |

No need for separate `^` - the type tells us what to do.

## Hygiene

Macros are hygienic by default. Identifiers created in quotes are in macro scope, separate from caller scope:

```mezze
macro with_temp: {tokens: Tokens} -> Tokens = {tokens} ->
    `(
        let temp = ~tokens
        temp + temp
    )

let temp = 100
with_temp!(temp * 2)
# The 'temp' inside macro is different from outer 'temp'
# Result: 400
```

To escape hygiene (refer to caller's scope):

```mezze
macro set_result: {tokens: Tokens} -> Tokens = {tokens} ->
    let result = raw_ident {name = "result"}
    `(let ~result = ~tokens)

let result = 0
set_result!(42)
# result is now 42
```

## Comptime

Execute code at compile time with effect erasure:

```mezze
comptime: {eval: {} -> 'a <*>} -> 'a
```

```mezze
let config = comptime {eval = {} ->
    perform FileSystemImpl in read_file {path = "config.json"}
}
# config is a pure value - effects erased
```

### Comptime in Macros

Macros can use comptime for compile-time effects:

```mezze
macro graphql: {tokens: Tokens} -> Tokens = {tokens} ->
    let query = parse_graphql {tokens}
    let schema = comptime {eval = {} ->
        perform FileSystemImpl in read_file {path = "schema.graphql"}
    }
    validate {query, schema}
    `(GraphqlQuery {text = ~(query.text)})
```

## Reflection

Access type system information at compile time via comptime:

```mezze
macro json_schema: {tokens: Tokens} -> Tokens = {tokens} ->
    match parse_type {tokens} is
        Err {e} -> compile_error {...}
        Ok {_} ->
            let type_name = tokens_to_string {tokens}
            let info = comptime {eval = {} ->
                reflect_type {name = type_name}
            }
            let schema = build_schema {info}
            `(~schema)
```

## Macros Are Not Values

Macros exist only at compile time. They cannot be:
- Assigned to variables
- Passed as arguments
- Returned from functions

```mezze
macro my_macro: {tokens: Tokens} -> Tokens = {tokens} -> ...

let x = my_macro  # ERROR
```

**Error:**
```
error: cannot assign macro to a variable
  --> src/main.mz:3:9
   |
 3 | let x = my_macro
   |         ^^^^^^^^
   |
   = note: `my_macro` is a macro, not a value
   = help: to call the macro, use: my_macro!(...)
```

For reusable token transformations, use functions:

```mezze
let my_transform: {tokens: Tokens} -> Tokens = {tokens} ->
    `(Log.debug {msg = ~tokens})

macro debug: {tokens: Tokens} -> Tokens = {tokens} ->
    my_transform {tokens}
```

## Error Handling

### Compile Errors

```mezze
compile_error: {msg: String, span: Span} -> Tokens

compile_error_with_help: {msg: String, span: Span, help: String} -> Tokens

compile_error_with_suggestion: {
    msg: String,
    span: Span,
    suggestion: String,
    replacement: Tokens
} -> Tokens

compile_errors: {errors: List CompileError} -> Tokens

compile_warning: {msg: String, span: Span} -> {}
```

### Example with Full Validation

```mezze
macro assert_eq: {tokens: Tokens} -> Tokens = {tokens} ->
    let parts = split {tokens, on = ","}
    match parts is
        [left, right] ->
            match parse_expr {left} is
                Err {e} ->
                    compile_error {
                        msg = "expected expression for left side",
                        span = span_of {tokens = left}
                    }
                Ok {_} ->
                    match parse_expr {right} is
                        Err {e} ->
                            compile_error {
                                msg = "expected expression for right side",
                                span = span_of {tokens = right}
                            }
                        Ok {_} ->
                            let left_str = tokens_to_string {tokens = left}
                            let right_str = tokens_to_string {tokens = right}
                            `(
                                do
                                    let __l = ~left
                                    let __r = ~right
                                    if __l != __r then
                                        crash {msg = "Assertion failed: $(~left_str) == $(~right_str)\n  left:  $(__l)\n  right: $(__r)"}
                                    else
                                        {}
                            )
        _ ->
            compile_error_with_help {
                msg = "expected 2 arguments",
                span = span_of {tokens},
                help = "Usage: assert_eq!(actual, expected)"
            }
```

## Types

### Core Types

```mezze
type Tokens = ...  # opaque, compiler provided

type Span = {
    file: String,
    start_line: Int,
    start_col: Int,
    end_line: Int,
    end_col: Int
}

type ParseError = {msg: String, span: Span}

type CompileError = {
    msg: String,
    span: Span,
    help: Option String,
    suggestion: Option {text: String, replacement: Tokens}
}
```

### Reflection Types

```mezze
type ReflectedType =
    | Primitive {name: String}
    , TypeVar {name: String}
    , Record {fields: List ReflectedField, open: Bool}
    , Variant {constructors: List ReflectedConstructor, open: Bool}
    , Newtype {name: String, inner: ReflectedType}
    , Alias {name: String, target: ReflectedType}
    , Function {param: ReflectedType, ret: ReflectedType, effects: ReflectedEffects, constraints: List ReflectedConstraint}
    , TypeApp {base: ReflectedType, args: List ReflectedType}
    , List {elem: ReflectedType}
    , Seq {elem: ReflectedType}
    , Map {key: ReflectedType, value: ReflectedType}
    , Set {elem: ReflectedType}
    , SortedMap {key: ReflectedType, value: ReflectedType}
    , SortedSet {elem: ReflectedType}
    , Option {inner: ReflectedType}
    , Result {ok: ReflectedType, err: ReflectedType}
    |

type ReflectedField = {name: String, ty: ReflectedType}

type ReflectedConstructor = {name: String, fields: List ReflectedField}

type ReflectedEffects = {effects: List String, open: Bool, performed: Bool}

type ReflectedConstraint = {ability: String, type_args: List ReflectedType}

type ReflectedAbility = {
    name: String,
    type_params: List String,
    methods: List ReflectedMethod,
    associated_types: List String
}

type ReflectedEffect = {
    name: String,
    associated_types: List String,
    operations: List ReflectedMethod
}

type ReflectedMethod = {name: String, ty: ReflectedType}

type ReflectedBinding =
    | Value {ty: ReflectedType}
    , Function {ty: ReflectedType}
    , Type {def: ReflectedType}
    , Ability {def: ReflectedAbility}
    , Effect {def: ReflectedEffect}
    |
```

## Functions

### Parsing

```mezze
parse_expr: {tokens: Tokens} -> Result Tokens ParseError
parse_ident: {tokens: Tokens} -> Result String ParseError
parse_string: {tokens: Tokens} -> Result String ParseError
parse_int: {tokens: Tokens} -> Result Int ParseError
parse_type: {tokens: Tokens} -> Result Tokens ParseError
parse_pattern: {tokens: Tokens} -> Result Tokens ParseError
```

### Token Inspection

```mezze
is_empty: {tokens: Tokens} -> Bool
is_ident: {tokens: Tokens} -> Bool
is_literal: {tokens: Tokens} -> Bool
is_group: {tokens: Tokens} -> Bool
first: {tokens: Tokens} -> Option Token
rest: {tokens: Tokens} -> Tokens
length: {tokens: Tokens} -> Int
tokens_to_string: {tokens: Tokens} -> String
```

### Token Manipulation

```mezze
split: {tokens: Tokens, on: String} -> List Tokens
concat: {a: Tokens, b: Tokens} -> Tokens
intersperse: {list: List Tokens, sep: Tokens} -> Tokens
```

### Token Creation

```mezze
ident: {name: String} -> Tokens
raw_ident: {name: String} -> Tokens
lit_string: {value: String} -> Tokens
lit_int: {value: Int} -> Tokens
lit_float: {value: Float} -> Tokens
lit_bool: {value: Bool} -> Tokens
gensym: {} -> Tokens
gensym_named: {prefix: String} -> Tokens
concat_idents: {parts: List String} -> Tokens
```

### Source Location

```mezze
span_of: {tokens: Tokens} -> Span
span_join: {a: Span, b: Span} -> Span
```

### Reflection (in comptime)

```mezze
reflect: {name: String} -> ReflectedBinding
reflect_type: {name: String} -> ReflectedType
reflect_ability: {name: String} -> ReflectedAbility
reflect_effect: {name: String} -> ReflectedEffect
reflect_function: {name: String} -> ReflectedType
has_ability: {ty: ReflectedType, ability: String} -> Bool
get_abilities: {ty: ReflectedType} -> List String
get_impls: {ability: String} -> List ReflectedType
```

## Examples

### debug!

```mezze
macro debug: {tokens: Tokens} -> Tokens = {tokens} ->
    match parse_expr {tokens} is
        Err {e} ->
            compile_error_with_help {
                msg = "debug! requires a valid expression",
                span = span_of {tokens},
                help = "Usage: debug!(some_expression)"
            }
        Ok {_} ->
            let span = span_of {tokens}
            let expr_str = tokens_to_string {tokens}
            `(
                do
                    let __value = ~tokens
                    Log.debug {msg = "[$(~span.file):$(~span.line)] $(~expr_str) = $(__value)"}
                    __value
            )
```

### list!

```mezze
macro list: {tokens: Tokens} -> Tokens = {tokens} ->
    let items = split {tokens, on = ","}
    match items is
        [] -> `(List::Nil)
        _ ->
            let build = {items: List Tokens} -> Tokens =
                match items is
                    [] -> `(List::Nil)
                    [head, ...tail] ->
                        let rest = build {items = tail}
                        `(List::Cons {head = ~head, tail = ~rest})
            build {items}
```

### impl_eq!

```mezze
macro impl_eq: {tokens: Tokens} -> Tokens = {tokens} ->
    match parse_type {tokens} is
        Err {e} ->
            compile_error {msg = "expected a type name", span = span_of {tokens}}
        Ok {_} ->
            let type_name = tokens_to_string {tokens}
            let info = comptime {eval = {} ->
                reflect_type {name = type_name}
            }
            match info is
                Record {fields, open} ->
                    let field_eqs = list::map {list = fields, f = {field ->
                        let name = ident {name = field.name}
                        `(Eq.eq {l = l.~name, r = r.~name})
                    }}
                    let combined = intersperse {list = field_eqs, sep = `(&&)}
                    `(
                        impl Eq for ~tokens where {
                            eq = {l, r} -> ~combined
                        }
                    )
                _ ->
                    compile_error {
                        msg = "impl_eq! only works on records",
                        span = span_of {tokens}
                    }
```

### json_schema!

```mezze
macro json_schema: {tokens: Tokens} -> Tokens = {tokens} ->
    match parse_type {tokens} is
        Err {e} ->
            compile_error_with_help {
                msg = "expected a type name",
                span = span_of {tokens},
                help = "Usage: json_schema!(MyType)"
            }
        Ok {_} ->
            let type_name = tokens_to_string {tokens}
            let info = comptime {eval = {} ->
                reflect_type {name = type_name}
            }
            let schema = build_schema {info}
            `(~schema)

let build_schema: {info: ReflectedType} -> Json = {info} ->
    match info is
        Primitive {name} ->
            match name is
                "String" -> Json::Object {fields = [("type", Json::String {val = "string"})]}
                "Int" -> Json::Object {fields = [("type", Json::String {val = "integer"})]}
                "Float" -> Json::Object {fields = [("type", Json::String {val = "number"})]}
                "Bool" -> Json::Object {fields = [("type", Json::String {val = "boolean"})]}
                _ -> Json::Object {fields = []}
        Record {fields, open} ->
            Json::Object {fields = [
                ("type", Json::String {val = "object"}),
                ("properties", Json::Object {
                    fields = list::map {list = fields, f = {field ->
                        (field.name, build_schema {info = field.ty})
                    }}
                })
            ]}
        Option {inner} ->
            let inner_schema = build_schema {info = inner}
            add_nullable {schema = inner_schema}
        _ -> Json::Object {fields = []}
```

## IDE Support

Macro expansion is cached using content-addressed hashing + SQLite:

1. First expansion: run macro (including comptime), cache result
2. Subsequent: hash lookup, instant result

This makes IDE support fast:
- Type info available after first expansion
- Cache persists across IDE restarts
- Same cache used by compiler

## Comparison to Rust

| Aspect | Rust | Mezze |
|--------|------|-------|
| Syntax | `quote!`, `#var` | `` `() ``, `~` |
| Hygiene | Yes | Yes |
| Type reflection | Syntax only (syn) | Full semantic (reflect_type) |
| Effects | Implicit | Explicit via comptime |
| Caching | None | Content-addressed |
| Derive macros | Separate feature | Just use macro + reflection |
| Must be in separate crate | Yes | No |

## Summary

The macro system provides:
- **Simple syntax**: 4 new constructs (`macro`, `!()`, `` `() ``, `~`)
- **Full power**: comptime for effects, reflection (Phase 2)
- **Great errors**: spans, help, suggestions
- **Fast IDE**: SQLite caching, content-hash invalidation
- **No derive macros needed**: reflection + macro covers all use cases
