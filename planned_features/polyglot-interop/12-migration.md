# Migration Guide

This document covers how to add polyglot capabilities to existing Mezze projects and migrate between versions.

## Adding Polyglot to Existing Project

### Step 1: Update Mezze.toml

Add polyglot configuration:

```toml
# Mezze.toml

[package]
name = "my-project"
version = "1.0.0"

[dependencies]
# ... existing dependencies

# Add polyglot section
[polyglot.python]
interop = "https://github.com/mezze-lang/python/releases/v1.0.0.tar.gz"
extras = []  # Add packages as needed
```

### Step 2: Run Sync

```bash
$ mezze sync

📦 Syncing Mezze packages...
  ✓ Downloaded mezze-python-1.0.0

🌐 Polyglot: Python
  Installing python...
  ✓ Installed python

✓ Sync complete
```

### Step 3: Add Imports

```mezze
# src/lib.mz

use python                    # Interop library
use std::interop::foreign     # Foreign type utilities
```

### Step 4: Wrap Existing Logic

Before (pure Mezze):
```mezze
let compute_stats = { data: [Float] } -> { mean: Float, std: Float } =
    { data } ->
        let mean = list::sum { list = data } / list::length { list = data }
        let variance = data
            |> list::map { f = { x } -> (x - mean) * (x - mean) }
            |> list::sum
            |> { s } -> s / list::length { list = data }
        { mean = mean, std = float::sqrt { val = variance } }
```

After (with Python/NumPy for performance):
```mezze
let compute_stats = { data: [Float] } -> Result { mean: Float, std: Float } InteropError <Python> =
    { data } ->
        python::scoped { ctx ->
            perform GraalPy { context = ctx } in
                let np = Python.import_module { name = "numpy" }
                match np is
                    Ok { val = numpy } ->
                        Python.bind { name = "np", value = numpy }
                        Python.bind { name = "data", value = data }
                        let result = Python.eval { code = """
                            arr = np.array(data)
                            {'mean': float(arr.mean()), 'std': float(arr.std())}
                        """ }
                        match result is
                            Ok { val = r } ->
                                let mean = foreign::get { obj = r, member = "mean" }
                                let std = foreign::get { obj = r, member = "std" }
                                # Convert and return
                                match (mean, std) is
                                    (Ok { val = m }, Ok { val = s }) ->
                                        match (foreign::to_float { val = m }, foreign::to_float { val = s }) is
                                            (Ok { val = mf }, Ok { val = sf }) ->
                                                Ok { val = { mean = mf, std = sf } }
                                            _ -> Err { error = ConversionError { from_type = "dict", to_type = "record", message = "Failed to convert" } }
                                    _ -> Err { error = MemberNotFound { object = r, member = "mean/std" } }
                            Err { e } -> Err { error = e }
                    Err { e } -> Err { error = e }
        }
```

### Step 5: Update Call Sites

Before:
```mezze
let stats = compute_stats { data = my_data }
log { msg = "Mean: $(stats.mean)" }
```

After:
```mezze
let result = compute_stats { data = my_data }
match result is
    Ok { val = stats } -> log { msg = "Mean: $(stats.mean)" }
    Err { error } -> log { msg = "Error: $(error)" }
```

## Adding Additional Languages

### Adding JavaScript

```toml
# Mezze.toml

[polyglot.python]
interop = "https://github.com/mezze-lang/python/..."
extras = ["numpy"]

# Add JavaScript
[polyglot.javascript]
interop = "https://github.com/mezze-lang/javascript/..."
extras = ["lodash"]
```

```bash
$ mezze sync

🌐 Polyglot: Python
  ✓ python already installed

🌐 Polyglot: JavaScript
  Installing js...
  ✓ Installed js
  Installing extras...
    ✓ Installed lodash

✓ Sync complete
```

### Using Multiple Languages

```mezze
use python
use javascript

# Use Python for data processing
let processed = python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.bind { name = "data", value = raw_data }
        Python.eval { code = "process(data)" }
}

# Use JavaScript for formatting
let formatted = javascript::scoped { ctx ->
    perform GraalJS { context = ctx } in
        JavaScript.bind { name = "data", value = processed }
        JavaScript.eval { code = "format(data)" }
}
```

## Migrating Between Versions

### Checking Current Version

```bash
$ mezze --version
mezze 0.5.0
```

### Version Compatibility

| Mezze Version | Polyglot API | Notes |
|--------------|--------------|-------|
| 0.5.x | v1 | Initial release |
| 0.6.x | v1 | Compatible with 0.5.x |
| 1.0.x | v2 | Breaking changes (see below) |

### Breaking Changes in v2 (Hypothetical)

#### Handler Syntax Change

v1:
```mezze
perform GraalPy { context = ctx } in
    Python.eval { code = "..." }
```

v2:
```mezze
perform python::handler { context = ctx } in
    python::eval { code = "..." }
```

**Migration:** Find and replace handler names.

#### Error Type Refinement

v1:
```mezze
Err { error = RuntimeError { message = "...", ... } }
```

v2:
```mezze
Err { error = Python.RuntimeError { message = "...", traceback = "...", ... } }
```

**Migration:** Update pattern matching for errors.

### Migration Script

```bash
# Hypothetical migration tool
$ mezze migrate --from 0.5 --to 1.0

Scanning project...
Found 15 files using polyglot

Migrations needed:
  - src/analysis.mz:23 - Update handler syntax
  - src/analysis.mz:45 - Update error pattern
  - src/utils.mz:12 - Update handler syntax

Apply migrations? [y/N] y

✓ Applied 3 migrations
✓ Updated Mezze.toml
✓ Run 'mezze sync' to update dependencies
```

## Gradual Migration Strategy

### Phase 1: Add Polyglot Alongside Pure Mezze

Keep pure Mezze implementations, add polyglot alternatives:

```mezze
# Pure Mezze (existing)
let compute_stats_pure = { data } -> ...

# Polyglot (new)
let compute_stats_numpy = { data } -> ...

# Configurable
let compute_stats = { data, use_numpy: Bool } ->
    if use_numpy then
        compute_stats_numpy { data = data }
    else
        Ok { val = compute_stats_pure { data = data } }
```

### Phase 2: Test and Benchmark

Compare implementations:

```mezze
let benchmark = {} ->
    let data = generate_test_data { size = 100000 }

    let pure_start = time::now {}
    let pure_result = compute_stats_pure { data = data }
    let pure_time = time::since { start = pure_start }

    let numpy_start = time::now {}
    let numpy_result = compute_stats_numpy { data = data }
    let numpy_time = time::since { start = numpy_start }

    log { msg = "Pure Mezze: $(pure_time)ms" }
    log { msg = "NumPy: $(numpy_time)ms" }
```

### Phase 3: Switch Default

```mezze
# Now default to polyglot
let compute_stats = compute_stats_numpy

# Keep pure version for fallback
let compute_stats_fallback = compute_stats_pure
```

### Phase 4: Remove Pure Implementation

Once confident, remove the pure Mezze version if polyglot is always faster/better.

## Rollback Strategy

### Keep Mezze.lock Committed

```bash
# Before migration
$ cp Mezze.lock Mezze.lock.backup

# After migration, if problems
$ cp Mezze.lock.backup Mezze.lock
$ mezze sync
```

### Feature Flags

```mezze
let USE_POLYGLOT = config::get { key = "use_polyglot", default = true }

let compute = { data } ->
    if USE_POLYGLOT then
        compute_with_python { data = data }
    else
        compute_pure_mezze { data = data }
```

Disable polyglot if issues:
```bash
$ MEZZE_CONFIG_use_polyglot=false mezze run src/main.mz
```

## CI/CD Considerations

### Update CI Pipeline

```yaml
# .github/workflows/ci.yml

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      # Use GraalVM instead of regular JDK
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm'
          components: 'python,js'  # Add needed languages

      # Sync dependencies including polyglot
      - run: mezze sync

      # Run tests
      - run: mezze test
```

### Cache Dependencies

```yaml
- name: Cache .deps
  uses: actions/cache@v3
  with:
    path: .deps
    key: deps-${{ hashFiles('Mezze.lock') }}
```

## Checklist

### Before Migration

- [ ] Back up Mezze.lock
- [ ] Review polyglot API documentation
- [ ] Identify functions to migrate
- [ ] Plan test strategy

### During Migration

- [ ] Add polyglot configuration to Mezze.toml
- [ ] Run mezze sync
- [ ] Add imports to affected files
- [ ] Update function signatures (add effect, Result type)
- [ ] Update call sites to handle Result
- [ ] Add tests for error cases

### After Migration

- [ ] Run all tests
- [ ] Benchmark performance
- [ ] Update CI pipeline
- [ ] Document changes
- [ ] Commit Mezze.lock

## Getting Help

If you encounter issues during migration:

1. Check [Troubleshooting Guide](11-troubleshooting.md)
2. Search [GitHub Issues](https://github.com/mezze-lang/mezze/issues)
3. Ask in [Discussions](https://github.com/mezze-lang/mezze/discussions)
