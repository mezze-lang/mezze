# Troubleshooting Guide

This document covers common issues and solutions when using polyglot interop in Mezze.

## Installation Issues

### GraalVM Not Found

**Symptom:**
```
$ mezze sync

Error: GraalVM Updater (gu) not found
```

**Cause:** GraalVM is not installed or not in PATH.

**Solution:**

1. Install GraalVM from https://www.graalvm.org/downloads/

2. Set GRAALVM_HOME:
   ```bash
   export GRAALVM_HOME=/path/to/graalvm
   export PATH=$GRAALVM_HOME/bin:$PATH
   ```

3. Verify installation:
   ```bash
   $ gu --version
   GraalVM Updater ...
   ```

### Language Component Not Installed

**Symptom:**
```
Error: Language 'python' not available
```

**Cause:** GraalVM language component not installed.

**Solution:**
```bash
# Install manually
$ gu install python

# Or let mezze sync install it
$ mezze sync
```

### pip/npm Not Found

**Symptom:**
```
$ mezze sync

Installing extras...
  Error: pip not found
```

**Cause:** Package manager not available in GraalVM environment.

**Solution:**

For Python:
```bash
# GraalPy includes pip, but ensure it's accessible
$ $GRAALVM_HOME/bin/graalpy -m pip --version
```

For JavaScript:
```bash
# GraalJS uses npm from Node.js
$ npm --version
```

### Package Installation Fails

**Symptom:**
```
$ mezze sync

Installing extras...
  Error: Could not find a version that satisfies the requirement nonexistent-package
```

**Cause:** Package doesn't exist or version constraint can't be satisfied.

**Solution:**

1. Check package name spelling in Mezze.toml
2. Check version constraints are valid
3. Try installing manually to see detailed error:
   ```bash
   $ $GRAALVM_HOME/bin/graalpy -m pip install package-name
   ```

## Runtime Errors

### LanguageNotAvailable

**Symptom:**
```mezze
Err { error = LanguageNotAvailable { language = "python", reason = "..." } }
```

**Cause:** Language runtime not available at runtime.

**Solution:**

1. Ensure language is installed: `gu list`
2. Ensure running with GraalVM: `mezze run --jvm`
3. Check Mezze.toml has the language configured

### RuntimeError from Foreign Code

**Symptom:**
```mezze
Err { error = RuntimeError { language = "python", message = "NameError: name 'foo' is not defined", stack = "..." } }
```

**Cause:** Error in foreign code execution.

**Solution:**

1. Check the Python/JS code syntax
2. Ensure all variables are bound before use
3. Ensure modules are imported

**Example fix:**
```mezze
# Wrong - np not defined
Python.eval { code = "np.array([1,2,3])" }

# Right - import first
let np = Python.import_module { name = "numpy" }
Python.bind { name = "np", value = np }
Python.eval { code = "np.array([1,2,3])" }
```

### ConversionError

**Symptom:**
```mezze
Err { error = ConversionError { from_type = "numpy.ndarray", to_type = "Int", message = "..." } }
```

**Cause:** Trying to convert incompatible types.

**Solution:**

1. Check the actual type of the foreign value
2. Use appropriate conversion function
3. Consider keeping value as Foreign if not needed in Mezze

**Example:**
```mezze
# Wrong - array can't convert to Int
let arr = Python.eval { code = "np.array([1,2,3])" }
foreign::to_int { val = arr }  # Error!

# Right - convert to list
foreign::to_list { val = arr, convert = foreign::to_int }
```

### MemberNotFound

**Symptom:**
```mezze
Err { error = MemberNotFound { object = <Foreign>, member = "nonexistent" } }
```

**Cause:** Accessing member that doesn't exist on foreign object.

**Solution:**

1. Check member name spelling
2. Check the object type has that member
3. For Python, ensure the attribute exists

**Debug by checking type:**
```mezze
let obj = Python.eval { code = "some_object" }
let type_name = Python.eval { code = "type(some_object).__name__" }
# Check what members exist
let members = Python.eval { code = "dir(some_object)" }
```

### Timeout

**Symptom:**
```mezze
Err { error = Timeout { operation = "eval", limit_ms = 5000 } }
```

**Cause:** Foreign code took too long to execute.

**Solution:**

1. Increase timeout if computation is legitimately slow:
   ```mezze
   perform GraalPy { context = ctx, timeout = 60000 } in  # 60 seconds
       Python.eval { code = slow_computation }
   ```

2. Optimize the foreign code
3. Check for infinite loops in foreign code

### MemoryExceeded

**Symptom:**
```mezze
Err { error = MemoryExceeded { operation = "eval", limit_mb = 256 } }
```

**Cause:** Foreign code used too much memory.

**Solution:**

1. Increase memory limit:
   ```mezze
   perform GraalPy { context = ctx, memory = 1024 } in  # 1GB
       Python.eval { code = memory_intensive }
   ```

2. Process data in smaller chunks
3. Clear unused variables in foreign code

### InvalidContext

**Symptom:**
```mezze
Err { error = InvalidContext { reason = "Context is closed" } }
```

**Cause:** Using a context that has been closed.

**Solution:**

Use `scoped` to ensure context stays valid:
```mezze
# Wrong - context escapes scope
let ctx = python::create_context {}
python::close_context { context = ctx }
perform GraalPy { context = ctx } in  # Error! Context closed
    Python.eval { code = "1 + 1" }

# Right - use scoped
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.eval { code = "1 + 1" }
}  # Context auto-closed here
```

## Type Checking Errors

### Missing Effect Declaration

**Symptom:**
```
Error: Function uses Python effect but doesn't declare it
  --> src/app.mz:15:1
```

**Cause:** Function calls polyglot operations but doesn't declare the effect.

**Solution:**

Add effect to function signature:
```mezze
# Wrong
let process = { data } ->
    Python.eval { code = "..." }

# Right
let process: { data: [Float] } -> Result Foreign InteropError <Python> = { data } ->
    Python.eval { code = "..." }
```

### Foreign Type Access

**Symptom:**
```
Error: Cannot access member 'name' on Foreign type
  --> src/app.mz:10:5
```

**Cause:** Trying to use dot notation on Foreign value.

**Solution:**

Use `foreign::get` instead:
```mezze
# Wrong
let name = foreign_obj.name

# Right
let name = foreign::get { obj = foreign_obj, member = "name" }
```

### Type Mismatch with Foreign

**Symptom:**
```
Error: Type mismatch
  Expected: Int
  Found: Foreign
```

**Cause:** Using Foreign value where concrete type expected.

**Solution:**

Convert explicitly:
```mezze
# Wrong
let x: Int = Python.eval { code = "42" }

# Right
let result = Python.eval { code = "42" }
match result is
    Ok { val = f } ->
        match foreign::to_int { val = f } is
            Ok { val = x } -> # x is Int here
            Err { e } -> # handle error
    Err { e } -> # handle error
```

## Common Pitfalls

### Binding Order

**Symptom:** "NameError: name 'x' is not defined"

**Cause:** Using variable before binding.

```mezze
# Wrong - uses x before binding
Python.eval { code = "print(x)" }
Python.bind { name = "x", value = 42 }

# Right - bind first
Python.bind { name = "x", value = 42 }
Python.eval { code = "print(x)" }
```

### Module Import Once

**Symptom:** Slow performance from repeated imports.

```mezze
# Wrong - imports every iteration
list::map { list = data, f = { item } ->
    let np = Python.import_module { name = "numpy" }
    # ...
}

# Right - import once
let np = Python.import_module { name = "numpy" }
Python.bind { name = "np", value = np }
list::map { list = data, f = { item } ->
    # np already available
}
```

### String Interpolation in Code

**Symptom:** Code injection vulnerabilities or syntax errors.

```mezze
# Dangerous - user input in code string
let user_input = get_input {}
Python.eval { code = "process('$(user_input)')" }  # Injection risk!

# Safe - use bindings
Python.bind { name = "data", value = user_input }
Python.eval { code = "process(data)" }
```

## Debugging Tips

### Enable Verbose Logging

```bash
# Run with debug output
MEZZE_DEBUG=polyglot mezze run src/main.mz
```

### Check Foreign Value Type

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let obj = Python.eval { code = "some_object" }
        match obj is
            Ok { val = f } ->
                # Get type info
                Python.bind { name = "obj", value = f }
                let type_info = Python.eval { code = """
                    {
                        'type': type(obj).__name__,
                        'module': type(obj).__module__,
                        'members': dir(obj)
                    }
                """ }
                log { msg = "Type info: $(type_info)" }
            Err { e } -> log { msg = "Error: $(e)" }
}
```

### Isolate the Problem

1. Test foreign code independently:
   ```bash
   $ graalpy -c "import numpy; print(numpy.array([1,2,3]))"
   ```

2. Test conversion separately:
   ```mezze
   let f = foreign::from { val = 42 }
   let result = foreign::to_int { val = f }
   # Should work
   ```

3. Test with minimal context:
   ```mezze
   python::scoped { ctx ->
       perform GraalPy { context = ctx } in
           Python.eval { code = "1 + 1" }  # Simplest possible
   }
   ```

## Getting Help

1. Check the error message carefully - it often contains hints
2. Search issues: https://github.com/mezze-lang/mezze/issues
3. Check GraalVM documentation: https://www.graalvm.org/latest/reference-manual/
4. Ask in discussions: https://github.com/mezze-lang/mezze/discussions
