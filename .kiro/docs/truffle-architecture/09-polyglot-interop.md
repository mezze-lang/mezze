# Polyglot Language Integration: Calling Other GraalVM Languages from Funk

## Overview

This document explains how Funk can call code written in other GraalVM languages (JavaScript, Python, R, Ruby, etc.) using GraalVM's polyglot capabilities. This enables Funk to leverage libraries and capabilities from different language ecosystems.

## Core Concepts

### 1. GraalVM Polyglot Architecture

GraalVM supports multiple languages running in the same VM:
- **JavaScript (GraalJS)**: V8-compatible JavaScript engine
- **Python (GraalPy)**: Python 3.x implementation
- **R (FastR)**: R language for statistical computing
- **Ruby (TruffleRuby)**: Ruby implementation
- **WebAssembly**: WASM module execution
- **LLVM**: C/C++ and other native languages

### 2. Polyglot Context

All languages share the same polyglot context, enabling:
- **Shared objects** between languages
- **Direct method calls** across language boundaries
- **Automatic type conversion** between language types
- **Exception propagation** across languages

## Polyglot Effect Design

### 1. Core Polyglot Effect

```funk
// Polyglot interop effect for calling other languages
effect Polyglot {
  eval: {language: String, code: String} -> PolyglotValue <Polyglot>
  call: {value: PolyglotValue, args: [PolyglotValue]} -> PolyglotValue <Polyglot>
  get_member: {value: PolyglotValue, member: String} -> PolyglotValue <Polyglot>
  set_member: {value: PolyglotValue, member: String, value: PolyglotValue} -> Unit <Polyglot>
  get_bindings: {language: String} -> PolyglotValue <Polyglot>
  import_module: {language: String, module: String} -> PolyglotValue <Polyglot>
  to_funk_string: {value: PolyglotValue} -> String <Polyglot>
  to_funk_int: {value: PolyglotValue} -> Int <Polyglot>
  to_funk_float: {value: PolyglotValue} -> Float <Polyglot>
  to_funk_boolean: {value: PolyglotValue} -> Boolean <Polyglot>
}

// Polyglot types
type PolyglotValue = | PolyglotValue { language: String, valueRef: Opaque } |
type PolyglotResult = 
  | Success { value: PolyglotValue }
  | Error { message: String, language: String }
```

### 2. Language-Specific Effects

```funk
// JavaScript-specific effect
effect JavaScript {
  eval: {code: String} -> PolyglotValue <JavaScript>
  call_function: {function_name: String, args: [PolyglotValue]} -> PolyglotValue <JavaScript>
  get_global: {name: String} -> PolyglotValue <JavaScript>
  set_global: {name: String, value: PolyglotValue} -> Unit <JavaScript>
  require: {module: String} -> PolyglotValue <JavaScript>
}

// Python-specific effect
effect Python {
  eval: {code: String} -> PolyglotValue <Python>
  import: {module: String} -> PolyglotValue <Python>
  call_method: {object: PolyglotValue, method: String, args: [PolyglotValue]} -> PolyglotValue <Python>
  get_attribute: {object: PolyglotValue, attribute: String} -> PolyglotValue <Python>
}

// R-specific effect
effect R {
  eval: {code: String} -> PolyglotValue <R>
  call_function: {function: String, args: [PolyglotValue]} -> PolyglotValue <R>
  get_variable: {name: String} -> PolyglotValue <R>
  set_variable: {name: String, value: PolyglotValue} -> Unit <R>
}
```

### 3. Implementation

```kotlin
class PolyglotInteropHandler(private val context: FunkContext) : SyncEffectHandler {
    
    override fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "eval" -> handleEval(arguments)
            "call" -> handleCall(arguments)
            "get_member" -> handleGetMember(arguments)
            "set_member" -> handleSetMember(arguments)
            "get_bindings" -> handleGetBindings(arguments)
            "to_funk_string" -> handleToFunkString(arguments)
            "to_funk_int" -> handleToFunkInt(arguments)
            "to_funk_float" -> handleToFunkFloat(arguments)
            "to_funk_boolean" -> handleToFunkBoolean(arguments)
            else -> throw FunkException("Unknown polyglot operation: $operation")
        }
    }
    
    private fun handleEval(arguments: FunkValue): FunkValue {
        val args = arguments as FunkRecord
        val language = (args.fields["language"] as FunkString).value
        val code = (args.fields["code"] as FunkString).value
        
        return try {
            // Get polyglot context from Truffle environment
            val polyglotContext = context.env?.getContext()
            val result = polyglotContext?.eval(language, code)
            
            if (result != null) {
                FunkVariant("PolyglotValue", FunkRecord(mapOf(
                    "language" to FunkString(language),
                    "valueRef" to PolyglotValueWrapper(result)
                )))
            } else {
                throw FunkException("Failed to evaluate $language code")
            }
        } catch (e: Exception) {
            throw FunkException("Polyglot eval failed: ${e.message}", e)
        }
    }
    
    private fun handleCall(arguments: FunkValue): FunkValue {
        val args = arguments as FunkRecord
        val polyglotValueVariant = args.fields["value"] as FunkVariant
        val argsArray = args.fields["args"] as FunkValue
        
        // Extract polyglot value
        val polyglotValueRecord = polyglotValueVariant.value as FunkRecord
        val polyglotValue = (polyglotValueRecord.fields["valueRef"] as PolyglotValueWrapper).value
        
        return try {
            val convertedArgs = convertFunkArgsToPolyglot(argsArray)
            val result = polyglotValue.execute(*convertedArgs)
            
            wrapPolyglotResult(result, "unknown")
        } catch (e: Exception) {
            throw FunkException("Polyglot call failed: ${e.message}", e)
        }
    }
    
    private fun handleGetMember(arguments: FunkValue): FunkValue {
        val args = arguments as FunkRecord
        val polyglotValueVariant = args.fields["value"] as FunkVariant
        val memberName = (args.fields["member"] as FunkString).value
        
        val polyglotValueRecord = polyglotValueVariant.value as FunkRecord
        val polyglotValue = (polyglotValueRecord.fields["valueRef"] as PolyglotValueWrapper).value
        
        return try {
            val member = polyglotValue.getMember(memberName)
            wrapPolyglotResult(member, "unknown")
        } catch (e: Exception) {
            throw FunkException("Failed to get member '$memberName': ${e.message}", e)
        }
    }
    
    private fun convertFunkArgsToPolyglot(funkArgs: FunkValue): Array<Any?> {
        return when (funkArgs) {
            is FunkRecord -> {
                funkArgs.fields.values.map { convertFunkToPolyglot(it) }.toTypedArray()
            }
            else -> arrayOf(convertFunkToPolyglot(funkArgs))
        }
    }
    
    private fun convertFunkToPolyglot(funkValue: FunkValue): Any? {
        return when (funkValue) {
            is FunkInt -> funkValue.value
            is FunkFloat -> funkValue.value
            is FunkString -> funkValue.value
            is FunkBoolean -> funkValue.value
            is FunkNull -> null
            is PolyglotValueWrapper -> funkValue.value  // Unwrap polyglot values
            is FunkRecord -> {
                // Convert to Map
                val map = mutableMapOf<String, Any?>()
                funkValue.fields.forEach { (key, value) ->
                    map[key] = convertFunkToPolyglot(value)
                }
                map
            }
            else -> funkValue.toString()
        }
    }
    
    private fun wrapPolyglotResult(result: Any?, language: String): FunkValue {
        return if (result != null) {
            FunkVariant("PolyglotValue", FunkRecord(mapOf(
                "language" to FunkString(language),
                "valueRef" to PolyglotValueWrapper(result)
            )))
        } else {
            FunkNull
        }
    }
    
    override fun canHandle(effectName: String): Boolean = 
        effectName == "Polyglot" || effectName == "JavaScript" || 
        effectName == "Python" || effectName == "R"
}

// Wrapper for polyglot values
class PolyglotValueWrapper(val value: Any) : FunkValue() {
    override fun getDisplayString(): String = "PolyglotValue(${value::class.simpleName})"
    override fun toString(): String = value.toString()
}
```

## Language-Specific Examples

### 1. JavaScript Integration

```funk
// Basic JavaScript evaluation
let javascript_basic = do
  // Evaluate JavaScript expression
  let result = JavaScript.eval {code = "Math.sqrt(16) + Math.PI"}
  let number = Polyglot.to_funk_float {value = result}
  
  Console.print {message = "JavaScript result: " ++ floatToString {val = number}}
  number

// Working with JavaScript objects and functions
let javascript_advanced = do
  // Create JavaScript object
  let obj_result = JavaScript.eval {code = """
    ({
      name: "Funk Language",
      version: "1.0.0",
      features: ["functional", "effects", "pattern-matching"],
      calculate: function(x, y) {
        return x * y + Math.random();
      }
    })
  """}
  
  // Access object properties
  let name = Polyglot.get_member {value = obj_result, member = "name"}
  let name_str = Polyglot.to_funk_string {value = name}
  
  let version = Polyglot.get_member {value = obj_result, member = "version"}
  let version_str = Polyglot.to_funk_string {value = version}
  
  // Call JavaScript function
  let calculate_fn = Polyglot.get_member {value = obj_result, member = "calculate"}
  let calc_result = Polyglot.call {
    value = calculate_fn,
    args = [
      Polyglot.eval {language = "js", code = "10"},
      Polyglot.eval {language = "js", code = "20"}
    ]
  }
  let calc_number = Polyglot.to_funk_float {value = calc_result}
  
  {
    name = name_str,
    version = version_str,
    calculation = calc_number
  }

// Using JavaScript libraries (if available)
let javascript_array_processing = do
  // Create and manipulate JavaScript arrays
  let js_array = JavaScript.eval {code = "[1, 2, 3, 4, 5]"}
  
  // Use JavaScript array methods
  let doubled = JavaScript.eval {code = """
    (function(arr) {
      return arr.map(x => x * 2);
    })
  """}
  
  let result = Polyglot.call {value = doubled, args = [js_array]}
  
  // Convert back to Funk (would need conversion helper)
  result
```

### 2. Python Integration

```funk
// Basic Python evaluation
let python_basic = do
  // Simple Python calculation
  let result = Python.eval {code = "import math; math.sqrt(25) + math.pi"}
  let number = Polyglot.to_funk_float {value = result}
  
  Console.print {message = "Python result: " ++ floatToString {val = number}}
  number

// Working with Python modules
let python_advanced = do
  // Import Python modules
  let math_module = Python.import {module = "math"}
  let random_module = Python.import {module = "random"}
  
  // Call Python functions
  let sqrt_result = Python.call_method {
    object = math_module,
    method = "sqrt",
    args = [Polyglot.eval {language = "python", code = "144"}]
  }
  
  let random_result = Python.call_method {
    object = random_module,
    method = "random",
    args = []
  }
  
  let sqrt_num = Polyglot.to_funk_float {value = sqrt_result}
  let random_num = Polyglot.to_funk_float {value = random_result}
  
  {sqrt = sqrt_num, random = random_num}

// Data science with Python (if NumPy available)
let python_data_science = do
  // Create Python list
  let data = Python.eval {code = "[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]"}
  
  // Use Python for statistical calculations
  let stats = Python.eval {code = """
import statistics
def analyze_data(data):
    return {
        'mean': statistics.mean(data),
        'median': statistics.median(data),
        'stdev': statistics.stdev(data),
        'max': max(data),
        'min': min(data)
    }
analyze_data
  """}
  
  let analysis = Polyglot.call {value = stats, args = [data]}
  
  // Extract results
  let mean = Polyglot.get_member {value = analysis, member = "mean"}
  let median = Polyglot.get_member {value = analysis, member = "median"}
  let stdev = Polyglot.get_member {value = analysis, member = "stdev"}
  
  {
    mean = Polyglot.to_funk_float {value = mean},
    median = Polyglot.to_funk_float {value = median},
    stdev = Polyglot.to_funk_float {value = stdev}
  }
```

### 3. R Integration

```funk
// Statistical computing with R
let r_statistics = do
  // Create R vector
  let data_vector = R.eval {code = "c(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)"}
  
  // Calculate statistics using R functions
  let mean_val = R.call_function {
    function = "mean",
    args = [data_vector]
  }
  
  let sd_val = R.call_function {
    function = "sd",
    args = [data_vector]
  }
  
  let summary_stats = R.call_function {
    function = "summary",
    args = [data_vector]
  }
  
  {
    mean = Polyglot.to_funk_float {value = mean_val},
    standard_deviation = Polyglot.to_funk_float {value = sd_val},
    summary = Polyglot.to_funk_string {value = summary_stats}
  }

// R data frame operations
let r_data_analysis = do
  // Create R data frame
  let df = R.eval {code = """
    data.frame(
      x = c(1, 2, 3, 4, 5),
      y = c(2, 4, 6, 8, 10),
      category = c("A", "B", "A", "B", "A")
    )
  """}
  
  // Perform linear regression
  let lm_result = R.eval {code = "lm(y ~ x, data = df)"}
  
  // Get regression summary
  let summary_result = R.call_function {
    function = "summary",
    args = [lm_result]
  }
  
  // Extract coefficients
  let coefficients = R.eval {code = "coef(lm_result)"}
  
  {
    model = lm_result,
    summary = Polyglot.to_funk_string {value = summary_result},
    coefficients = coefficients
  }
```

### 4. Multi-Language Integration

```funk
// Combining multiple languages in one computation
let multi_language_pipeline = do
  // Step 1: Generate data with R
  let r_data = R.eval {code = """
    set.seed(42)
    data <- rnorm(100, mean = 50, sd = 10)
    data
  """}
  
  // Step 2: Process with Python
  let python_processor = Python.eval {code = """
def process_data(data):
    import statistics
    processed = [x * 1.1 for x in data if x > statistics.mean(data)]
    return {
        'processed': processed,
        'count': len(processed),
        'average': statistics.mean(processed)
    }
process_data
  """}
  
  let processed_data = Polyglot.call {value = python_processor, args = [r_data]}
  
  // Step 3: Visualize with JavaScript (conceptually)
  let js_visualizer = JavaScript.eval {code = """
(function(data) {
    return {
        chart_type: 'histogram',
        data_points: data.count,
        average: data.average,
        title: 'Processed Data Distribution'
    };
})
  """}
  
  let visualization = Polyglot.call {value = js_visualizer, args = [processed_data]}
  
  // Step 4: Combine results in Funk
  let count = Polyglot.get_member {value = processed_data, member = "count"}
  let average = Polyglot.get_member {value = processed_data, member = "average"}
  let chart_type = Polyglot.get_member {value = visualization, member = "chart_type"}
  
  {
    data_count = Polyglot.to_funk_int {value = count},
    data_average = Polyglot.to_funk_float {value = average},
    chart_type = Polyglot.to_funk_string {value = chart_type}
  }
```

## Advanced Patterns

### 1. Error Handling Across Languages

```funk
// Safe polyglot calls with error handling
type PolyglotResult = 
  | Success { value: PolyglotValue }
  | Error { message: String, language: String }

let safe_polyglot_eval = {language: String, code: String} ->
  try
    let result = Polyglot.eval {language = language, code = code}
    Success {value = result}
  catch e ->
    Error {message = e.message, language = language}

// Usage with error handling
let safe_computation = do
  let python_result = safe_polyglot_eval {
    language = "python",
    code = "import math; math.sqrt(-1)"  // This will cause an error
  }
  
  match python_result is
    Success {value} ->
      let number = Polyglot.to_funk_float {value = value}
      Console.print {message = "Result: " ++ floatToString {val = number}}
    Error {message, language} ->
      Console.print {message = "Error in " ++ language ++ ": " ++ message}
```

### 2. Shared State Between Languages

```funk
// Sharing data between languages through polyglot context
let shared_computation = do
  // Set up shared data in JavaScript
  let _ = JavaScript.eval {code = """
    globalThis.sharedData = {
      values: [1, 2, 3, 4, 5],
      multiplier: 2.5,
      transform: function(arr, mult) {
        return arr.map(x => x * mult);
      }
    };
  """}
  
  // Access shared data from Python
  let python_result = Python.eval {code = """
import js
shared = js.eval("globalThis.sharedData")
result = shared.transform(shared.values, shared.multiplier)
list(result)
  """}
  
  // Process in R
  let r_result = R.eval {code = """
# Access the Python result (if possible in this setup)
# data <- py$python_result
# mean(data)
mean(c(2.5, 5.0, 7.5, 10.0, 12.5))  # Simulated result
  """}
  
  {
    python_result = python_result,
    r_mean = Polyglot.to_funk_float {value = r_result}
  }
```

### 3. Function Passing Between Languages

```funk
// Passing Funk functions to other languages
let function_interop = do
  // Define a Funk function to be used in JavaScript
  let funk_processor = {x: Int} -> x * x + 1
  
  // Convert Funk function for use in JavaScript
  let js_wrapper = JavaScript.eval {code = """
(function(funkFunction) {
    return function(array) {
        return array.map(x => funkFunction(x));
    };
})
  """}
  
  // Create JavaScript array
  let js_array = JavaScript.eval {code = "[1, 2, 3, 4, 5]"}
  
  // Apply Funk function via JavaScript
  let wrapped_funk_fn = Polyglot.call {value = js_wrapper, args = [funk_processor]}
  let result = Polyglot.call {value = wrapped_funk_fn, args = [js_array]}
  
  result
```

### 4. Performance-Critical Polyglot Operations

```funk
// Optimize polyglot calls for performance
let optimized_polyglot = do
  // Pre-compile frequently used code
  let compiled_python_fn = Python.eval {code = """
import numpy as np

def fast_matrix_multiply(a, b):
    return np.dot(a, b).tolist()

fast_matrix_multiply
  """}
  
  // Batch operations to minimize boundary crossings
  let matrix_a = [[1, 2], [3, 4]]
  let matrix_b = [[5, 6], [7, 8]]
  
  let result = Polyglot.call {
    value = compiled_python_fn,
    args = [
      Polyglot.eval {language = "python", code = str(matrix_a)},
      Polyglot.eval {language = "python", code = str(matrix_b)}
    ]
  }
  
  result
```

## Configuration and Setup

### 1. Enable Polyglot Languages

```java
// Enable specific languages in GraalVM context
Context context = Context.newBuilder("funk", "js", "python", "R")
    .allowAllAccess(true)  // Enable all polyglot access
    .allowExperimentalOptions(true)  // For experimental languages
    .build();
```

### 2. Language-Specific Configuration

```java
// Configure individual languages
Context context = Context.newBuilder("funk")
    .allowAllAccess(true)
    .option("js.ecmascript-version", "2022")  // JavaScript version
    .option("python.ForceImportSite", "true")  // Python import behavior
    .option("R.PrintErrorStacktracesToFile", "false")  // R error handling
    .build();
```

### 3. Resource Limits

```java
// Set resource limits for polyglot execution
Context context = Context.newBuilder("funk", "js", "python")
    .allowAllAccess(true)
    .resourceLimits(ResourceLimits.newBuilder()
        .statementLimit(10000, null)  // Limit statements
        .timeLimit(Duration.ofSeconds(30), null)  // Time limit
        .build())
    .build();
```

## Performance Considerations

### 1. Language Startup Costs

```funk
// Warm up languages to amortize startup costs
let warm_up_languages = do
  // Warm up JavaScript
  let _ = JavaScript.eval {code = "1 + 1"}
  
  // Warm up Python
  let _ = Python.eval {code = "1 + 1"}
  
  // Warm up R
  let _ = R.eval {code = "1 + 1"}
  
  Console.print {message = "Languages warmed up"}
```

### 2. Minimize Boundary Crossings

```funk
// Good: Batch operations in target language
let efficient_polyglot = do
  let result = Python.eval {code = """
data = [1, 2, 3, 4, 5]
processed = [x * 2 for x in data if x > 2]
{
    'original_count': len(data),
    'processed_count': len(processed),
    'sum': sum(processed),
    'average': sum(processed) / len(processed)
}
  """}
  
  result

// Avoid: Multiple small boundary crossings
let inefficient_polyglot = do
  let data = Python.eval {code = "[1, 2, 3, 4, 5]"}
  let filtered = Python.call_method {object = data, method = "filter", args = [...]}
  let mapped = Python.call_method {object = filtered, method = "map", args = [...]}
  let sum_result = Python.call_function {function = "sum", args = [mapped]}
  sum_result
```

### 3. Memory Management

```funk
// Explicit cleanup for large polyglot objects
effect PolyglotMemory {
  cleanup: {value: PolyglotValue} -> Unit <PolyglotMemory>
  gc_collect: {} -> Unit <PolyglotMemory>
}

let memory_conscious_computation = do
  let large_data = Python.eval {code = "list(range(1000000))"}
  
  let result = process_large_data {data = large_data}
  
  // Cleanup when done
  PolyglotMemory.cleanup {value = large_data}
  
  result
```

## Testing Polyglot Integration

### 1. Unit Tests for Polyglot Functions

```funk
// Test polyglot functionality
let test_javascript_integration = do
  let result = JavaScript.eval {code = "2 + 2"}
  let number = Polyglot.to_funk_int {value = result}
  
  assert {condition = number == 4, message = "JavaScript 2+2 should equal 4"}
  Console.print {message = "✓ JavaScript integration test passed"}

let test_python_integration = do
  let result = Python.eval {code = "len([1, 2, 3])"}
  let length = Polyglot.to_funk_int {value = result}
  
  assert {condition = length == 3, message = "Python len([1,2,3]) should equal 3"}
  Console.print {message = "✓ Python integration test passed"}

let test_r_integration = do
  let result = R.eval {code = "mean(c(1, 2, 3))"}
  let mean_val = Polyglot.to_funk_float {value = result}
  
  assert {condition = abs {val = mean_val - 2.0} < 0.001, message = "R mean should be 2.0"}
  Console.print {message = "✓ R integration test passed"}
```

### 2. Error Handling Tests

```funk
// Test error propagation across languages
let test_error_handling = do
  let python_error = safe_polyglot_eval {
    language = "python",
    code = "1 / 0"  // Division by zero
  }
  
  match python_error is
    Error {message, language} ->
      Console.print {message = "✓ Error correctly caught: " ++ message}
    Success {value} ->
      Console.print {message = "✗ Expected error but got success"}
```

## Future Enhancements

### 1. Type-Safe Polyglot Bindings

```funk
// Future: Generate type-safe bindings
type PythonNumPy = {
  array: {data: [Float]} -> NumpyArray,
  dot: {a: NumpyArray, b: NumpyArray} -> NumpyArray,
  mean: {arr: NumpyArray} -> Float
}

let typed_numpy: PythonNumPy = generate_python_bindings {module = "numpy"}
```

### 2. Automatic Type Conversion

```funk
// Future: Automatic conversion between language types
let seamless_interop = do
  let funk_list = [1, 2, 3, 4, 5]
  
  // Automatic conversion to JavaScript array
  let js_result = JavaScript.call {
    function = "Math.max",
    args = funk_list  // Automatically converted
  }
  
  // Automatic conversion back to Funk
  let funk_result: Int = js_result  // Automatically converted
  funk_result
```

### 3. Performance Optimizations

- **JIT compilation** across language boundaries
- **Shared memory** for large data structures
- **Vectorized operations** using SIMD across languages
- **Escape analysis** for temporary polyglot objects

---

This polyglot integration enables Funk to leverage the entire GraalVM language ecosystem while maintaining type safety and functional programming principles through the effect system.
