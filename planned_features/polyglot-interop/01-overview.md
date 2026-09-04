# Polyglot Interop System Overview

## Purpose

Enable Mezze programs to call code written in other GraalVM/Truffle languages (Python, JavaScript, Java, R, Ruby) with:
- Type-safe effect tracking
- Crash isolation
- Unified dependency management
- Ergonomic API

## Quick Example

```mezze
use python
use std::interop::foreign

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let np = Python.import_module { name = "numpy" }
        match np is
            Ok { val = numpy } ->
                Python.bind { name = "np", value = numpy }
                Python.bind { name = "data", value = [1.0, 2.0, 3.0, 4.0, 5.0] }
                let result = Python.eval { code = "np.array(data).sum()" }
                match result is
                    Ok { val = sum } -> foreign::to_float { val = sum }
                    Err { error } -> Err { error = error }
            Err { error } -> Err { error = error }
}
```

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                        User Code                            │
│  python::scoped { ctx ->                                    │
│      perform GraalPy { context = ctx } in                   │
│          numpy::array { data = [1.0, 2.0, 3.0] }           │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Wrapper Libraries                         │
│  mezze-numpy, mezze-pandas, mezze-lodash                       │
│  Mezze-friendly API over foreign code                        │
│  Declares foreign package dependencies                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    Interop Libraries                         │
│  mezze-python, mezze-js, mezze-java, mezze-r, mezze-ruby         │
│  Effect + Handler definitions                               │
│  Context management (create, close, scoped)                 │
│  Declares package manager configuration                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     std/interop/                            │
│  foreign.mz - Foreign type, conversions, errors           │
│  Core types shared by all interop libraries                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Mezze Runtime (Interpreter)                │
│  __native_polyglot_* functions                              │
│  GraalVM context management                                 │
│  Exception wrapping, timeouts, memory limits                │
│  Value conversion (Mezze ↔ Foreign)                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      GraalVM Truffle                        │
│  InteropLibrary for cross-language values                   │
│  Language contexts (GraalPy, GraalJS, etc.)                 │
└─────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### 1. Interop is an Effect

Foreign code is effectful - it can do IO, network, random, mutation, anything. We don't know what's happening inside, so we must track it as an effect.

```mezze
# Functions calling interop must declare the effect
let process: { data: [Float] } -> Result NDArray InteropError <Python> = { data } ->
    ...
```

### 2. Separate Effects per Language

Each language has its own effect because they have different capabilities:
- Python: `import_module`
- JavaScript: `require`
- Java: `import_class`, `new_instance`

### 3. Explicit Context Management

Users control context lifecycle for performance:

```mezze
# Scoped (auto-managed, safe)
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.eval { code = "1 + 1" }
}

# Manual (full control)
let ctx = python::create_context {}
perform GraalPy { context = ctx } in ...
perform GraalPy { context = ctx } in ...  # Reuse
python::close_context { context = ctx }
```

### 4. Handler Configuration

Handlers accept timeout and memory limits:

```mezze
perform GraalPy { context = ctx, timeout = 5000, memory = 256 } in
    Python.eval { code = "heavy_computation()" }
```

### 5. Unified Dependency Management

All dependencies (Mezze + foreign) managed through Mezze.toml:

```toml
[dependencies]
mezze-numpy = "https://github.com/mezze-lang/numpy/..."

[polyglot.python]
interop = "https://github.com/mezze-lang/python/..."
extras = ["requests>=2.28.0", "pandas==2.0.0"]
```

Installed to `.deps/`:

```
.deps/
├── mezze/           # Mezze packages
├── python/         # pip packages
├── node/           # npm packages
└── java/           # JARs
```

### 6. Crash Isolation

Foreign code runs in isolated GraalVM contexts. All exceptions are caught and wrapped in `InteropError`. Timeouts and memory limits prevent runaway foreign code.

## Documents in This Series

| Document | Description |
|----------|-------------|
| [01-overview.md](01-overview.md) | This document - architecture and key decisions |
| [02-dependency-management.md](02-dependency-management.md) | Mezze.toml schema, `mezze sync`, `.deps/` structure |
| [03-stdlib-foreign.md](03-stdlib-foreign.md) | `std/interop/foreign.mz` types and functions |
| [04-interop-libraries.md](04-interop-libraries.md) | Effect and handler design for each language |
| [05-runtime-implementation.md](05-runtime-implementation.md) | Truffle interpreter changes, native functions |
| [06-cli-changes.md](06-cli-changes.md) | CLI commands for polyglot support |
| [07-typechecker-changes.md](07-typechecker-changes.md) | Type system additions for polyglot |
| [08-security.md](08-security.md) | Sandbox policies, resource limits, security checklist |
| [09-testing.md](09-testing.md) | Testing strategies for polyglot code |
| [10-performance.md](10-performance.md) | Performance optimization tips |
| [11-troubleshooting.md](11-troubleshooting.md) | Common errors and solutions |
| [12-migration.md](12-migration.md) | Adding polyglot to existing projects |

## Usage Patterns

### Simple one-shot evaluation

```mezze
use python

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.eval { code = "1 + 1" }
}
```

### With bindings

```mezze
use python

let x = 10
let y = 20

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.eval_with { code = "x + y", bindings = { x = x, y = y } }
}
```

### Using a library wrapper

```mezze
use python
use numpy

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let arr = numpy::array { data = [1.0, 2.0, 3.0] }
        numpy::sum { arr = arr }
}
```

### Shared context across perform blocks

```mezze
use python

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.import_module { name = "numpy" }
        Python.bind { name = "data", value = large_dataset }

    # ... some Mezze computation ...

    perform GraalPy { context = ctx } in
        Python.eval { code = "process(data)" }  # data still exists
}
```

### Passing Mezze functions to foreign code

```mezze
use python

let double = { n: Int } -> n * 2

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.bind { name = "double", value = double }
        Python.eval { code = "double(21)" }  # Returns 42
}
```

### Error handling

```mezze
use python

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let result = Python.eval { code = "1 / 0" }
        match result is
            Ok { val } -> val
            Err { error } ->
                match error is
                    RuntimeError { message, stack, ... } ->
                        log { msg = "Python error: $(message)" }
                    Timeout { limit_ms, ... } ->
                        log { msg = "Timed out" }
                    _ -> {}
                error
}
```

## Future: Macro Sugar

With macros, we can provide cleaner syntax:

```mezze
# Current (verbose)
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.eval_with { code = "x + y", bindings = { x = 10, y = 20 } }
}

# Future (with macro)
py!("$(x) + $(y)")
```

See [docs/macros/](../macros/) for the macro system design.
