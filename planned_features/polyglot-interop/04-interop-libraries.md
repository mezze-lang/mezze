# Interop Libraries

Each language has its own interop library (mezze-python, mezze-js, mezze-java, etc.) that provides:
1. Effect declaration
2. Handler implementation
3. Context management functions

## Common Pattern

All interop libraries follow the same pattern:

```mezze
# 1. Context management (regular functions)
pub let create_context: {} -> Context
pub let close_context: { context: Context } -> {}
pub let scoped: { f: { ctx: Context } -> 'a } -> 'a

# 2. Effect declaration
pub effect {Language} where {
    eval: { code: String } -> Result Foreign InteropError <{Language}>,
    eval_with: { code: String, bindings: { ...'r } } -> Result Foreign InteropError <{Language}>,
    bind: { name: String, value: 'a } -> Result {} InteropError <{Language}>,
    ...language-specific operations...
}

# 3. Handler (requires context)
pub impl {Language} for {Handler} { context: Context } where { ... }
```

---

## Python (mezze-python)

### Effect Declaration

```mezze
pub effect Python where {
    eval: { code: String } -> Result Foreign InteropError <Python>,
    eval_with: { code: String, bindings: { ...'r } } -> Result Foreign InteropError <Python>,
    bind: { name: String, value: 'a } -> Result {} InteropError <Python>,
    import_module: { name: String } -> Result Foreign InteropError <Python>
}
```

### Handler

```mezze
pub impl Python for GraalPy { context: Context } where {
    eval = { code } -> __native_python_eval { context = context, code = code },
    eval_with = { code, bindings } -> __native_python_eval_with { context = context, code = code, bindings = bindings },
    bind = { name, value } -> __native_python_bind { context = context, name = name, value = value },
    import_module = { name } -> __native_python_import { context = context, name = name }
}
```

### Context Management

```mezze
pub let create_context: {} -> Context = {} ->
    __native_python_create_context {}

pub let close_context: { context: Context } -> {} = { context } ->
    __native_python_close_context { context = context }

pub let scoped: { f: { ctx: Context } -> 'a } -> 'a = { f } ->
    let ctx = create_context {}
    let result = f { ctx = ctx }
    close_context { context = ctx }
    result
```

### Usage Examples

**Simple evaluation:**
```mezze
use python

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.eval { code = "1 + 1" }
}
# Returns: Ok { val = Foreign(2) }
```

**With bindings:**
```mezze
use python
use std::interop::foreign

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let result = Python.eval_with {
            code = "x + y",
            bindings = { x = 10, y = 20 }
        }
        match result is
            Ok { val = f } -> foreign::to_int { val = f }
            Err { error } -> Err { error = error }
}
# Returns: Ok { val = 30 }
```

**Using numpy:**
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
                Python.eval { code = "np.array(data).sum()" }
            Err { error } -> Err { error = error }
}
```

**Shared context across perform blocks:**
```mezze
use python

python::scoped { ctx ->
    # First block: setup
    perform GraalPy { context = ctx } in
        Python.import_module { name = "numpy" }
        Python.bind { name = "np", value = numpy }
        Python.eval { code = "model = np.zeros((100, 100))" }

    # ... do some Mezze computation ...
    let scale_factor = compute_scale {}

    # Second block: use the model
    perform GraalPy { context = ctx } in
        Python.bind { name = "scale", value = scale_factor }
        Python.eval { code = "model * scale" }
}
```

**Passing Mezze functions to Python:**
```mezze
use python

let double = { n: Int } -> n * 2

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        Python.bind { name = "double", value = double }
        Python.eval { code = "double(21)" }
}
# Returns: Ok { val = Foreign(42) }
```

---

## JavaScript (mezze-js)

### Effect Declaration

```mezze
pub effect JavaScript where {
    eval: { code: String } -> Result Foreign InteropError <JavaScript>,
    eval_with: { code: String, bindings: { ...'r } } -> Result Foreign InteropError <JavaScript>,
    bind: { name: String, value: 'a } -> Result {} InteropError <JavaScript>,
    require: { module: String } -> Result Foreign InteropError <JavaScript>
}
```

### Handler

```mezze
pub impl JavaScript for GraalJS { context: Context } where {
    eval = { code } -> __native_js_eval { context = context, code = code },
    eval_with = { code, bindings } -> __native_js_eval_with { context = context, code = code, bindings = bindings },
    bind = { name, value } -> __native_js_bind { context = context, name = name, value = value },
    require = { module } -> __native_js_require { context = context, module = module }
}
```

### Context Management

```mezze
pub let create_context: {} -> Context = {} ->
    __native_js_create_context {}

pub let close_context: { context: Context } -> {} = { context } ->
    __native_js_close_context { context = context }

pub let scoped: { f: { ctx: Context } -> 'a } -> 'a = { f } ->
    let ctx = create_context {}
    let result = f { ctx = ctx }
    close_context { context = ctx }
    result
```

### Usage Examples

**Simple evaluation:**
```mezze
use javascript

javascript::scoped { ctx ->
    perform GraalJS { context = ctx } in
        JavaScript.eval { code = "Math.max(1, 2, 3)" }
}
# Returns: Ok { val = Foreign(3) }
```

**Using lodash:**
```mezze
use javascript
use std::interop::foreign

javascript::scoped { ctx ->
    perform GraalJS { context = ctx } in
        let _ = JavaScript.require { module = "lodash" }
        JavaScript.bind { name = "data", value = [3, 1, 4, 1, 5, 9, 2, 6] }
        let result = JavaScript.eval { code = "_.sortBy(data)" }
        match result is
            Ok { val = sorted } -> foreign::to_array { val = sorted }
            Err { error } -> Err { error = error }
}
```

**Working with JSON:**
```mezze
use javascript

javascript::scoped { ctx ->
    perform GraalJS { context = ctx } in
        JavaScript.bind { name = "obj", value = { name = "Alice", age = 30 } }
        JavaScript.eval { code = "JSON.stringify(obj)" }
}
# Returns: Ok { val = Foreign("{\"name\":\"Alice\",\"age\":30}") }
```

**Async JavaScript (with promises):**
```mezze
use javascript
use std::interop::foreign

javascript::scoped { ctx ->
    perform GraalJS { context = ctx } in
        let promise = JavaScript.eval { code = "
            new Promise((resolve) => {
                setTimeout(() => resolve(42), 100)
            })
        " }
        # Await the promise
        match promise is
            Ok { val = p } ->
                let awaited = foreign::call { obj = p, method = "then", args = [{ x } -> x] }
                awaited
            Err { error } -> Err { error = error }
}
```

---

## Java (mezze-java)

### Effect Declaration

```mezze
pub effect Java where {
    import_class: { name: String } -> Result Foreign InteropError <Java>,
    new_instance: { class: Foreign, args: [Foreign] } -> Result Foreign InteropError <Java>,
    call_static: { class: Foreign, method: String, args: [Foreign] } -> Result Foreign InteropError <Java>,
    bind: { name: String, value: 'a } -> Result {} InteropError <Java>
}
```

### Handler

```mezze
pub impl Java for JVM { context: Context } where {
    import_class = { name } -> __native_java_import_class { context = context, name = name },
    new_instance = { class, args } -> __native_java_new_instance { context = context, class = class, args = args },
    call_static = { class, method, args } -> __native_java_call_static { context = context, class = class, method = method, args = args },
    bind = { name, value } -> __native_java_bind { context = context, name = name, value = value }
}
```

### Context Management

```mezze
pub let create_context: {} -> Context = {} ->
    __native_java_create_context {}

pub let close_context: { context: Context } -> {} = { context } ->
    __native_java_close_context { context = context }

pub let scoped: { f: { ctx: Context } -> 'a } -> 'a = { f } ->
    let ctx = create_context {}
    let result = f { ctx = ctx }
    close_context { context = ctx }
    result
```

### Usage Examples

**Using Java standard library:**
```mezze
use java
use std::interop::foreign

java::scoped { ctx ->
    perform JVM { context = ctx } in
        let ArrayList = Java.import_class { name = "java.util.ArrayList" }
        match ArrayList is
            Ok { val = cls } ->
                let list = Java.new_instance { class = cls, args = [] }
                match list is
                    Ok { val = l } ->
                        foreign::call { obj = l, method = "add", args = [foreign::from { val = "hello" }] }
                        foreign::call { obj = l, method = "add", args = [foreign::from { val = "world" }] }
                        foreign::call { obj = l, method = "size", args = [] }
                    Err { error } -> Err { error = error }
            Err { error } -> Err { error = error }
}
# Returns: Ok { val = Foreign(2) }
```

**Using Gson for JSON:**
```mezze
use java
use std::interop::foreign

java::scoped { ctx ->
    perform JVM { context = ctx } in
        let Gson = Java.import_class { name = "com.google.gson.Gson" }
        match Gson is
            Ok { val = cls } ->
                let gson = Java.new_instance { class = cls, args = [] }
                match gson is
                    Ok { val = g } ->
                        let data = { name = "Alice", age = 30 }
                        Java.bind { name = "data", value = data }
                        foreign::call { obj = g, method = "toJson", args = [foreign::from { val = data }] }
                    Err { error } -> Err { error = error }
            Err { error } -> Err { error = error }
}
```

**Calling static methods:**
```mezze
use java
use std::interop::foreign

java::scoped { ctx ->
    perform JVM { context = ctx } in
        let Math = Java.import_class { name = "java.lang.Math" }
        match Math is
            Ok { val = cls } ->
                Java.call_static {
                    class = cls,
                    method = "max",
                    args = [foreign::from { val = 10 }, foreign::from { val = 20 }]
                }
            Err { error } -> Err { error = error }
}
# Returns: Ok { val = Foreign(20) }
```

**Using Kotlin code:**
```mezze
use java
use std::interop::foreign

# Kotlin compiles to JVM bytecode, so we use the Java interop
java::scoped { ctx ->
    perform JVM { context = ctx } in
        # Kotlin data class becomes a regular Java class
        let Person = Java.import_class { name = "com.example.Person" }
        match Person is
            Ok { val = cls } ->
                Java.new_instance {
                    class = cls,
                    args = [foreign::from { val = "Alice" }, foreign::from { val = 30 }]
                }
            Err { error } -> Err { error = error }
}
```

---

## R (mezze-r)

### Effect Declaration

```mezze
pub effect R where {
    eval: { code: String } -> Result Foreign InteropError <R>,
    eval_with: { code: String, bindings: { ...'r } } -> Result Foreign InteropError <R>,
    bind: { name: String, value: 'a } -> Result {} InteropError <R>,
    library: { name: String } -> Result {} InteropError <R>
}
```

### Handler

```mezze
pub impl R for FastR { context: Context } where {
    eval = { code } -> __native_r_eval { context = context, code = code },
    eval_with = { code, bindings } -> __native_r_eval_with { context = context, code = code, bindings = bindings },
    bind = { name, value } -> __native_r_bind { context = context, name = name, value = value },
    library = { name } -> __native_r_library { context = context, name = name }
}
```

### Usage Examples

**Simple evaluation:**
```mezze
use r

r::scoped { ctx ->
    perform FastR { context = ctx } in
        R.eval { code = "sum(1:10)" }
}
# Returns: Ok { val = Foreign(55) }
```

**Using ggplot2:**
```mezze
use r
use std::interop::foreign

r::scoped { ctx ->
    perform FastR { context = ctx } in
        R.library { name = "ggplot2" }
        R.bind { name = "data", value = [1.0, 2.0, 3.0, 4.0, 5.0] }
        R.eval { code = "
            df <- data.frame(x = 1:5, y = data)
            ggplot(df, aes(x, y)) + geom_point()
        " }
}
```

**Statistical computation:**
```mezze
use r

r::scoped { ctx ->
    perform FastR { context = ctx } in
        R.bind { name = "x", value = [1.0, 2.0, 3.0, 4.0, 5.0] }
        R.bind { name = "y", value = [2.1, 3.9, 6.1, 7.9, 10.2] }
        R.eval { code = "
            model <- lm(y ~ x)
            summary(model)$r.squared
        " }
}
```

---

## Ruby (mezze-ruby)

### Effect Declaration

```mezze
pub effect Ruby where {
    eval: { code: String } -> Result Foreign InteropError <Ruby>,
    eval_with: { code: String, bindings: { ...'r } } -> Result Foreign InteropError <Ruby>,
    bind: { name: String, value: 'a } -> Result {} InteropError <Ruby>,
    require: { gem: String } -> Result {} InteropError <Ruby>
}
```

### Handler

```mezze
pub impl Ruby for TruffleRuby { context: Context } where {
    eval = { code } -> __native_ruby_eval { context = context, code = code },
    eval_with = { code, bindings } -> __native_ruby_eval_with { context = context, code = code, bindings = bindings },
    bind = { name, value } -> __native_ruby_bind { context = context, name = name, value = value },
    require = { gem } -> __native_ruby_require { context = context, gem = gem }
}
```

### Usage Examples

**Simple evaluation:**
```mezze
use ruby

ruby::scoped { ctx ->
    perform TruffleRuby { context = ctx } in
        Ruby.eval { code = "[1, 2, 3, 4, 5].map { |x| x * 2 }" }
}
# Returns: Ok { val = Foreign([2, 4, 6, 8, 10]) }
```

**Using a gem:**
```mezze
use ruby

ruby::scoped { ctx ->
    perform TruffleRuby { context = ctx } in
        Ruby.require { gem = "json" }
        Ruby.bind { name = "data", value = { name = "Alice", age = 30 } }
        Ruby.eval { code = "JSON.generate(data)" }
}
```

---

## Handler Configuration

All handlers support timeout and memory limits:

```mezze
# With timeout (milliseconds)
perform GraalPy { context = ctx, timeout = 5000 } in
    Python.eval { code = "slow_computation()" }

# With memory limit (MB)
perform GraalPy { context = ctx, memory = 256 } in
    Python.eval { code = "memory_intensive()" }

# Both
perform GraalPy { context = ctx, timeout = 30000, memory = 512 } in
    Python.eval { code = "train_model()" }
```

---

## Error Handling

All operations return `Result Foreign InteropError`. Handle errors with pattern matching:

```mezze
python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let result = Python.import_module { name = "nonexistent" }
        match result is
            Ok { val = module } ->
                # Use module
                Ok { val = module }
            Err { error } ->
                match error is
                    RuntimeError { language, message, stack } ->
                        # Python raised an exception
                        log { msg = "Python error: $(message)" }
                        Err { error = error }
                    Timeout { operation, limit_ms } ->
                        # Operation timed out
                        log { msg = "Timeout after $(limit_ms)ms" }
                        Err { error = error }
                    _ ->
                        # Other errors
                        Err { error = error }
}
```

---

## Library Wrapper Example (mezze-numpy)

Wrapper libraries provide Mezze-friendly APIs over foreign code:

```mezze
# mezze-numpy/src/lib.mz
use python
use std::interop::foreign

pub type NDArray = { inner: Foreign }

pub let array: { data: [Float] } -> Result NDArray InteropError <Python> = { data } ->
    let np = Python.import_module { name = "numpy" }
    match np is
        Ok { val = numpy } ->
            let result = foreign::call {
                obj = numpy,
                method = "array",
                args = [foreign::from { val = data }]
            }
            match result is
                Ok { val = arr } -> Ok { val = { inner = arr } }
                Err { error } -> Err { error = error }
        Err { error } -> Err { error = error }

pub let sum: { arr: NDArray } -> Result Float InteropError <Python> = { arr } ->
    let result = foreign::call { obj = arr.inner, method = "sum", args = [] }
    match result is
        Ok { val = s } -> foreign::to_float { val = s }
        Err { error } -> Err { error = error }

pub let dot: { a: NDArray, b: NDArray } -> Result NDArray InteropError <Python> = { a, b } ->
    let result = foreign::call { obj = a.inner, method = "dot", args = [b.inner] }
    match result is
        Ok { val = r } -> Ok { val = { inner = r } }
        Err { error } -> Err { error = error }

pub let shape: { arr: NDArray } -> Result [Int] InteropError <Python> = { arr } ->
    let s = foreign::get { obj = arr.inner, member = "shape" }
    # Convert tuple to array...
    ...
```

**Usage:**
```mezze
use python
use numpy

python::scoped { ctx ->
    perform GraalPy { context = ctx } in
        let arr1 = numpy::array { data = [1.0, 2.0, 3.0] }
        let arr2 = numpy::array { data = [4.0, 5.0, 6.0] }
        match arr1 is
            Ok { val = a1 } ->
                match arr2 is
                    Ok { val = a2 } ->
                        numpy::dot { a = a1, b = a2 }
                    Err { error } -> Err { error = error }
            Err { error } -> Err { error = error }
}
```
