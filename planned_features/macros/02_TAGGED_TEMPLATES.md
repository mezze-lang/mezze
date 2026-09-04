# DSL Embedding with Pattern Macros

This document explains how to embed domain-specific languages (DSLs) using pattern macros with string literals and interpolation. Instead of a separate tagged template syntax, we use pattern macros with the `!()` syntax.

## Declaration Syntax

```mezze
macro name: {tokens: TokenStream} -> Expr (ReturnType <effects>) = {tokens} ->
    # Parse tokens (typically a string literal)
    # Validate DSL syntax
    # Process interpolations
    # Generate AST
```

**Key points:**
- Declared with `macro` keyword
- Lowercase naming (function convention)
- Receives `TokenStream` (raw tokens inside `!()`)
- Returns `Expr` type which wraps generated AST
- **Fully statically typed** with type inference

## Basic Example: sql

```mezze
macro sql: {tokens: TokenStream} -> Expr (SqlQuery <>) = {tokens} ->
    # Parse tokens - expect a string literal
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # Validate SQL syntax
            match validate_sql {sql = template} is
                Err {error} -> compile_error {msg = "Invalid SQL: $(error)"}
                Ok {} -> {}

            # Replace interpolations with placeholders
            let placeholders = interpolations |> list::map_indexed {list = @, f = {interp, idx} ->
                {key = interp.name, placeholder = "?$(idx)"}
            }

            let query_string = replace_interpolations {
                template,
                replacements = placeholders
            }

            let params = interpolations |> list::map {list = @, f = {interp} -> interp.expr}

            record {fields = [
                {"query", string_lit {value = query_string}},
                {"params", list_literal {elements = params}}
            ]}

# Usage (with string interpolation):
let user_id = 42
sql!("SELECT * FROM users WHERE id = $(user_id)")

# Expands to:
{
    query = "SELECT * FROM users WHERE id = ?0",
    params = [user_id]
}
```

## How It Works

```
User writes:    sql!("SELECT * FROM users WHERE id = $(user_id)")
                    ↓
Parser:         Captures raw tokens: ["SELECT * FROM users WHERE id = $(user_id)"]
                    ↓
Type checker:   Calls sql macro with TokenStream
                    ↓
Macro executes: 1. parse_string_literal → extract template and interpolations
                2. validate_sql → check SQL syntax
                3. replace_interpolations → "... WHERE id = ?0"
                4. extract params → [user_id]
                5. build AST
                    ↓
Returns AST:    Record({
                  query = "SELECT * FROM users WHERE id = ?0",
                  params = [user_id]
                })
                    ↓
Type checker:   Validates generated AST
                Checks user_id type matches SQL parameter type
                    ↓
Result:         Typed expression of type SqlQuery
```

## Template Processing Functions

All available in macro scope:

### String Parsing

```mezze
# Parse string literal from token stream
parse_string_literal: {
    tokens: TokenStream
} -> Result {
    template: String,
    interpolations: [{name: String, expr: Expr 'a}]
} String

# Replace interpolations with placeholders
replace_interpolations: {
    template: String,
    replacements: [{key: String, placeholder: String}]
} -> String

# Extract interpolation names
extract_interpolation_names: {template: String} -> [String]

# Count interpolations
count_interpolations: {template: String} -> Int
```

### Validation Helpers

```mezze
# Validate SQL syntax
validate_sql: {sql: String} -> Result {} String

# Validate HTML structure
validate_html: {html: String} -> Result {} String

# Validate regex pattern
validate_regex: {pattern: String} -> Result {} String

# Validate JSON structure
validate_json: {json: String} -> Result {} String
```

## Example: html Macro

```mezze
macro html: {tokens: TokenStream} -> Expr (HtmlNode <>) = {tokens} ->
    # Parse string literal
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # Validate HTML
            match validate_html {html = template} is
                Err {error} -> compile_error {msg = "Invalid HTML: $(error)"}
                Ok {} -> {}

            # Parse HTML structure
            match parse_html {html = template} is
                Err {error} -> compile_error {msg = "Failed to parse HTML: $(error)"}
                Ok {tree} ->
                    build_html_ast {tree, interpolations}

let build_html_ast = {tree, interpolations} ->
    call {
        fn = qualified {name = "html::element"},
        args = record {fields = [
            {"tag", string_lit {value = tree.tag}},
            {"attrs", build_attributes {attrs = tree.attrs, interpolations}},
            {"children", build_children {children = tree.children, interpolations}}
        ]}
    }

let build_attributes = {attrs, interpolations} ->
    let attr_list = attrs |> list::map {list = @, f = {attr} ->
        let value = match attr.value is
            Literal {text} -> string_lit {value = text}
            Interpolation {name} ->
                match find_interpolation {interpolations, name} is
                    Some {expr} -> expr
                    None -> compile_error {msg = "Unknown interpolation: $(name)"}

        record {fields = [
            {"name", string_lit {value = attr.name}},
            {"value", value}
        ]}
    }

    list_literal {elements = attr_list}

let build_children = {children, interpolations} ->
    let child_list = children |> list::map {list = @, f = {child} ->
        match child is
            Text {content} ->
                call {
                    fn = qualified {name = "html::text"},
                    args = record {fields = [{"content", string_lit {value = content}}]}
                }
            Element {tree} -> build_html_ast {tree, interpolations}
            Interpolation {name} ->
                match find_interpolation {interpolations, name} is
                    Some {expr} -> expr
                    None -> compile_error {msg = "Unknown interpolation: $(name)"}
    }

    list_literal {elements = child_list}

# Usage:
let name = "Alice"
let items = ["Apple", "Banana", "Cherry"]

html!("
<div class=\"container\">
    <h1>Hello, $(name)!</h1>
    <ul>
        $(items)
    </ul>
</div>
")

# Type-checks that:
# - name is String
# - items is compatible with list rendering
# - HTML structure is valid
```

## Example: regex Macro

```mezze
macro regex: {tokens: TokenStream} -> Expr (Regex <>) = {tokens} ->
    # Parse string literal
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # Regex patterns should not have interpolations (compile-time only)
            match list::is_empty {list = interpolations} is
                False -> compile_error {
                    msg = "regex! does not support interpolations (pattern must be compile-time constant)"
                }
                True -> {}

            # Validate regex syntax
            match validate_regex {pattern = template} is
                Err {error} -> compile_error {msg = "Invalid regex pattern: $(error)"}
                Ok {} -> {}

            # Return compiled regex
            call {
                fn = qualified {name = "regex::compile"},
                args = record {fields = [
                    {"pattern", string_lit {value = template}}
                ]}
            }

# Usage:
let email_pattern = regex!("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")

# Compile error if pattern is invalid!
let bad_pattern = regex!("[unclosed")
# Error: Invalid regex pattern: unclosed character class
```

## Example: json Macro

```mezze
macro json: {tokens: TokenStream} -> Expr (JsonValue <>) = {tokens} ->
    # Parse string literal
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # Validate JSON structure
            match validate_json {json = template} is
                Err {error} -> compile_error {msg = "Invalid JSON: $(error)"}
                Ok {} -> {}

            # Parse JSON
            match parse_json_template {json = template} is
                Err {error} -> compile_error {msg = "Failed to parse JSON: $(error)"}
                Ok {tree} -> build_json_ast {tree, interpolations}

let build_json_ast = {tree, interpolations} ->
    match tree is
        JsonObject {fields} ->
            let field_list = fields |> list::map {list = @, f = {field} ->
                record {fields = [
                    {"key", string_lit {value = field.key}},
                    {"value", build_json_ast {tree = field.value, interpolations}}
                ]}
            }
            call {
                fn = qualified {name = "json::object"},
                args = record {fields = [{"fields", list_literal {elements = field_list}}]}
            }

        JsonArray {items} ->
            let item_list = items |> list::map {list = @, f = {item} ->
                build_json_ast {tree = item, interpolations}
            }
            call {
                fn = qualified {name = "json::array"},
                args = record {fields = [{"items", list_literal {elements = item_list}}]}
            }

        JsonString {value} ->
            call {
                fn = qualified {name = "json::string"},
                args = record {fields = [{"value", string_lit {value}}]}
            }

        JsonNumber {value} ->
            call {
                fn = qualified {name = "json::number"},
                args = record {fields = [{"value", int_lit {value}}]}
            }

        JsonBool {value} ->
            call {
                fn = qualified {name = "json::bool"},
                args = record {fields = [{"value", bool_lit {value}}]}
            }

        JsonNull {} ->
            qualified {name = "json::null"}

        JsonInterpolation {name} ->
            match find_interpolation {interpolations, name} is
                Some {expr} ->
                    # Wrap expression in json::from
                    call {
                        fn = qualified {name = "json::from"},
                        args = record {fields = [{"value", expr}]}
                    }
                None -> compile_error {msg = "Unknown interpolation: $(name)"}

# Usage:
let name = "Alice"
let age = 30

json!("
{
    \"name\": \"$(name)\",
    \"age\": $(age),
    \"active\": true
}
")

# Expands to:
json::object {fields = [
    {key = "name", value = json::string {value = name}},
    {key = "age", value = json::from {value = age}},
    {key = "active", value = json::bool {value = true}}
]}
```

## Interpolation Syntax

String literals use `$(name)` for interpolation:

```mezze
# Simple interpolation
sql!("SELECT * FROM users WHERE id = $(user_id)")
#                                     ^^^^^^^^^^^ user_id

# Multiple interpolations
sql!("SELECT * FROM $(table) WHERE $(column) = $(value)")
#                   ^^^^^^^        ^^^^^^^^     ^^^^^^^

# Interpolations are expressions
html!("<div class=\"$(get_class {})\">Content</div>")
#                    ^^^^^^^^^^^^^^ any expression
```

**Rules:**
1. `$(name)` interpolates the variable `name`
2. `$(expression)` interpolates any expression
3. Interpolation names are captured automatically by `parse_string_literal`
4. Each `$(...)` becomes an entry in the `interpolations` list

## Type Safety

Interpolated expressions are type-checked:

```mezze
let user_id: Int = 42

# ✅ Correct: user_id is Int
sql!("SELECT * FROM users WHERE id = $(user_id)")

# ❌ Type error: if user_id was String
# Error: SQL parameter type mismatch
#   Expected: Int
#   Found: String in interpolation $(user_id)
```

## Compile-Time Validation

Templates are validated at compile-time:

```mezze
# ✅ Valid SQL
sql!("SELECT name FROM users")

# ❌ Invalid SQL
sql!("SELCT name FROM users")
# Error: Invalid SQL: syntax error near 'SELCT'
#   --> example.mz:1:1
#  |
#1 | sql!("SELCT name FROM users")
#  | ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ invalid SQL syntax
#  |
#  help: Did you mean 'SELECT'?

# ✅ Valid HTML
html!("<div><p>Hello</p></div>")

# ❌ Invalid HTML
html!("<div><p>Hello</div></p>")
# Error: Invalid HTML: mismatched tags
#   Expected closing tag: </p>
#   Found: </div>

# ✅ Valid regex
regex!("^\\d{3}-\\d{2}-\\d{4}$")

# ❌ Invalid regex
regex!("[unclosed")
# Error: Invalid regex pattern: unclosed character class
```

## Comparison with String Literals

```mezze
┌─────────────────────────────┬────────────────────────────┐
│ String Literals             │ Pattern Macros             │
├─────────────────────────────┼────────────────────────────┤
│ "SELECT * FROM users"       │ sql!("SELECT * FROM users")│
│ Runtime string              │ Compile-time validation    │
│ No syntax checking          │ Syntax checked             │
│ Manual escaping             │ Type-safe interpolation    │
├─────────────────────────────┼────────────────────────────┤
│ let query = "SELECT..."     │ let query = sql!("...")    │
│ Type: String                │ Type: SqlQuery (richer)    │
│ Manual escaping             │ Auto parameterization      │
│ SQL injection risk          │ SQL injection safe         │
└─────────────────────────────┴────────────────────────────┘
```

## Best Practices

1. **Validate early** - Check syntax before processing
2. **Clear error messages** - Point to exact location in template
3. **Type-safe interpolations** - Validate interpolated expression types
4. **Use qualified names** - `sql::query`, not `query`
5. **Document syntax** - Explain expected template format
6. **Handle edge cases** - Empty templates, no interpolations

## Common Patterns

### Template with no interpolations

```mezze
macro css: {tokens: TokenStream} -> Expr (CssRules <>) = {tokens} ->
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # CSS typically doesn't need interpolations (compile-time styles)
            match list::is_empty {list = interpolations} is
                False -> compile_error {msg = "css! does not support interpolations"}
                True -> {}

            validate_and_parse_css {template}
```

### Template with required interpolations

```mezze
macro query: {tokens: TokenStream} -> Expr (Query <>) = {tokens} ->
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # Ensure at least one interpolation exists
            match list::is_empty {list = interpolations} is
                True -> compile_error {msg = "query! requires at least one interpolation"}
                False -> build_query {template, interpolations}
```

### Template with conditional expansion

```mezze
macro html: {tokens: TokenStream} -> Expr (HtmlNode <>) = {tokens} ->
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            match parse_html {html = template} is
                Ok {tree} when is_static_tree {tree} ->
                    # No interpolations - optimize to static HTML
                    build_static_html {tree}

                Ok {tree} ->
                    # Has interpolations - build dynamic HTML
                    build_dynamic_html {tree, interpolations}

                Err {error} -> compile_error {msg = error}
```

### Type-safe parameter checking

```mezze
macro sql: {tokens: TokenStream} -> Expr (SqlQuery <>) = {tokens} ->
    match parse_string_literal {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {template, interpolations} ->
            # Infer expected parameter types from SQL
            let expected_types = infer_parameter_types {sql = template}

            # Check each interpolation
            interpolations |> list::iter_indexed {list = @, f = {interp, index} ->
                let expected = list::at {list = expected_types, index}
                match check_type_compatible {expr = interp.expr, expected} is
                    False -> compile_error {
                        msg = "Parameter $(interp.name) has wrong type\n  Expected: $(expected)\n  Found: $(type_of {expr = interp.expr})"
                    }
                    True -> {}
            }

            build_sql_query {template, interpolations}
```

## Error Handling

Always validate template syntax:

```mezze
# ✅ Good: Validate and handle errors
match validate_sql {sql = template} is
    Ok {} -> build_query {template}
    Err {error} -> compile_error {msg = "Invalid SQL: $(error)"}

# ❌ Bad: No validation
build_query {template}  # May generate invalid SQL!
```

## Summary

DSL embedding with pattern macros:
- ✅ Uses pattern macro syntax with `!()` invocation
- ✅ Receives `TokenStream`, parses string literals
- ✅ Returns `Expr` types (statically typed)
- ✅ Compile-time validated (syntax checking)
- ✅ Type-safe interpolations with `$(expression)` syntax
- ✅ Great for DSLs (SQL, HTML, regex, JSON, CSS, etc.)
- ✅ Single, consistent macro type across the language

This approach provides a clean way to embed domain-specific languages with full type safety and validation at compile-time, using the same pattern macro infrastructure as other macros.
