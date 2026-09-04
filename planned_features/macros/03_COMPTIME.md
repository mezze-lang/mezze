# CompileTime Execution

Execute arbitrary Mezze code at compile-time using the `CompileTime` effect.

## Concept

Like Zig's `comptime`, the `CompileTime` effect allows running any Mezze code during compilation rather than at runtime. This is useful for:

- Computing constants from complex logic
- Loading configuration files
- Generating lookup tables
- Performing expensive computations once

## Effect Definition

```mezze
# In std/macro/comptime.mz

pub effect CompileTime having 'result where {
    eval: {code: {} -> 'result} -> 'result <CompileTime>
}
```

## Default Handler

```mezze
# Execute immediately at compile-time
pub impl CompileTime for Execute having 'result = 'a where {
    eval = {code} -> code {}
}
```

## Basic Usage

```mezze
# Compute at compile-time
let size = CompileTime.eval {code = {} -> 1024 * 1024}
# size = 1048576 (computed during compilation)

# Load config at compile-time
let config = CompileTime.eval {code = {} ->
    read_file {path = "config.json"} |> parse_json {@}
}

# Generate lookup table
let sin_table = CompileTime.eval {code = {} ->
    range {from = 0, to = 360} |> list::map {list = @, f = {deg ->
        sin {radians = deg * 3.14159 / 180.0}
    }}
}
```

## Restrictions

Code executed at compile-time has limitations:

1. **Pure functions only** - No I/O effects (unless using special compile-time handlers)
2. **Known dependencies** - All imported modules must be available
3. **Terminates** - Infinite loops will hang the compiler
4. **No runtime values** - Can't access runtime-only data

## Example: Compile-Time Configuration

```mezze
# config.json file at compile-time
let app_config = CompileTime.eval {code = {} ->
    let json_str = read_file {path = "config.json"}
    parse_json {json = json_str}
}

# Use configuration values
let server_port = app_config.server.port  # Known at compile-time
let db_host = app_config.database.host
```

## Example: Pre-Computed Lookup Tables

```mezze
# Generate prime numbers at compile-time
let primes_under_1000 = CompileTime.eval {code = {} ->
    sieve_of_eratosthenes {limit = 1000}
}

# Use in runtime code
let is_prime = {n} ->
    list::contains {list = primes_under_1000, item = n}
```

## Example: Build-Time Code Generation

```mezze
# Generate boilerplate at compile-time
let http_methods = CompileTime.eval {code = {} ->
    ["GET", "POST", "PUT", "DELETE", "PATCH"]
}

# Could use this to generate handler functions, etc.
```

## Advanced: Conditional Compilation

```mezze
let debug_mode = CompileTime.eval {code = {} ->
    get_env {var = "DEBUG"} == "true"
}

let log = {msg} ->
    if debug_mode
    then Console.println {msg}
    else {}  # No-op in release builds
```

Since `debug_mode` is compile-time constant, the compiler could optimize away the branch.

## Interaction with Other Macros

CompileTime can be used inside pattern macros:

```mezze
impl PatternMacro for Env having 'result = Expression where {
    expand = {tokens} -> do
        let var_name = parse_string_literal {tokens}

        # Read environment variable at compile-time
        let value = CompileTime.eval {code = {} ->
            get_env {var = var_name}
        }

        # Return as string literal
        string_lit {value}
}

# Usage:
let api_key = Env!("API_KEY")  # Reads env var during compilation
```

## Compile-Time Effects

Some effects may have special compile-time handlers:

```mezze
# FileSystem effect with compile-time handler
impl FileSystem for CompileTime having ... where {
    read = {path} ->
        # Read file during compilation
        __native_compile_time_read_file {path}

    write = {path, content} ->
        # Write file during compilation (code generation!)
        __native_compile_time_write_file {path, content}
}
```

**Use case:** Code generation - macros that write generated code to files.

## Debugging Compile-Time Code

Errors during compile-time execution are reported as compile errors:

```mezze
let x = CompileTime.eval {code = {} ->
    1 / 0  # Runtime error during compilation!
}

# Compiler error:
# Error: Division by zero during compile-time evaluation
#   at line 2: 1 / 0
```

## Performance Considerations

**Benefits:**
- Work done once at compile-time, not every run
- Smaller runtime code (constants vs computations)
- Faster startup (no loading/parsing at runtime)

**Costs:**
- Longer compilation time
- More memory during compilation
- Re-compiles when inputs change

**Best practices:**
- Use for expensive one-time computations
- Don't overuse - balance compile-time vs runtime costs
- Cache results in build artifacts when possible

## Comparison with Other Languages

**Zig:**
```zig
const size = comptime fibonacci(10);
```

**Rust:**
```rust
const SIZE: usize = compute_size();  // Only const fn allowed
```

**Mezze:**
```mezze
let size = CompileTime.eval {code = {} -> fibonacci {n = 10}}
# Any pure code allowed!
```

Mezze is more flexible - any pure function can run at compile-time.

## Future Extensions

Possible future features:

1. **Compile-time reflection** - Inspect types, AST at compile-time
2. **Build scripts** - Use CompileTime for pre-build steps
3. **Optimizations** - Partial evaluation of compile-time constants
4. **Cross-compilation** - Execute for target platform during compilation

## Best Practices

1. **Keep it pure** - Avoid side effects in compile-time code
2. **Make it fast** - Slow compile-time code = slow compilation
3. **Handle errors** - Provide good error messages for failures
4. **Document dependencies** - Make it clear what files/env vars are needed
5. **Test separately** - Unit test compile-time functions as normal functions

## Limitations

1. **No infinite loops** - Will hang the compiler
2. **Memory limits** - Large compile-time computations may exhaust memory
3. **No dynamic loading** - Can't load plugins/libraries at compile-time
4. **Deterministic** - Should produce same result every time (for build reproducibility)

## Summary

CompileTime effect enables:
- ✅ Execute any pure Mezze code during compilation
- ✅ Pre-compute constants and lookup tables
- ✅ Load configuration files at compile-time
- ✅ Generate code based on compile-time inputs
- ✅ Flexible and powerful compared to const functions

The key insight: **It's just an effect!** No special syntax needed beyond the effect system.
