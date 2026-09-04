# CompileTime and Reflection

Mezze provides compile-time execution and reflection capabilities for advanced metaprogramming, particularly for deriving trait implementations.

## CompileTime: Executing Code at Compile-Time

The `comptime` function executes arbitrary Mezze code during compilation:

```mezze
comptime: {eval: {} -> 'a <*>} -> 'a
```

**Key points:**
- Built-in function (not a macro)
- Executes the `eval` function at compile-time
- Result is embedded in the compiled program
- No runtime overhead

### Basic Example

```mezze
# Load configuration at compile-time
let config = comptime {eval = {} ->
    read_file {path = "config.json"} |> parse_json {@}
}

# config is available at runtime with no file I/O overhead!
let port = config.server.port
```

### How It Works

```
Source Code:    let config = comptime {eval = {} -> read_file {...}}
                    ↓
Parser:         Creates CompileTimeExecution node
                    ↓
Type checker:   Evaluates the lambda {} -> read_file {...}
                Uses interpreter in "compile-time mode"
                    ↓
Interpreter:    Executes read_file, parse_json
                Returns the config value
                    ↓
Type checker:   Replaces comptime {...} with the result
                Embeds as a literal in the AST
                    ↓
Result:         let config = {server = {port = 8080, ...}, ...}
```

## Use Cases for CompileTime

### 1. Load Static Resources

```mezze
# Embed file contents at compile-time
let html_template = comptime {eval = {} ->
    read_file {path = "templates/index.html"}
}

# Include binary data
let icon_data = comptime {eval = {} ->
    read_binary {path = "assets/icon.png"}
}
```

### 2. Generate Code

```mezze
# Generate route handlers at compile-time
comptime {eval = {} ->
    let routes = read_file {path = "routes.config"} |> parse_routes {@}

    routes |> list::iter {list = @, f = {route} ->
        generate_handler {
            path = route.path,
            handler = route.handler
        }
    }
}
```

### 3. Compute Constants

```mezze
# Compute lookup table at compile-time
let crc_table = comptime {eval = {} ->
    list::range {from = 0, to = 255} |> list::map {list = @, f = {i} ->
        compute_crc_entry {value = i}
    }
}
```

### 4. Validate Configuration

```mezze
# Validate config at compile-time
comptime {eval = {} ->
    let config = read_file {path = "app.config"} |> parse_config {@}

    match validate_config {config} is
        Err {error} -> compile_error {msg = "Invalid config: $(error)"}
        Ok {} -> {}
}
```

## Reflection: Inspecting Types

Mezze provides reflection functions for inspecting types at compile-time:

### Type Information

```mezze
# Get type information
type_info: {for_type: Type} -> TypeInfo

type TypeInfo =
    | RecordType {fields: [{name: String, type: Type}]}
    | VariantType {constructors: [{name: String, payload: Option Type}]}
    | FunctionType {params: [Type], return: Type}
    | PrimitiveType {name: String}
    | GenericType {name: String, params: [Type]}
```

### Field Inspection

```mezze
# Get record fields
fields_of: {type: Type} -> [{name: String, type: Type}]

# Get variant constructors
constructors_of: {type: Type} -> [{name: String, payload: Option Type}]

# Check if type is a record
is_record: {type: Type} -> Bool

# Check if type is a variant
is_variant: {type: Type} -> Bool
```

### Type Queries

```mezze
# Get type name
type_name: {type: Type} -> String

# Get type parameters (for generics)
type_params: {type: Type} -> [String]

# Check type equality
type_equals: {t1: Type, t2: Type} -> Bool
```

## Deriving Trait Implementations

Use `comptime` with reflection to automatically generate trait implementations:

### Example: Deriving Eq

```mezze
type Point = {x: Int, y: Int}

# Derive Eq implementation at compile-time
comptime {eval = {} ->
    derive_eq {for_type = Point}
}

# Now Point has Eq implementation:
let p1 = {x = 1, y = 2}
let p2 = {x = 1, y = 2}
p1 == p2  # ✅ Works! Returns true
```

### How derive_eq Works

```mezze
# In std/macro/derive.mz

let derive_eq = {for_type} ->
    match type_info {for_type} is
        RecordType {fields} ->
            generate_record_eq {type_name = type_name {type = for_type}, fields}

        VariantType {constructors} ->
            generate_variant_eq {type_name = type_name {type = for_type}, constructors}

        _ ->
            compile_error {msg = "Cannot derive Eq for this type"}

let generate_record_eq = {type_name, fields} ->
    # Generate: impl Eq for TypeName where {
    #     eq = {l, r} ->
    #         l.field1 == r.field1 && l.field2 == r.field2 && ...
    # }

    let field_comparisons = fields |> list::map {list = @, f = {field} ->
        binary_op {
            op = "==",
            left = member_access {
                obj = identifier {name = "l"},
                member = field.name
            },
            right = member_access {
                obj = identifier {name = "r"},
                member = field.name
            }
        }
    }

    let eq_body = fold_and_operations {operations = field_comparisons}

    emit_impl {
        trait = "Eq",
        for_type = type_name,
        methods = [{
            name = "eq",
            params = ["l", "r"],
            body = eq_body
        }]
    }
```

### Example: Deriving Show

```mezze
type User = {name: String, age: Int, email: String}

comptime {eval = {} ->
    derive_show {for_type = User}
}

# Now User has Show implementation:
let user = {name = "Alice", age = 30, email = "alice@example.com"}
print {msg = show {user}}
# Prints: User {name = "Alice", age = 30, email = "alice@example.com"}
```

### How derive_show Works

```mezze
let derive_show = {for_type} ->
    match type_info {for_type} is
        RecordType {fields} ->
            generate_record_show {type_name = type_name {type = for_type}, fields}

        VariantType {constructors} ->
            generate_variant_show {type_name = type_name {type = for_type}, constructors}

        _ ->
            compile_error {msg = "Cannot derive Show for this type"}

let generate_record_show = {type_name, fields} ->
    # Generate: impl Show for TypeName where {
    #     show = {value} ->
    #         "TypeName {field1 = ..., field2 = ..., ...}"
    # }

    let field_strings = fields |> list::map {list = @, f = {field} ->
        string_concat {
            parts = [
                string_lit {value = "$(field.name) = "},
                call {
                    fn = qualified {name = "Show::show"},
                    args = record {fields = [
                        {"value", member_access {
                            obj = identifier {name = "value"},
                            member = field.name
                        }}
                    ]}
                }
            ]
        }
    }

    let fields_str = join_with_comma {strings = field_strings}

    let show_body = string_concat {
        parts = [
            string_lit {value = "$(type_name) {"},
            fields_str,
            string_lit {value = "}"}
        ]
    }

    emit_impl {
        trait = "Show",
        for_type = type_name,
        methods = [{
            name = "show",
            params = ["value"],
            body = show_body
        }]
    }
```

### Example: Deriving Multiple Traits

```mezze
type Point = {x: Int, y: Int}

comptime {eval = {} ->
    derive_eq {for_type = Point}
    derive_show {for_type = Point}
    derive_clone {for_type = Point}
}

# Or use a helper macro:
derive!(Eq, Show, Clone for Point)
```

### The derive Helper Macro

```mezze
macro derive: {tokens: TokenStream} -> {} = {tokens} ->
    # Parse: Trait1, Trait2, ... for TypeName
    match parse_derive_syntax {tokens} is
        Err {error} -> compile_error {msg = error}
        Ok {traits, type_name} ->
            # Generate comptime block
            let derive_calls = traits |> list::map {list = @, f = {trait} ->
                expr_stmt {expr = call {
                    fn = qualified {name = "derive_$(trait)"},
                    args = record {fields = [
                        {"for_type", identifier {name = type_name}}
                    ]}
                }}
            }

            call {
                fn = qualified {name = "comptime"},
                args = record {fields = [
                    {"eval", lambda {
                        params = [],
                        body = do_expr {
                            statements = derive_calls,
                            result = record {fields = []}
                        }
                    }}
                ]}
            }

# Usage:
derive!(Eq, Show, Clone for Point)

# Expands to:
comptime {eval = {} ->
    derive_eq {for_type = Point}
    derive_show {for_type = Point}
    derive_clone {for_type = Point}
}
```

## Advanced Reflection Examples

### Derive JSON Serialization

```mezze
comptime {eval = {} ->
    derive_to_json {for_type = User}
    derive_from_json {for_type = User}
}

let user = {name = "Bob", age = 25, email = "bob@example.com"}
let json = to_json {user}
# Returns: {"name": "Bob", "age": 25, "email": "bob@example.com"}

let parsed = from_json {json, type = User}
# Returns: User record
```

### Derive Lens Helpers

```mezze
type User = {name: String, address: Address}
type Address = {street: String, city: String}

comptime {eval = {} ->
    derive_lenses {for_type = User}
}

# Generated lenses:
# user_name_lens: Lens User String
# user_address_lens: Lens User Address

let user = {name = "Alice", address = {street = "Main St", city = "NYC"}}
let updated = lens_set {lens = user_name_lens, value = "Bob", in = user}
# Returns: {name = "Bob", address = {street = "Main St", city = "NYC"}}
```

### Generate Builder Pattern

```mezze
type Config = {
    host: String,
    port: Int,
    timeout: Int,
    retries: Int
}

comptime {eval = {} ->
    derive_builder {for_type = Config}
}

# Generated builder:
let config = config_builder {}
    |> with_host {value = "localhost"}
    |> with_port {value = 8080}
    |> with_timeout {value = 30}
    |> with_retries {value = 3}
    |> build {}
```

## CompileTime Constraints

Code executed with `comptime` has some constraints:

### 1. Pure Functions Only

```mezze
# ✅ Allowed: pure operations
comptime {eval = {} ->
    list::map {list = [1, 2, 3], f = {x} -> x * 2}
}

# ❌ Not allowed: effects at compile-time
comptime {eval = {} ->
    perform Log in Log.debug {msg = "compiling..."}  # Error!
}
```

**Exception:** File I/O effects are allowed for loading resources:
```mezze
# ✅ Allowed: reading files
comptime {eval = {} ->
    read_file {path = "config.json"}
}
```

### 2. Type-Level Operations

```mezze
# ✅ Allowed: reflection and type inspection
comptime {eval = {} ->
    derive_eq {for_type = Point}
}

# ✅ Allowed: generating implementations
comptime {eval = {} ->
    emit_impl {trait = "Show", for_type = "User", ...}
}
```

### 3. Compile Errors

```mezze
# Can emit compile errors
comptime {eval = {} ->
    match validate_schema {schema} is
        Err {error} -> compile_error {msg = "Invalid schema: $(error)"}
        Ok {} -> {}
}
```

## Best Practices

1. **Use for static data** - Load configs, resources at compile-time
2. **Generate boilerplate** - Use derive for repetitive code
3. **Validate early** - Check configurations at compile-time
4. **Keep it pure** - Avoid side effects (except file I/O)
5. **Clear errors** - Use `compile_error` for validation failures
6. **Document generated code** - Explain what derive generates

## Standard Derive Functions

Mezze's standard library provides these derive functions:

```mezze
# Equality
derive_eq: {for_type: Type} -> {}
derive_ne: {for_type: Type} -> {}

# Comparison
derive_ord: {for_type: Type} -> {}
derive_lt: {for_type: Type} -> {}
derive_le: {for_type: Type} -> {}
derive_gt: {for_type: Type} -> {}
derive_ge: {for_type: Type} -> {}

# String conversion
derive_show: {for_type: Type} -> {}
derive_debug: {for_type: Type} -> {}

# Copying
derive_clone: {for_type: Type} -> {}
derive_copy: {for_type: Type} -> {}

# Hashing
derive_hash: {for_type: Type} -> {}

# Serialization
derive_to_json: {for_type: Type} -> {}
derive_from_json: {for_type: Type} -> {}

# Default values
derive_default: {for_type: Type} -> {}
```

## Reflection API Reference

### Type Inspection

```mezze
type_info: {for_type: Type} -> TypeInfo
type_name: {type: Type} -> String
type_params: {type: Type} -> [String]
is_record: {type: Type} -> Bool
is_variant: {type: Type} -> Bool
is_function: {type: Type} -> Bool
is_generic: {type: Type} -> Bool
```

### Field/Constructor Access

```mezze
fields_of: {type: Type} -> [{name: String, type: Type}]
constructors_of: {type: Type} -> [{name: String, payload: Option Type}]
field_type: {type: Type, field: String} -> Option Type
constructor_payload: {type: Type, constructor: String} -> Option Type
```

### Code Generation

```mezze
emit_impl: {
    trait: String,
    for_type: String,
    methods: [{name: String, params: [String], body: Expression}]
} -> {}

emit_function: {
    name: String,
    params: [String],
    body: Expression
} -> {}

emit_type: {
    name: String,
    definition: TypeDefinition
} -> {}
```

## Summary

CompileTime and reflection provide:
- ✅ **Compile-time execution** - Run code during compilation
- ✅ **Type introspection** - Inspect types at compile-time
- ✅ **Code generation** - Generate trait implementations
- ✅ **Static validation** - Catch errors before runtime
- ✅ **Zero runtime cost** - No reflection overhead
- ✅ **Derive macros** - Automatic trait implementations

They enable powerful metaprogramming while maintaining Mezze's philosophy of explicitness and type safety.
