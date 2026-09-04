# std/interop/foreign.mz

Core types and functions shared by all interop libraries.

## Types

### Foreign

Opaque wrapper for values from other languages. Cannot be inspected or pattern matched directly - must use conversion functions.

```mezze
# Opaque type - implementation is native
pub type Foreign
```

### InteropError

All errors from foreign code are captured and wrapped:

```mezze
pub type InteropError =
    # Language runtime not available
    | LanguageNotAvailable { language: String, reason: String }

    # Foreign code threw an exception
    | RuntimeError { language: String, message: String, stack: String }

    # Could not convert between Mezze and foreign types
    | ConversionError { from_type: String, to_type: String, message: String }

    # Tried to access a member that doesn't exist
    | MemberNotFound { object: Foreign, member: String }

    # Method call failed
    | InvocationError { object: Foreign, method: String, message: String }

    # Operation timed out
    | Timeout { operation: String, limit_ms: Int }

    # Memory limit exceeded
    | MemoryExceeded { operation: String, limit_mb: Int }

    # Context was closed or invalid
    | InvalidContext { reason: String }
```

### Context

Handle to a foreign language context. Can be reused across calls to share state.

```mezze
# Opaque type - implementation is native
pub type Context
```

## Conversion Functions

### Mezze to Foreign

```mezze
# Wrap any Mezze value as Foreign
# Works for: Int, Float, String, Bool, Array, Record
pub let from: { val: 'a } -> Foreign = { val } ->
    __native_foreign_from { val = val }
```

### Foreign to Mezze (with explicit type)

```mezze
# Convert to Int
pub let to_int: { val: Foreign } -> Result Int InteropError = { val } ->
    __native_foreign_to_int { val = val }

# Convert to Float
pub let to_float: { val: Foreign } -> Result Float InteropError = { val } ->
    __native_foreign_to_float { val = val }

# Convert to String
pub let to_string: { val: Foreign } -> Result String InteropError = { val } ->
    __native_foreign_to_string { val = val }

# Convert to Bool
pub let to_bool: { val: Foreign } -> Result Bool InteropError = { val } ->
    __native_foreign_to_bool { val = val }

# Convert to Array (elements remain Foreign)
pub let to_array: { val: Foreign } -> Result [Foreign] InteropError = { val } ->
    __native_foreign_to_array { val = val }
```

## Object Operations

### Member Access

```mezze
# Get a member/property from a foreign object
pub let get: { obj: Foreign, member: String } -> Result Foreign InteropError = { obj, member } ->
    __native_foreign_get { obj = obj, member = member }

# Set a member/property on a foreign object
pub let set: { obj: Foreign, member: String, value: Foreign } -> Result {} InteropError = { obj, member, value } ->
    __native_foreign_set { obj = obj, member = member, value = value }

# Check if member exists
pub let has: { obj: Foreign, member: String } -> Result Bool InteropError = { obj, member } ->
    __native_foreign_has { obj = obj, member = member }
```

### Method Invocation

```mezze
# Call a method on a foreign object
pub let call: { obj: Foreign, method: String, args: [Foreign] } -> Result Foreign InteropError = { obj, method, args } ->
    __native_foreign_call { obj = obj, method = method, args = args }

# Call a foreign function directly (not a method)
pub let invoke: { func: Foreign, args: [Foreign] } -> Result Foreign InteropError = { func, args } ->
    __native_foreign_invoke { func = func, args = args }
```

### Array/List Operations

```mezze
# Get array length
pub let length: { arr: Foreign } -> Result Int InteropError = { arr } ->
    __native_foreign_length { arr = arr }

# Get element at index
pub let at: { arr: Foreign, index: Int } -> Result Foreign InteropError = { arr, index } ->
    __native_foreign_at { arr = arr, index = index }
```

## Type Inspection

```mezze
# Check if value is null/None
pub let is_null: { val: Foreign } -> Bool = { val } ->
    __native_foreign_is_null { val = val }

# Check if value is callable (function/method)
pub let is_callable: { val: Foreign } -> Bool = { val } ->
    __native_foreign_is_callable { val = val }

# Check if value has array-like behavior
pub let is_array: { val: Foreign } -> Bool = { val } ->
    __native_foreign_is_array { val = val }

# Check if value has object-like behavior (has members)
pub let is_object: { val: Foreign } -> Bool = { val } ->
    __native_foreign_is_object { val = val }

# Get type name as reported by foreign language
pub let type_name: { val: Foreign } -> String = { val } ->
    __native_foreign_type_name { val = val }
```

## Example Usage

```mezze
use std::interop::foreign

# Working with a Python numpy array
let process_array: { arr: Foreign } -> Result Float InteropError = { arr } ->
    # Get the 'sum' method
    let sum_method = foreign::get { obj = arr, member = "sum" }
    match sum_method is
        Ok { val = method } ->
            # Call it with no arguments
            let result = foreign::invoke { func = method, args = [] }
            match result is
                Ok { val = sum_foreign } ->
                    foreign::to_float { val = sum_foreign }
                Err { error } -> Err { error = error }
        Err { error } -> Err { error = error }
```

## Implementation Notes

All `__native_*` functions are implemented in the Truffle interpreter using GraalVM's InteropLibrary:

- `__native_foreign_from` - Wraps Mezze values as TruffleObject
- `__native_foreign_to_*` - Uses InteropLibrary to convert foreign values
- `__native_foreign_get` - Uses `InteropLibrary.readMember()`
- `__native_foreign_call` - Uses `InteropLibrary.invokeMember()`
- `__native_foreign_invoke` - Uses `InteropLibrary.execute()`

All operations catch exceptions and wrap them in `InteropError`.
