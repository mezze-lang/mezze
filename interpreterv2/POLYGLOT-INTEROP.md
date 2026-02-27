# Polyglot Interop: Using Python, JavaScript, Ruby, and More

## The GraalVM Advantage

Since Mezze runs on GraalVM Truffle, we get **zero-overhead interop** with:
- **GraalPy** (Python)
- **GraalJS** (JavaScript)
- **TruffleRuby** (Ruby)
- **FastR** (R)
- **GraalWasm** (WebAssembly)
- **Sulong** (LLVM - C/C++/Rust)
- **Espresso** (Java bytecode)

All these languages share the same JIT compiler and can call each other **without serialization or FFI overhead**.

---

## Part 1: How Polyglot Works

### Traditional FFI (Slow)

```
┌─────────────┐     serialize      ┌─────────────┐
│   Mezze     │ ──────────────────▶│   Python    │
│             │     deserialize    │             │
│  result = 42│ ◀──────────────────│  return 42  │
└─────────────┘                    └─────────────┘

Cost: Serialization + deserialization + process boundary
Latency: ~1-10ms per call
```

### GraalVM Polyglot (Fast)

```
┌─────────────────────────────────────────────────────────────┐
│                     GraalVM Runtime                          │
│                                                              │
│  ┌─────────────┐    direct reference    ┌─────────────┐    │
│  │   Mezze     │ ───────────────────────▶│  GraalPy    │    │
│  │   Node      │                         │   Node      │    │
│  │             │◀───────────────────────│             │    │
│  └─────────────┘    direct reference    └─────────────┘    │
│                                                              │
│            Both JIT compiled together by Graal              │
└─────────────────────────────────────────────────────────────┘

Cost: Direct object reference (same as Java method call)
Latency: ~10-100 nanoseconds per call
```

---

## Part 2: Mezze Syntax for Polyglot

### 2.1 Importing Python Libraries

```mezze
// Import Python's numpy
let np = polyglot::import "python" "numpy"

// Use it directly
let arr = np.array { data = [1, 2, 3, 4, 5] }
let mean = np.mean { a = arr }
let std = np.std { a = arr }

// Returns Mezze Float
Log.info { msg = "Mean: $(mean), Std: $(std)" }
```

### 2.2 Importing JavaScript Libraries

```mezze
// Import JavaScript lodash
let _ = polyglot::import "js" "lodash"

// Use lodash functions
let data = [1, 2, 3, 4, 5]
let doubled = _.map { collection = data, iteratee = {x} -> x * 2 }
let sum = _.sum { array = doubled }
```

### 2.3 Inline Polyglot Code

```mezze
// Inline Python for complex ML
let train_model = polyglot::eval "python" """
    import sklearn
    from sklearn.ensemble import RandomForestClassifier

    def train(X, y):
        model = RandomForestClassifier(n_estimators=100)
        model.fit(X, y)
        return model

    train
"""

// Call from Mezze
let model = train_model { X = features, y = labels }
let predictions = model.predict { X = test_features }
```

### 2.4 JavaScript for Browser/Node Integration

```mezze
// Use JavaScript fetch API
let fetch = polyglot::import "js" "fetch"

let response = fetch { url = "https://api.example.com/data" }
let json = response.json {}
```

---

## Part 3: Type Mapping

### Automatic Type Conversion

| Mezze Type | Python | JavaScript | Ruby |
|------------|--------|------------|------|
| `Int` | `int` | `number` | `Integer` |
| `Float` | `float` | `number` | `Float` |
| `String` | `str` | `string` | `String` |
| `Bool` | `bool` | `boolean` | `TrueClass/FalseClass` |
| `Array<T>` | `list` | `Array` | `Array` |
| `Record` | `dict` | `object` | `Hash` |
| `Function` | `callable` | `function` | `Proc/Lambda` |
| `null` | `None` | `null/undefined` | `nil` |

### Example: Passing Mezze Record to Python

```mezze
let point = { x = 10, y = 20 }

// Python sees this as a dict-like object
let py_func = polyglot::eval "python" """
    def process_point(p):
        return p['x'] + p['y']  # Can access fields!
    process_point
"""

let result = py_func { p = point }  // result = 30
```

### Example: Receiving Python Object in Mezze

```mezze
let py_point = polyglot::eval "python" """
    class Point:
        def __init__(self, x, y):
            self.x = x
            self.y = y

    Point(10, 20)
"""

// Access Python object fields from Mezze
let x = py_point.x  // 10
let y = py_point.y  // 20
```

---

## Part 4: Passing Mezze Functions to Other Languages

### Mezze Function as Python Callback

```mezze
let my_transform = {x: Int} -> x * 2 + 1

// Pass to Python's map
let py_map = polyglot::eval "python" """
    def apply_to_all(func, items):
        return [func(x) for x in items]
    apply_to_all
"""

let result = py_map {
    func = my_transform,  // Mezze function!
    items = [1, 2, 3, 4, 5]
}
// result = [3, 5, 7, 9, 11]
```

### Mezze Function as JavaScript Callback

```mezze
let filter_fn = {item: Item} -> item.value > 10

let js_filter = polyglot::eval "js" """
    (arr, fn) => arr.filter(fn)
"""

let filtered = js_filter {
    arr = items,
    fn = filter_fn  // Mezze function called from JS!
}
```

---

## Part 5: Effect Handlers with Polyglot

### Python Library with Mezze Effects

```mezze
effect MLTraining {
    log_metric: { name: String, value: Float } -> {}
    save_checkpoint: { model: Foreign, path: String } -> {}
}

// Handler that uses Python's mlflow
impl MLTraining for MLflowTracker {
    log_metric = { name, value } -> do
        let mlflow = polyglot::import "python" "mlflow"
        mlflow.log_metric { key = name, value = value }

    save_checkpoint = { model, path } -> do
        let mlflow = polyglot::import "python" "mlflow"
        mlflow.sklearn.log_model { sk_model = model, artifact_path = path }
}

// Use in Mezze code
let train = {} -> perform MLflowTracker {} in do
    let model = train_model { data = dataset }
    MLTraining.log_metric { name = "accuracy", value = 0.95 }
    MLTraining.save_checkpoint { model = model, path = "model" }
```

---

## Part 6: Implementation in Interpreter v2

### 6.1 Polyglot Context Setup

```java
public class MezzeContextV2 {
    private final Context polyglotContext;

    public MezzeContextV2() {
        this.polyglotContext = Context.newBuilder()
            .allowAllAccess(true)
            // Enable specific languages
            .option("python.Executable", "/path/to/graalpy")
            .option("js.ecmascript-version", "2022")
            .build();
    }

    public Value importPolyglot(String language, String module) {
        return switch (language) {
            case "python" -> polyglotContext.eval("python",
                "import " + module + "; " + module);
            case "js" -> polyglotContext.eval("js",
                "require('" + module + "')");
            case "ruby" -> polyglotContext.eval("ruby",
                "require '" + module + "'; " + module.split("::").last);
            default -> throw new UnsupportedLanguageException(language);
        };
    }

    public Value evalPolyglot(String language, String code) {
        return polyglotContext.eval(language, code);
    }
}
```

### 6.2 PolyglotImportNode

```java
@NodeInfo(shortName = "polyglot-import")
public abstract class PolyglotImportNode extends MezzeNode {
    private final String language;
    private final String module;

    @Specialization
    protected Object doImport(VirtualFrame frame,
            @CachedContext(MezzeLanguageV2.class) MezzeContextV2 context) {
        // Import is cached after first execution
        return context.importPolyglot(language, module);
    }
}
```

### 6.3 PolyglotCallNode

```java
@NodeInfo(shortName = "polyglot-call")
public abstract class PolyglotCallNode extends MezzeNode {
    @Child private InteropLibrary interop = InteropLibrary.getFactory().createDispatched(5);
    @Child private MezzeNode receiverNode;
    @Child private MezzeNode argsNode;
    private final String methodName;

    @Specialization
    protected Object doCall(VirtualFrame frame, Object receiver, Object args) {
        try {
            // Convert Mezze args to polyglot
            Object[] polyglotArgs = convertArgs(args);

            if (methodName != null) {
                // Method call: receiver.method(args)
                return interop.invokeMember(receiver, methodName, polyglotArgs);
            } else {
                // Direct call: receiver(args)
                return interop.execute(receiver, polyglotArgs);
            }
        } catch (UnsupportedMessageException | ArityException e) {
            throw new MezzePolyglotException(e);
        }
    }

    @TruffleBoundary
    private Object[] convertArgs(Object args) {
        if (args instanceof MezzeRecord record) {
            // Convert record fields to positional args or keep as map
            return record.getFields().values().toArray();
        }
        return new Object[] { args };
    }
}
```

### 6.4 Type Conversion Nodes

```java
public abstract class PolyglotToMezzeNode extends MezzeNode {
    @Child private InteropLibrary interop = InteropLibrary.getFactory().createDispatched(5);

    @Specialization(guards = "interop.isNumber(value)")
    protected long convertNumber(Object value) {
        try {
            if (interop.fitsInLong(value)) {
                return interop.asLong(value);
            }
            // Fall through to double
        } catch (UnsupportedMessageException e) {}
        return (long) interop.asDouble(value);
    }

    @Specialization(guards = "interop.isString(value)")
    protected String convertString(Object value) {
        try {
            return interop.asString(value);
        } catch (UnsupportedMessageException e) {
            throw new MezzePolyglotException(e);
        }
    }

    @Specialization(guards = "interop.hasArrayElements(value)")
    protected MezzeArrayList convertArray(Object value) {
        try {
            long size = interop.getArraySize(value);
            List<MezzeValue> elements = new ArrayList<>();
            for (long i = 0; i < size; i++) {
                Object elem = interop.readArrayElement(value, i);
                elements.add(convertToMezze(elem));
            }
            return MezzeArrayList.fromList(elements);
        } catch (UnsupportedMessageException | InvalidArrayIndexException e) {
            throw new MezzePolyglotException(e);
        }
    }

    @Specialization(guards = "interop.hasMembers(value)")
    protected MezzeRecord convertObject(Object value) {
        try {
            Object members = interop.getMembers(value);
            Map<String, MezzeValue> fields = new LinkedHashMap<>();
            long size = interop.getArraySize(members);
            for (long i = 0; i < size; i++) {
                String key = interop.asString(interop.readArrayElement(members, i));
                Object val = interop.readMember(value, key);
                fields.put(key, convertToMezze(val));
            }
            return MezzeRecord.fromMap(fields);
        } catch (UnsupportedMessageException | UnknownIdentifierException e) {
            throw new MezzePolyglotException(e);
        }
    }
}
```

---

## Part 7: AST and Type System Extensions

### 7.1 New AST Nodes

```kotlin
// polyglot::import "python" "numpy"
data class PolyglotImport(
    val language: String,      // "python", "js", "ruby"
    val module: String,        // "numpy", "lodash"
    override val sourceLocation: SourceLocation? = null,
    override val inferredType: MezzeType? = null  // Foreign type
) : Expression

// polyglot::eval "python" "code..."
data class PolyglotEval(
    val language: String,
    val code: String,
    override val sourceLocation: SourceLocation? = null,
    override val inferredType: MezzeType? = null  // Foreign type
) : Expression
```

### 7.2 Foreign Type

```kotlin
// Represents a value from another language
object ForeignType : MezzeType {
    override fun toString() = "Foreign"
}

// More specific foreign types (optional, for better type checking)
data class ForeignObjectType(
    val language: String,
    val knownMembers: Map<String, MezzeType>? = null  // If we can infer
) : MezzeType
```

### 7.3 Type Checking Rules

```kotlin
// In constraint generator
fun generateConstraints(expr: PolyglotImport): ConstraintResult {
    // Foreign imports have Foreign type
    return ConstraintResult(
        type = ForeignType,
        constraints = emptyList()
    )
}

// Member access on Foreign type
fun generateConstraints(expr: MemberAccess): ConstraintResult {
    val receiverType = infer(expr.receiver)

    if (receiverType is ForeignType || receiverType is ForeignObjectType) {
        // Foreign member access - type is unknown/Foreign
        return ConstraintResult(
            type = ForeignType,  // Conservative: we don't know the type
            constraints = emptyList()
        )
    }
    // ... normal member access
}

// Calling Foreign values
fun generateConstraints(expr: FunctionCall): ConstraintResult {
    val funcType = infer(expr.function)

    if (funcType is ForeignType) {
        // Foreign call - return type is Foreign
        return ConstraintResult(
            type = ForeignType,
            constraints = emptyList()
        )
    }
    // ... normal function call
}
```

---

## Part 8: Deployment with Polyglot

### Worker with Python Support

```
WORKER with GraalPy:
├── GraalVM with GraalPy (~400 MB)
├── mezze-worker.jar (~20 MB)
├── Python packages (pip installed)
│   ├── numpy
│   ├── pandas
│   ├── scikit-learn
│   └── ...
└── Mezze fetches code that uses polyglot::import "python"
```

### Docker Image Example

```dockerfile
FROM ghcr.io/graalvm/graalpy:23.1.0

# Install Mezze worker
COPY mezze-worker.jar /app/

# Install Python packages
RUN pip install numpy pandas scikit-learn mlflow

# Start worker
CMD ["java", "-jar", "/app/mezze-worker.jar", \
     "--coordinator=grpc://coordinator:50051"]
```

### Selective Language Support

Workers can advertise which languages they support:

```json
{
  "workerId": "worker-ml-1",
  "endpoint": "grpc://worker1:50051",
  "capabilities": {
    "languages": ["mezze", "python"],
    "pythonPackages": ["numpy", "pandas", "sklearn"]
  }
}
```

Coordinator routes tasks appropriately:
- Task uses `polyglot::import "python"` → route to Python-capable worker
- Pure Mezze task → route to any worker

---

## Part 9: Performance Considerations

### JIT Compilation Across Languages

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Graal JIT Compiler                                 │
│                                                                              │
│   Mezze code:          Python code:           Combined:                     │
│   ┌───────────────┐   ┌───────────────┐      ┌───────────────────────────┐ │
│   │ let x = ...   │   │ def foo():    │      │ // All inlined together!  │ │
│   │ py_func { x } │──▶│   return x*2  │ ──▶  │ long result = x * 2;      │ │
│   └───────────────┘   └───────────────┘      └───────────────────────────┘ │
│                                                                              │
│   After warmup, Graal inlines across language boundaries!                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### When Polyglot is Fast

- **Tight loops**: Graal inlines across languages
- **Primitive types**: No boxing at boundaries
- **Cached imports**: Import once, call many times

### When Polyglot is Slower

- **First call**: Language startup overhead
- **Complex objects**: Conversion overhead
- **String-heavy**: String conversion between encodings

### Benchmarks (Expected)

| Scenario | Pure Mezze | Mezze + Python (cold) | Mezze + Python (warm) |
|----------|------------|----------------------|----------------------|
| Simple function call | 10ns | 1ms | 50ns |
| Array processing | 100ns | 5ms | 200ns |
| NumPy matrix multiply | N/A | 10ms | 1ms |

---

## Part 10: Example Use Cases

### 10.1 Data Science Pipeline

```mezze
use std::effects::log::{Console, Log}

// Python for ML
let sklearn = polyglot::import "python" "sklearn"
let np = polyglot::import "python" "numpy"

// Mezze for business logic
let preprocess = {data: Array<Record>} -> [Log]
    data
    |> Array.filter {r} -> r.valid
    |> Array.map {r} -> { features = extract_features r, label = r.label }

// Training pipeline
let train_pipeline = {raw_data: Array<Record>} -> [Log] perform Console {} in do
    Log.info { msg = "Preprocessing..." }
    let processed = preprocess { data = raw_data }

    Log.info { msg = "Converting to numpy..." }
    let X = np.array { object = processed |> Array.map {r} -> r.features }
    let y = np.array { object = processed |> Array.map {r} -> r.label }

    Log.info { msg = "Training model..." }
    let model = sklearn.ensemble.RandomForestClassifier { n_estimators = 100 }
    model.fit { X = X, y = y }

    Log.info { msg = "Done!" }
    model
```

### 10.2 Web Backend with JavaScript

```mezze
// JavaScript for HTTP handling
let express = polyglot::import "js" "express"

let create_server = {} -> do
    let app = express {}

    // Mezze handler
    let handle_request = {req: Foreign, res: Foreign} -> do
        let data = process_request { body = req.body }
        res.json { data = data }

    app.post { path = "/api/process", callback = handle_request }
    app.listen { port = 3000 }
```

### 10.3 System Integration with C/Rust (via LLVM)

```mezze
// Load native library via Sulong
let native_lib = polyglot::import "llvm" "./libfast_math.so"

// Call C function
let result = native_lib.fast_matrix_multiply {
    a = matrix_a,
    b = matrix_b,
    n = 1000
}
```

---

## Summary

| Feature | Benefit |
|---------|---------|
| Zero-overhead interop | Graal JIT compiles across languages |
| Rich ecosystem | Use Python ML, JS web, Ruby scripting |
| Type-safe boundaries | Mezze types map to foreign types |
| Effect handlers work | Mezze effects can wrap foreign calls |
| Distributed ready | Workers can have language-specific capabilities |

Mezze becomes a **glue language** that can orchestrate code from any GraalVM language while maintaining its own type safety and effect system.
