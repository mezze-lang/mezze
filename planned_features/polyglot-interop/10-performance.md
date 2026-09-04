# Performance Considerations

This document covers performance optimization strategies for polyglot interop in Mezze.

## Understanding Overhead

### Sources of Overhead

1. **Context creation** - Creating a GraalVM context is expensive (100-500ms)
2. **Value marshalling** - Converting between Mezze and foreign types
3. **Boundary crossing** - Each call into/from foreign code has overhead
4. **JIT warmup** - Truffle needs time to optimize cross-language calls

### Typical Costs

| Operation | Approximate Cost |
|-----------|-----------------|
| Context creation | 100-500ms |
| Simple eval | 0.1-1ms (cold), <0.01ms (warm) |
| Value conversion (primitive) | ~0.001ms |
| Value conversion (array 1000 elements) | ~0.1ms |
| Function callback (Mezze -> Python -> Mezze) | ~0.01ms |

## Context Management

### Reuse Contexts

**Bad** - Creating context per operation:

```mezze
# Creates and destroys context every call
let process_item = { item } ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            Python.eval { code = "process($(item))" }
    }

# 1000 context creations!
list::map { list = items, f = process_item }
```

**Good** - Share context across operations:

```mezze
# Single context for all operations
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        list::map { list = items, f = { item } ->
            Python.eval { code = "process($(item))" }
        }
}
```

### Context Pooling (Advanced)

For high-throughput scenarios, maintain a pool of warm contexts:

```mezze
# Context pool (conceptual)
let pool = context_pool::create { language = "python", size = 4 }

# Borrow from pool instead of creating
let result = context_pool::with_context { pool = pool, f = { ctx } ->
    perform GraalPy { context = ctx } in
        Python.eval { code = "expensive_computation()" }
}
```

## Batching Operations

### Batch Eval Calls

**Bad** - Many small evals:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let a = Python.eval { code = "compute_a()" }
        let b = Python.eval { code = "compute_b()" }
        let c = Python.eval { code = "compute_c()" }
        # 3 boundary crossings
}
```

**Good** - Single batched eval:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let result = Python.eval { code = """
            a = compute_a()
            b = compute_b()
            c = compute_c()
            (a, b, c)
        """ }
        # 1 boundary crossing
}
```

### Batch Data Transfers

**Bad** - Transfer items one by one:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        list::for_each { list = items, f = { item } ->
            Python.bind { name = "item", value = item }
            Python.eval { code = "process(item)" }
        }
        # N boundary crossings
}
```

**Good** - Transfer entire array:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.bind { name = "items", value = items }
        Python.eval { code = "[process(item) for item in items]" }
        # 1 boundary crossing
}
```

## JIT Warmup

### Warmup Strategy

Truffle JIT compiles hot paths. Allow warmup before measuring performance:

```mezze
let benchmark = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            # Warmup phase (don't measure)
            range { from = 0, to = 1000 }
                |> list::for_each { f = { _ } ->
                    Python.eval { code = "1 + 1" }
                }

            # Measurement phase
            let start = time::now {}
            range { from = 0, to = 10000 }
                |> list::for_each { f = { _ } ->
                    Python.eval { code = "1 + 1" }
                }
            let elapsed = time::since { start = start }

            log { msg = "10000 evals in $(elapsed)ms" }
    }
```

### Polyglot Inlining

After warmup, Truffle can inline across language boundaries. Keep hot code paths consistent:

```mezze
# This function will be inlined into Python after warmup
let double = { n: Int } -> n * 2

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.bind { name = "double", value = double }

        # Hot loop - double will be inlined
        Python.eval { code = """
            result = 0
            for i in range(10000):
                result += double(i)  # Inlined after warmup
            result
        """ }
}
```

## Data Transfer Optimization

### Avoid Unnecessary Conversions

**Bad** - Convert to Mezze just to pass back:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let np_array = Python.eval { code = "np.zeros(1000000)" }
        match np_array is
            Ok { val = arr } ->
                # Don't convert to Mezze list just to pass back!
                let mezze_list = foreign::to_list { val = arr, convert = foreign::to_float }
                match mezze_list is
                    Ok { val = lst } ->
                        Python.bind { name = "data", value = lst }
                        Python.eval { code = "process(data)" }
                    Err { e } -> Err { error = e }
            Err { e } -> Err { error = e }
}
```

**Good** - Keep data in foreign land:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        # Keep as Foreign, don't convert
        let np_array = Python.eval { code = "np.zeros(1000000)" }
        match np_array is
            Ok { val = arr } ->
                Python.bind { name = "data", value = arr }  # Pass Foreign directly
                Python.eval { code = "process(data)" }
            Err { e } -> Err { error = e }
}
```

### Use Typed Arrays

GraalVM can share memory for typed arrays without copying:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        # Python numpy array can share memory with Mezze
        Python.eval { code = """
            import numpy as np
            # Using dtype for memory-efficient sharing
            data = np.array([1.0, 2.0, 3.0], dtype=np.float64)
        """ }
}
```

## Async Operations

### Don't Block on IO

**Bad** - Blocking on network in main context:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        # Blocks entire context while waiting
        Python.eval { code = "requests.get('https://api.example.com')" }
}
```

**Good** - Use async patterns:

```mezze
# Conceptual - actual implementation depends on Mezze's async model
let fetch_async = { url } ->
    async {
        python::scoped { ctx ->
            perform GraalPy { context = ctx } in
                Python.eval { code = "requests.get('$(url)')" }
        }
    }

# Launch multiple requests concurrently
let results = await_all {
    requests = [
        fetch_async { url = "https://api1.example.com" },
        fetch_async { url = "https://api2.example.com" },
        fetch_async { url = "https://api3.example.com" }
    ]
}
```

## Memory Optimization

### Limit Context Memory

Prevent memory bloat in long-running contexts:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx, memory = 512 } in  # 512MB limit
        # Large computation constrained to 512MB
        Python.eval { code = "process_large_dataset()" }
}
```

### Clear Bindings

Remove unused bindings to allow garbage collection:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        # Create large data
        Python.bind { name = "large_data", value = huge_array }
        Python.eval { code = "result = process(large_data)" }

        # Clear binding to free memory
        Python.eval { code = "del large_data" }

        # Continue with result
        Python.eval { code = "analyze(result)" }
}
```

## Profiling

### GraalVM VisualVM

Use GraalVM's profiler to identify bottlenecks:

```bash
# Run with profiling
mezze run --jvm --vm.agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005

# Connect VisualVM to localhost:5005
```

### Truffle Profiler

```bash
# CPU profiling
mezze run --jvm --experimental-options --cpusampler src/main.mz

# Memory profiling
mezze run --jvm --experimental-options --memtracer src/main.mz
```

### Manual Timing

```mezze
let timed = { name, f } ->
    let start = time::now {}
    let result = f {}
    let elapsed = time::since { start = start }
    log { msg = "$(name): $(elapsed)ms" }
    result

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        timed { name = "import numpy", f = {} ->
            Python.import_module { name = "numpy" }
        }

        timed { name = "compute", f = {} ->
            Python.eval { code = "np.random.rand(10000, 10000)" }
        }
}
```

## Best Practices Summary

| Do | Don't |
|----|-------|
| Reuse contexts across operations | Create context per call |
| Batch multiple evals into one | Make many small eval calls |
| Keep large data in foreign land | Convert large arrays back and forth |
| Allow JIT warmup before measuring | Benchmark cold code |
| Use memory limits for untrusted code | Allow unbounded memory |
| Profile to find real bottlenecks | Optimize prematurely |

## Performance Checklist

- [ ] Context created once and reused
- [ ] Operations batched when possible
- [ ] Large data stays in foreign language
- [ ] JIT warmup considered for benchmarks
- [ ] Memory limits set appropriately
- [ ] Async used for IO operations
- [ ] Profiling done to identify actual bottlenecks
