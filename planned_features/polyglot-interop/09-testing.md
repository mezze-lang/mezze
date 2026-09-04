# Testing Strategy

This document covers testing approaches for polyglot interop code in Mezze.

## Test Categories

### 1. Unit Tests (Pure Mezze)

Test Mezze logic independently from foreign code:

```mezze
# test/math_utils_test.mz

use testing

let test_normalize_data = {} ->
    let data = [1.0, 2.0, 3.0, 4.0, 5.0]
    let result = normalize { data = data }
    testing::assert_eq { expected = 5, actual = list::length { list = result } }
```

### 2. Integration Tests (With Mocks)

Mock foreign operations to test Mezze code that uses interop:

```mezze
# test/analysis_test.mz

use testing
use std::interop::foreign

# Mock handler that doesn't actually call Python
handler MockPython for Python {
    eval { code } -> Ok { val = foreign::from { val = 42.0 } }
    import_module { name } -> Ok { val = foreign::from { val = {} } }
    bind { name, value } -> Ok { val = {} }
}

let test_analysis_with_mock = {} ->
    perform MockPython in
        let result = analyze_data { data = [1.0, 2.0, 3.0] }
        match result is
            Ok { val } -> testing::assert { condition = val > 0.0 }
            Err { error } -> testing::fail { msg = "Should not fail" }
```

### 3. End-to-End Tests (Real Interop)

Test actual foreign code execution:

```mezze
# test/numpy_integration_test.mz

use testing
use python
use std::interop::foreign

let test_numpy_array_sum = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            let np = Python.import_module { name = "numpy" }
            match np is
                Ok { val = numpy } ->
                    Python.bind { name = "np", value = numpy }
                    Python.bind { name = "data", value = [1.0, 2.0, 3.0] }
                    let result = Python.eval { code = "np.array(data).sum()" }
                    match result is
                        Ok { val = sum } ->
                            let sum_float = foreign::to_float { val = sum }
                            match sum_float is
                                Ok { val = f } -> testing::assert_eq { expected = 6.0, actual = f }
                                Err { e } -> testing::fail { msg = "Conversion failed" }
                        Err { e } -> testing::fail { msg = "Eval failed" }
                Err { e } -> testing::fail { msg = "Import failed" }
    }
```

## Test Fixtures

### Shared Context Fixture

```mezze
# test/fixtures/python_fixture.mz

use python

pub let with_numpy_context: { f: { ctx: Context } -> a } -> a = { f } ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            let np = Python.import_module { name = "numpy" }
            match np is
                Ok { val = numpy } ->
                    Python.bind { name = "np", value = numpy }
                    f { ctx = ctx }
                Err { e } ->
                    testing::skip { reason = "numpy not available" }
    }
```

Usage:

```mezze
let test_with_fixture = {} ->
    with_numpy_context { ctx ->
        perform GraalPy { context = ctx } in
            Python.eval { code = "np.zeros(10)" }
    }
```

### Test Data Fixtures

```mezze
# test/fixtures/test_data.mz

pub let sample_array: [Float] = [1.0, 2.0, 3.0, 4.0, 5.0]

pub let sample_matrix: [[Float]] = [
    [1.0, 2.0, 3.0],
    [4.0, 5.0, 6.0],
    [7.0, 8.0, 9.0]
]
```

## Testing Error Handling

### Test Expected Errors

```mezze
let test_import_nonexistent_module = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            let result = Python.import_module { name = "nonexistent_module_xyz" }
            match result is
                Ok { val } -> testing::fail { msg = "Should have failed" }
                Err { error } ->
                    match error is
                        RuntimeError { message, ... } ->
                            testing::assert {
                                condition = string::contains { haystack = message, needle = "No module" }
                            }
                        _ -> testing::fail { msg = "Wrong error type" }
    }
```

### Test Timeout

```mezze
let test_timeout_enforced = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx, timeout = 100 } in  # 100ms
            let result = Python.eval { code = "while True: pass" }
            match result is
                Ok { val } -> testing::fail { msg = "Should have timed out" }
                Err { error } ->
                    match error is
                        Timeout { limit_ms, ... } ->
                            testing::assert_eq { expected = 100, actual = limit_ms }
                        _ -> testing::fail { msg = "Should be Timeout error" }
    }
```

### Test Memory Limit

```mezze
let test_memory_limit_enforced = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx, memory = 64 } in  # 64MB
            let result = Python.eval { code = "[0] * 100000000" }  # Try to allocate ~800MB
            match result is
                Ok { val } -> testing::fail { msg = "Should have exceeded memory" }
                Err { error } ->
                    match error is
                        MemoryExceeded { limit_mb, ... } ->
                            testing::assert_eq { expected = 64, actual = limit_mb }
                        _ -> testing::fail { msg = "Should be MemoryExceeded error" }
    }
```

## Testing Type Conversions

### Roundtrip Tests

```mezze
let test_int_roundtrip = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            Python.bind { name = "x", value = 42 }
            let result = Python.eval { code = "x" }
            match result is
                Ok { val = f } ->
                    let back = foreign::to_int { val = f }
                    match back is
                        Ok { val = i } -> testing::assert_eq { expected = 42, actual = i }
                        Err { e } -> testing::fail { msg = "Conversion failed" }
                Err { e } -> testing::fail { msg = "Eval failed" }
    }

let test_list_roundtrip = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            let original = [1.0, 2.0, 3.0]
            Python.bind { name = "data", value = original }
            let result = Python.eval { code = "data" }
            match result is
                Ok { val = f } ->
                    let back = foreign::to_list { val = f, convert = foreign::to_float }
                    match back is
                        Ok { val = lst } -> testing::assert_eq { expected = original, actual = lst }
                        Err { e } -> testing::fail { msg = "Conversion failed" }
                Err { e } -> testing::fail { msg = "Eval failed" }
    }
```

### Edge Case Tests

```mezze
let test_special_float_values = {} ->
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            # Test infinity
            let inf_result = Python.eval { code = "float('inf')" }
            match inf_result is
                Ok { val = f } ->
                    let inf = foreign::to_float { val = f }
                    match inf is
                        Ok { val = v } -> testing::assert { condition = float::is_infinite { val = v } }
                        Err { e } -> testing::fail { msg = "Inf conversion failed" }
                Err { e } -> testing::fail { msg = "Inf eval failed" }

            # Test NaN
            let nan_result = Python.eval { code = "float('nan')" }
            match nan_result is
                Ok { val = f } ->
                    let nan = foreign::to_float { val = f }
                    match nan is
                        Ok { val = v } -> testing::assert { condition = float::is_nan { val = v } }
                        Err { e } -> testing::fail { msg = "NaN conversion failed" }
                Err { e } -> testing::fail { msg = "NaN eval failed" }
    }
```

## Test Organization

### Directory Structure

```
project/
├── src/
│   └── lib.mz
├── test/
│   ├── unit/              # Pure Mezze tests
│   │   └── math_test.mz
│   ├── integration/       # Tests with mocks
│   │   └── analysis_test.mz
│   ├── e2e/              # Real interop tests
│   │   ├── python_test.mz
│   │   └── js_test.mz
│   └── fixtures/
│       ├── python_fixture.mz
│       └── test_data.mz
└── Mezze.toml
```

### Test Configuration

```toml
# Mezze.toml

[test]
include = ["test/**/*_test.mz"]
exclude = ["test/e2e/*"]  # Skip e2e by default

[test.e2e]
include = ["test/e2e/*_test.mz"]
requires = ["python", "javascript"]  # Skip if not available
```

## CI/CD Integration

### GitHub Actions Example

```yaml
# .github/workflows/test.yml

name: Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm'
      - run: mezze test test/unit

  integration-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm'
      - run: mezze test test/integration

  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm'
          components: 'python,js'
      - run: mezze sync
      - run: mezze test test/e2e
```

### Skip Tests When Language Unavailable

```mezze
let test_requires_python = {} ->
    if not python::is_available {} then
        testing::skip { reason = "Python not installed" }
    else
        python::scoped { ctx ->
            perform GraalPy { context = ctx } in
                # actual test
        }
```

## Performance Testing

### Benchmark Polyglot Overhead

```mezze
let benchmark_call_overhead = {} ->
    let iterations = 10000

    # Pure Mezze baseline
    let mezze_start = time::now {}
    let mezze_result = range { from = 0, to = iterations }
        |> list::fold { init = 0, f = { acc, _ } -> acc + 1 }
    let mezze_time = time::since { start = mezze_start }

    # Python interop
    python::scoped { ctx ->
        perform GraalPy { context = ctx } in
            Python.bind { name = "n", value = iterations }
            let py_start = time::now {}
            let py_result = Python.eval { code = "sum(1 for _ in range(n))" }
            let py_time = time::since { start = py_start }

            log { msg = "Mezze: $(mezze_time)ms, Python: $(py_time)ms" }
    }
```

## Test Utilities

### Helper Functions

```mezze
# test/utils.mz

pub let assert_foreign_eq: { expected: a, actual: Foreign, convert: Foreign -> Result a InteropError } -> {} =
    { expected, actual, convert } ->
        match convert { val = actual } is
            Ok { val = v } -> testing::assert_eq { expected = expected, actual = v }
            Err { e } -> testing::fail { msg = "Conversion failed: $(e)" }

pub let assert_python_returns: { ctx: Context, code: String, expected: a, convert: Foreign -> Result a InteropError } -> {} =
    { ctx, code, expected, convert } ->
        perform GraalPy { context = ctx } in
            let result = Python.eval { code = code }
            match result is
                Ok { val = f } -> assert_foreign_eq { expected = expected, actual = f, convert = convert }
                Err { e } -> testing::fail { msg = "Python eval failed: $(e)" }
```

## Checklist

- [ ] Unit tests don't depend on foreign languages
- [ ] Integration tests use mocks for isolation
- [ ] E2E tests handle missing language gracefully
- [ ] Error cases are tested (timeout, memory, runtime errors)
- [ ] Type conversions are tested (roundtrip, edge cases)
- [ ] CI skips e2e tests if languages not installed
- [ ] Performance benchmarks track interop overhead
