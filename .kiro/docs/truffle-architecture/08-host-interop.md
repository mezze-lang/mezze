# Host Language Integration: Calling Java/Kotlin from Funk

## Overview

This document explains how Funk code can call Java and Kotlin methods using GraalVM's `HostAccess.ALL` and `HostClassLookup` capabilities. This approach leverages GraalVM's built-in host interoperability rather than custom reflection, providing better performance and integration.

## Core Concepts

### 1. GraalVM Host Access

GraalVM provides seamless access to host JVM classes and objects through:
- **HostAccess.ALL**: Enables full access to host classes and methods
- **HostClassLookup**: Allows dynamic class loading and instantiation
- **Automatic type conversion**: Between Funk values and Java objects
- **Method resolution**: Automatic overload resolution and type coercion

### 2. Funk Type System Integration

Since Funk doesn't have an `Any` type, we use:
- **Records for arguments**: `{arg1 = value1, arg2 = value2}`
- **Explicit return types**: Different effect operations for different expected types
- **Type-safe wrappers**: Funk variants for host objects
- **Result types**: For error handling

## Host Access Effect Design

### 1. Core Host Effect

```funk
// Host interop effect using Funk's type system
effect Host {
  get_class: {name: String} -> HostClass <Host>
  new_instance: {class: HostClass, args: {}} -> HostObject <Host>
  call_for_string: {object: HostObject, method: String, args: {}} -> String <Host>
  call_for_int: {object: HostObject, method: String, args: {}} -> Int <Host>
  call_for_boolean: {object: HostObject, method: String, args: {}} -> Boolean <Host>
  call_for_object: {object: HostObject, method: String, args: {}} -> HostObject <Host>
  static_for_string: {class: HostClass, method: String, args: {}} -> String <Host>
  static_for_int: {class: HostClass, method: String, args: {}} -> Int <Host>
  static_for_float: {class: HostClass, method: String, args: {}} -> Float <Host>
  get_field: {object: HostObject, field: String} -> HostValue <Host>
  set_field: {object: HostObject, field: String, value: HostValue} -> Unit <Host>
}

// Host types as Funk variants
type HostClass = | HostClass { className: String, classObject: Opaque } |
type HostObject = | HostObject { objectType: String, objectRef: Opaque } |
type HostValue = 
  | HostString { value: String }
  | HostInt { value: Int }
  | HostFloat { value: Float }
  | HostBoolean { value: Boolean }
  | HostNull
  | HostObject { objectType: String, objectRef: Opaque }
  | HostArray { elements: [HostValue] }
```

### 2. Implementation with GraalVM Host Access

```kotlin
class FunkHostAccessHandler(private val context: FunkContext) : SyncEffectHandler {
    
    override fun handle(operation: String, arguments: FunkValue): FunkValue {
        if (!context.isHostAccessAllowed()) {
            throw FunkException("Host access not allowed. Enable with HostAccess.ALL")
        }
        
        return when (operation) {
            "get_class" -> handleGetClass(arguments)
            "new_instance" -> handleNewInstance(arguments)
            "call_for_string" -> handleTypedCall(arguments, String::class.java)
            "call_for_int" -> handleTypedCall(arguments, Int::class.java)
            "call_for_boolean" -> handleTypedCall(arguments, Boolean::class.java)
            "call_for_object" -> handleTypedCall(arguments, Any::class.java)
            "static_for_string" -> handleTypedStaticCall(arguments, String::class.java)
            "static_for_int" -> handleTypedStaticCall(arguments, Int::class.java)
            "static_for_float" -> handleTypedStaticCall(arguments, Double::class.java)
            "get_field" -> handleGetField(arguments)
            "set_field" -> handleSetField(arguments)
            else -> throw FunkException("Unknown host operation: $operation")
        }
    }
    
    private fun handleGetClass(arguments: FunkValue): FunkValue {
        val args = arguments as FunkRecord
        val className = (args.fields["name"] as FunkString).value
        
        return try {
            // Use GraalVM's host class lookup
            val hostClass = context.env?.lookupHostSymbol(className)
            if (hostClass != null) {
                FunkVariant("HostClass", FunkRecord(mapOf(
                    "className" to FunkString(className),
                    "classObject" to HostObjectWrapper(hostClass)
                )))
            } else {
                throw FunkException("Host class not found: $className")
            }
        } catch (e: Exception) {
            throw FunkException("Failed to get host class '$className': ${e.message}", e)
        }
    }
    
    private fun handleNewInstance(arguments: FunkValue): FunkValue {
        val args = arguments as FunkRecord
        val hostClassVariant = args.fields["class"] as FunkVariant
        val constructorArgs = args.fields["args"] as FunkRecord
        
        // Extract host class from variant
        val hostClassRecord = hostClassVariant.value as FunkRecord
        val hostClass = (hostClassRecord.fields["classObject"] as HostObjectWrapper).hostObject
        val className = (hostClassRecord.fields["className"] as FunkString).value
        
        return try {
            // Use Truffle interop for instantiation
            val interop = InteropLibrary.getUncached()
            val argArray = convertRecordToHostArgs(constructorArgs)
            val instance = interop.instantiate(hostClass, *argArray)
            
            FunkVariant("HostObject", FunkRecord(mapOf(
                "objectType" to FunkString(className),
                "objectRef" to HostObjectWrapper(instance)
            )))
        } catch (e: Exception) {
            throw FunkException("Failed to create instance of $className: ${e.message}", e)
        }
    }
    
    private fun <T> handleTypedCall(arguments: FunkValue, expectedType: Class<T>): FunkValue {
        val args = arguments as FunkRecord
        val hostObjectVariant = args.fields["object"] as FunkVariant
        val methodName = (args.fields["method"] as FunkString).value
        val methodArgs = args.fields["args"] as FunkRecord
        
        // Extract host object from variant
        val hostObjectRecord = hostObjectVariant.value as FunkRecord
        val hostObject = (hostObjectRecord.fields["objectRef"] as HostObjectWrapper).hostObject
        
        return try {
            // Use Truffle interop for method calls
            val interop = InteropLibrary.getUncached()
            val argArray = convertRecordToHostArgs(methodArgs)
            val result = interop.invokeMember(hostObject, methodName, *argArray)
            
            // Convert result based on expected type
            convertHostResultToFunk(result, expectedType)
        } catch (e: Exception) {
            throw FunkException("Failed to call method '$methodName': ${e.message}", e)
        }
    }
    
    private fun <T> handleTypedStaticCall(arguments: FunkValue, expectedType: Class<T>): FunkValue {
        val args = arguments as FunkRecord
        val hostClassVariant = args.fields["class"] as FunkVariant
        val methodName = (args.fields["method"] as FunkString).value
        val methodArgs = args.fields["args"] as FunkRecord
        
        // Extract host class from variant
        val hostClassRecord = hostClassVariant.value as FunkRecord
        val hostClass = (hostClassRecord.fields["classObject"] as HostObjectWrapper).hostObject
        
        return try {
            val interop = InteropLibrary.getUncached()
            val argArray = convertRecordToHostArgs(methodArgs)
            val result = interop.invokeMember(hostClass, methodName, *argArray)
            
            convertHostResultToFunk(result, expectedType)
        } catch (e: Exception) {
            throw FunkException("Failed to call static method '$methodName': ${e.message}", e)
        }
    }
    
    private fun convertRecordToHostArgs(record: FunkRecord): Array<Any?> {
        // Convert record fields to ordered array based on field names
        return record.fields.entries
            .sortedBy { it.key }  // Consistent ordering
            .map { (_, value) -> convertFunkToHost(value) }
            .toTypedArray()
    }
    
    private fun <T> convertHostResultToFunk(result: Any?, expectedType: Class<T>): FunkValue {
        return when (expectedType) {
            String::class.java -> FunkString(result?.toString() ?: "")
            Int::class.java -> FunkInt((result as? Number)?.toInt() ?: 0)
            Double::class.java -> FunkFloat((result as? Number)?.toDouble() ?: 0.0)
            Boolean::class.java -> FunkBool(result as? Boolean ?: false)
            else -> {
                // Return as HostObject for complex types
                if (result != null) {
                    FunkVariant("HostObject", FunkRecord(mapOf(
                        "objectType" to FunkString(result::class.simpleName ?: "Unknown"),
                        "objectRef" to HostObjectWrapper(result)
                    )))
                } else {
                    FunkNull
                }
            }
        }
    }
    
    override fun canHandle(effectName: String): Boolean = effectName == "Host"
}

// Wrapper for host objects
class HostObjectWrapper(val hostObject: Any) : FunkValue() {
    override fun getDisplayString(): String = "HostObject(${hostObject::class.simpleName})"
    override fun toString(): String = hostObject.toString()
}
```

### 3. Type Conversion System

```kotlin
object HostInteropConverter {
    
    fun convertFunkToHost(funkValue: FunkValue): Any? {
        return when (funkValue) {
            is FunkInt -> funkValue.value
            is FunkFloat -> funkValue.value
            is FunkString -> funkValue.value
            is FunkBool -> funkValue.value
            is FunkNull -> null
            is HostObjectWrapper -> funkValue.hostObject  // Unwrap host objects
            is FunkRecord -> {
                // Convert to Map for host consumption
                val map = mutableMapOf<String, Any?>()
                funkValue.fields.forEach { (key, value) ->
                    map[key] = convertFunkToHost(value)
                }
                map
            }
            is FunkVariant -> {
                // Convert variants to maps with constructor info
                mapOf(
                    "constructor" to funkValue.constructor,
                    "data" to funkValue.value?.let { convertFunkToHost(it) }
                )
            }
            else -> funkValue.toString()  // Fallback to string representation
        }
    }
    
    fun convertHostToFunk(hostValue: Any?): FunkValue {
        return when (hostValue) {
            null -> FunkNull
            is Int -> FunkInt(hostValue)
            is Long -> FunkInt(hostValue.toInt())  // Convert to Funk Int
            is Double -> FunkFloat(hostValue)
            is Float -> FunkFloat(hostValue.toDouble())
            is String -> FunkString(hostValue)
            is Boolean -> FunkBool(hostValue)
            is Collection<*> -> {
                // Convert to Funk list (as nested variants)
                hostValue.foldRight(FunkVariant("Nil", null)) { item, acc ->
                    FunkVariant("Cons", FunkRecord(mapOf(
                        "head" to convertHostToFunk(item),
                        "tail" to acc
                    )))
                }
            }
            is Map<*, *> -> {
                // Convert to Funk record
                val fields = mutableMapOf<String, FunkValue>()
                hostValue.forEach { (key, value) ->
                    if (key is String) {
                        fields[key] = convertHostToFunk(value)
                    }
                }
                FunkRecord(fields)
            }
            is Array<*> -> {
                // Convert arrays to Funk lists
                hostValue.foldRight(FunkVariant("Nil", null)) { item, acc ->
                    FunkVariant("Cons", FunkRecord(mapOf(
                        "head" to convertHostToFunk(item),
                        "tail" to acc
                    )))
                }
            }
            else -> {
                // Wrap other host objects
                HostObjectWrapper(hostValue)
            }
        }
    }
}
```

## Usage Examples

### 1. File Operations

```funk
// File operations using Java File API
let file_operations = {path: String} -> do
  // Get File class
  let FileClass = Host.get_class {name = "java.io.File"}
  
  // Create File instance with record args
  let file = Host.new_instance {
    class = FileClass,
    args = {path = path}  // Record with named argument
  }
  
  // Call methods with expected return types
  let exists = Host.call_for_boolean {
    object = file,
    method = "exists",
    args = {}  // Empty record for no arguments
  }
  
  let name = Host.call_for_string {
    object = file,
    method = "getName",
    args = {}
  }
  
  let length = Host.call_for_int {
    object = file,
    method = "length",
    args = {}
  }
  
  let is_directory = Host.call_for_boolean {
    object = file,
    method = "isDirectory",
    args = {}
  }
  
  {
    exists = exists, 
    name = name, 
    length = length, 
    is_directory = is_directory
  }

// Usage
let file_info = file_operations {path = "/Users/shriyans/Projects/funk/README.md"}
Console.print {message = "File: " ++ file_info.name ++ ", Size: " ++ intToString {val = file_info.length}}
```

### 2. String Processing

```funk
// Advanced string operations using Java String methods
let string_operations = {text: String} -> do
  let StringClass = Host.get_class {name = "java.lang.String"}
  
  // Create String instance (though Funk strings are automatically compatible)
  let java_string = Host.new_instance {
    class = StringClass,
    args = {value = text}
  }
  
  // Use String methods with typed returns
  let length = Host.call_for_int {
    object = java_string,
    method = "length",
    args = {}
  }
  
  let upper = Host.call_for_string {
    object = java_string,
    method = "toUpperCase",
    args = {}
  }
  
  let contains_hello = Host.call_for_boolean {
    object = java_string,
    method = "contains",
    args = {substring = "hello"}
  }
  
  let starts_with = Host.call_for_boolean {
    object = java_string,
    method = "startsWith",
    args = {prefix = "Hello"}
  }
  
  // String splitting returns array - gets converted to Funk list
  let words = Host.call_for_object {
    object = java_string,
    method = "split",
    args = {regex = " "}
  }
  
  {
    length = length,
    upper = upper, 
    contains_hello = contains_hello,
    starts_with = starts_with,
    words = words
  }
```

### 3. Math Operations

```funk
// Math operations using Java Math class static methods
let math_operations = {x: Float, y: Float} -> do
  let MathClass = Host.get_class {name = "java.lang.Math"}
  
  let max_val = Host.static_for_float {
    class = MathClass,
    method = "max",
    args = {a = x, b = y}
  }
  
  let min_val = Host.static_for_float {
    class = MathClass,
    method = "min",
    args = {a = x, b = y}
  }
  
  let sqrt_x = Host.static_for_float {
    class = MathClass,
    method = "sqrt",
    args = {value = x}
  }
  
  let pow_xy = Host.static_for_float {
    class = MathClass,
    method = "pow",
    args = {base = x, exponent = y}
  }
  
  let random = Host.static_for_float {
    class = MathClass,
    method = "random",
    args = {}
  }
  
  // Math constants
  let pi = Host.get_field {
    object = MathClass,
    field = "PI"
  }
  
  {
    max = max_val,
    min = min_val,
    sqrt = sqrt_x,
    power = pow_xy,
    random = random,
    pi = pi
  }
```

### 4. Collections

```funk
// Working with Java collections
let collection_example = do
  let ArrayListClass = Host.get_class {name = "java.util.ArrayList"}
  
  let list = Host.new_instance {
    class = ArrayListClass,
    args = {}
  }
  
  // Add elements (returns boolean indicating success)
  let added1 = Host.call_for_boolean {
    object = list,
    method = "add",
    args = {element = "Hello"}
  }
  
  let added2 = Host.call_for_boolean {
    object = list,
    method = "add", 
    args = {element = "World"}
  }
  
  let added3 = Host.call_for_boolean {
    object = list,
    method = "add",
    args = {element = "from"}
  }
  
  let added4 = Host.call_for_boolean {
    object = list,
    method = "add",
    args = {element = "Funk"}
  }
  
  // Get size
  let size = Host.call_for_int {
    object = list,
    method = "size",
    args = {}
  }
  
  // Get elements
  let first = Host.call_for_string {
    object = list,
    method = "get",
    args = {index = 0}
  }
  
  let last = Host.call_for_string {
    object = list,
    method = "get",
    args = {index = size - 1}
  }
  
  // Convert to array
  let array = Host.call_for_object {
    object = list,
    method = "toArray",
    args = {}
  }
  
  {
    size = size,
    first = first,
    last = last,
    all_added = added1 && added2 && added3 && added4,
    array = array
  }
```

### 5. Date and Time

```funk
// Date and time operations using Java time APIs
let datetime_operations = do
  let LocalDateTimeClass = Host.get_class {name = "java.time.LocalDateTime"}
  let DateTimeFormatterClass = Host.get_class {name = "java.time.format.DateTimeFormatter"}
  
  // Get current time (static method)
  let now = Host.static_for_object {
    class = LocalDateTimeClass,
    method = "now",
    args = {}
  }
  
  // Create specific date
  let specific_date = Host.static_for_object {
    class = LocalDateTimeClass,
    method = "of",
    args = {
      year = 2024,
      month = 12,
      dayOfMonth = 25,
      hour = 10,
      minute = 30,
      second = 0
    }
  }
  
  // Create formatter
  let formatter = Host.static_for_object {
    class = DateTimeFormatterClass,
    method = "ofPattern",
    args = {pattern = "yyyy-MM-dd HH:mm:ss"}
  }
  
  // Format dates
  let formatted_now = Host.call_for_string {
    object = now,
    method = "format",
    args = {formatter = formatter}
  }
  
  let formatted_specific = Host.call_for_string {
    object = specific_date,
    method = "format",
    args = {formatter = formatter}
  }
  
  // Extract components
  let year = Host.call_for_int {
    object = now,
    method = "getYear",
    args = {}
  }
  
  let month = Host.call_for_int {
    object = now,
    method = "getMonthValue",
    args = {}
  }
  
  let day = Host.call_for_int {
    object = now,
    method = "getDayOfMonth",
    args = {}
  }
  
  {
    now_formatted = formatted_now,
    specific_formatted = formatted_specific,
    current_year = year,
    current_month = month,
    current_day = day
  }
```

### 6. System Properties and Environment

```funk
// Access system properties and environment variables
let system_info = do
  let SystemClass = Host.get_class {name = "java.lang.System"}
  
  // Get system properties
  let java_version = Host.static_for_string {
    class = SystemClass,
    method = "getProperty",
    args = {key = "java.version"}
  }
  
  let os_name = Host.static_for_string {
    class = SystemClass,
    method = "getProperty",
    args = {key = "os.name"}
  }
  
  let user_home = Host.static_for_string {
    class = SystemClass,
    method = "getProperty",
    args = {key = "user.home"}
  }
  
  // Get current time in milliseconds
  let current_time = Host.static_for_int {
    class = SystemClass,
    method = "currentTimeMillis",
    args = {}
  }
  
  // Get environment variables
  let path_env = Host.static_for_string {
    class = SystemClass,
    method = "getenv",
    args = {name = "PATH"}
  }
  
  {
    java_version = java_version,
    os_name = os_name,
    user_home = user_home,
    current_time = current_time,
    path = path_env
  }
```

## Advanced Patterns

### 1. Result Types for Error Handling

```funk
// Safe host calls with Result types
type HostResult = 
  | Success { value: String }
  | Error { message: String }

let safe_host_call = {operation: () -> String} ->
  try
    Success {value = operation {}}
  catch e ->
    Error {message = "Host call failed: " ++ e.message}

// Usage
let safe_file_read = {path: String} -> 
  safe_host_call {() ->
    let FileClass = Host.get_class {name = "java.io.File"}
    let file = Host.new_instance {class = FileClass, args = {path = path}}
    
    let exists = Host.call_for_boolean {
      object = file, 
      method = "exists", 
      args = {}
    }
    
    if exists then
      // Simulate reading file content
      let PathsClass = Host.get_class {name = "java.nio.file.Paths"}
      let FilesClass = Host.get_class {name = "java.nio.file.Files"}
      
      let path_obj = Host.static_for_object {
        class = PathsClass,
        method = "get",
        args = {first = path}
      }
      
      Host.static_for_string {
        class = FilesClass,
        method = "readString",
        args = {path = path_obj}
      }
    else
      throw {message = "File does not exist: " ++ path}
  }
```

### 2. Builder Pattern Integration

```funk
// Java StringBuilder using builder pattern
let string_builder_example = {words: [String]} -> do
  let StringBuilderClass = Host.get_class {name = "java.lang.StringBuilder"}
  
  let builder = Host.new_instance {
    class = StringBuilderClass,
    args = {}
  }
  
  // Append each word (builder pattern returns the same object)
  let final_builder = fold {
    list = words,
    init = builder,
    f = {acc_builder, word} ->
      Host.call_for_object {
        object = acc_builder,
        method = "append",
        args = {str = word ++ " "}
      }
  }
  
  // Convert to string
  Host.call_for_string {
    object = final_builder,
    method = "toString",
    args = {}
  }

// Usage
let result = string_builder_example {words = ["Hello", "world", "from", "Funk"]}
Console.print {message = result}  // "Hello world from Funk "
```

### 3. Type-Safe Wrappers

```funk
// Type-safe wrappers for common Java types
type JavaFile = HostObject
type JavaList = HostObject
type JavaMap = HostObject
type JavaDate = HostObject

let file_wrapper = {
  create: {path: String} -> JavaFile = {path} -> do
    let FileClass = Host.get_class {name = "java.io.File"}
    Host.new_instance {class = FileClass, args = {path = path}},
    
  exists: {file: JavaFile} -> Boolean = {file} ->
    Host.call_for_boolean {object = file, method = "exists", args = {}},
    
  get_name: {file: JavaFile} -> String = {file} ->
    Host.call_for_string {object = file, method = "getName", args = {}},
    
  get_size: {file: JavaFile} -> Int = {file} ->
    Host.call_for_int {object = file, method = "length", args = {}},
    
  is_directory: {file: JavaFile} -> Boolean = {file} ->
    Host.call_for_boolean {object = file, method = "isDirectory", args = {}},
    
  list_files: {file: JavaFile} -> [JavaFile] = {file} -> do
    let files_array = Host.call_for_object {
      object = file,
      method = "listFiles",
      args = {}
    }
    // Convert array to Funk list (automatic conversion)
    convert_host_array_to_funk_list {array = files_array}
}

// Usage with type safety
let file_operations_safe = {path: String} -> do
  let file = file_wrapper.create {path = path}
  let exists = file_wrapper.exists {file = file}
  
  if exists then
    let name = file_wrapper.get_name {file = file}
    let size = file_wrapper.get_size {file = file}
    let is_dir = file_wrapper.is_directory {file = file}
    
    let info = if is_dir then
      let files = file_wrapper.list_files {file = file}
      "Directory: " ++ name ++ ", Files: " ++ intToString {val = length {list = files}}
    else
      "File: " ++ name ++ ", Size: " ++ intToString {val = size} ++ " bytes"
    
    Success {value = info}
  else
    Error {message = "File does not exist: " ++ path}
```

### 4. Functional Interface Integration

```funk
// Working with Java functional interfaces
let functional_interface_example = do
  let ArraysClass = Host.get_class {name = "java.util.Arrays"}
  let numbers = [5, 2, 8, 1, 9, 3]
  
  // Convert Funk list to Java array
  let java_array = convert_funk_list_to_java_array {list = numbers}
  
  // Sort array using Comparator (functional interface)
  let sorted_array = Host.static_for_object {
    class = ArraysClass,
    method = "sort",
    args = {
      array = java_array,
      comparator = {a, b -> compare {x = a, y = b}}  // Funk function as Java Comparator
    }
  }
  
  // Convert back to Funk list
  convert_java_array_to_funk_list {array = sorted_array}
```

## Configuration and Setup

### 1. Enable Host Access in Context Creation

```java
// When creating GraalVM context (from Java host application)
Context context = Context.newBuilder("funk")
    .allowHostAccess(HostAccess.ALL)  // Enable full host access
    .allowHostClassLookup(className -> true)  // Allow all class lookups
    .build();
```

### 2. Selective Host Access (Security)

```java
// More restrictive host access for security-sensitive environments
HostAccess restrictedAccess = HostAccess.newBuilder()
    .allowPublicAccess(true)
    .allowAllImplementations(true)
    .allowAllClassImplementations(true)
    .allowArrayAccess(true)
    .allowListAccess(true)
    .allowMapAccess(true)
    .build();

Context context = Context.newBuilder("funk")
    .allowHostAccess(restrictedAccess)
    .allowHostClassLookup(className -> {
        // Only allow specific packages
        return className.startsWith("java.") || 
               className.startsWith("javax.") ||
               className.startsWith("com.mycompany.");
    })
    .build();
```

### 3. Context Configuration in FunkLanguage

```kotlin
// In FunkLanguage.kt - configure host access
override fun createContext(env: Env): FunkContext {
    // Verify host access is enabled
    if (!env.isHostLookupAllowed) {
        System.err.println("Warning: Host access not enabled. Use HostAccess.ALL for Java interop")
    }
    
    return FunkContext(this, env)
}
```

## Performance Considerations

### 1. Method Call Overhead

```funk
// Minimize boundary crossings by batching operations
let efficient_string_processing = {text: String} -> do
  let StringClass = Host.get_class {name = "java.lang.String"}
  let java_string = Host.new_instance {class = StringClass, args = {value = text}}
  
  // Batch multiple operations on the same object
  let length = Host.call_for_int {object = java_string, method = "length", args = {}}
  let upper = Host.call_for_string {object = java_string, method = "toUpperCase", args = {}}
  let lower = Host.call_for_string {object = java_string, method = "toLowerCase", args = {}}
  
  {length = length, upper = upper, lower = lower}

// Avoid: Multiple separate host calls
let inefficient_processing = {text: String} -> do
  let result1 = process_string_part1 {text = text}  // Host call 1
  let result2 = process_string_part2 {text = result1}  // Host call 2  
  let result3 = process_string_part3 {text = result2}  // Host call 3
  result3
```

### 2. Type Conversion Optimization

```kotlin
// Cache converted values to avoid repeated conversion
class ConversionCache {
    private val cache = ConcurrentHashMap<FunkValue, Any>()
    
    fun getCachedConversion(funkValue: FunkValue): Any? {
        return cache.computeIfAbsent(funkValue) { 
            HostInteropConverter.convertFunkToHost(it)
        }
    }
    
    fun clearCache() {
        cache.clear()
    }
}
```

### 3. Object Lifecycle Management

```funk
// Proper resource management for host objects
effect ResourceManager {
  acquire: {resource_type: String, config: {}} -> HostObject <ResourceManager>
  release: {resource: HostObject} -> Unit <ResourceManager>
  with_resource: {resource_type: String, config: {}, operation: HostObject -> a} -> a <ResourceManager>
}

// Usage
let database_operation = ResourceManager.with_resource {
  resource_type = "DatabaseConnection",
  config = {url = "jdbc:postgresql://localhost/mydb", user = "user", password = "pass"},
  operation = {connection} -> do
    let result = Host.call_for_object {
      object = connection,
      method = "executeQuery",
      args = {sql = "SELECT * FROM users"}
    }
    process_result_set {result = result}
}  // Connection automatically closed
```

## Future Enhancements

### 1. Automatic Binding Generation
- **Generate Funk bindings** from Java bytecode analysis
- **Type-safe interfaces** with compile-time checking
- **Documentation generation** from JavaDoc comments

### 2. Performance Optimizations
- **Method call inlining** for frequently used host methods
- **Type specialization** based on host method signatures
- **Escape analysis** for temporary host objects

### 3. Enhanced Error Handling
- **Structured exception mapping** from Java exceptions to Funk effects
- **Stack trace preservation** across language boundaries
- **Timeout and cancellation** support for long-running host operations

---

This host interop system provides Funk with seamless access to the vast Java ecosystem while maintaining type safety and the functional programming paradigm through the effect system.
