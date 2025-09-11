# Funk Effect System - Complete Guide

The Funk programming language features a powerful, direct-style effect system with native function integration, parameterized handlers, and built-in async support.

## 🔥 Quick Start

```funk
# 1. Declare an effect
effect Log {
  print : {msg: String} -> {}
}

# 2. Implement the effect
impl Log for Console {
  print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
}

# 3. Use the effect
handle Log.print {msg = "Hello, Funk!"} with Console
```

**Output**: `[FUNK LOG] Hello, Funk!`

## 🏗️ Core Concepts

### 1. **Effect Declarations**
Define what operations an effect provides:

```funk
effect Random {
  nextInt : {min: Int, max: Int} -> Int
  nextDouble : {} -> Float
}

effect FileSystem {
  readFile : {path: String} -> String
  writeFile : {path: String, content: String} -> {}
}
```

### 2. **Implementation Declarations**
Provide concrete implementations for effects:

```funk
# Simple implementation
impl Random for MathRandom {
  nextInt : {min: Int, max: Int} -> Int = {min, max} -> 
    native_random_int {min = min, max = max}
  nextDouble : {} -> Float = {} -> 
    native_random_double {}
}

# Parameterized implementation
impl FileLogger {path: String} for FileSystem {
  writeFile : {path: String, content: String} -> {} = {path, content} ->
    native_print {msg = "[FILE:" + path + "] " + content}
}
```

### 3. **Effect Handling**
Execute effectful code with specific handlers:

```funk
# Single handler
handle Random.nextInt {min = 1, max = 100} with MathRandom

# Multiple handlers
handle someFunction {} with Console, MathRandom, FileSystem
```

## 🛠️ Built-in Native Functions

Funk provides native integration with Kotlin functions:

| Function | Description | Example |
|----------|-------------|---------|
| `native_print` | Kotlin `println` | `native_print {msg = "Hello"}` |
| `native_debug` | Debug logging | `native_debug {msg = "Debug info"}` |
| `native_random_int` | Random integer | `native_random_int {min = 1, max = 10}` |
| `native_random_double` | Random double | `native_random_double {}` |

### Pre-built Handler Implementations

```funk
# Available out of the box:
# - NativeConsole: print, debug operations
# - NativeRandom: nextInt, nextDouble operations
```

## 📚 Complete Examples

### Basic Logging
```funk
effect Log {
  info : {msg: String} -> {}
  error : {msg: String} -> {}
}

impl Log for Console {
  info : {msg: String} -> {} = {msg} -> native_print {msg = "[INFO] " + msg}
  error : {msg: String} -> {} = {msg} -> native_print {msg = "[ERROR] " + msg}
}

let logExample = {} ->
  do
    Log.info {msg = "Application started"}
    Log.error {msg = "Something went wrong"}

handle logExample {} with Console
```

### Random Number Generation
```funk
effect Random {
  nextInt : {min: Int, max: Int} -> Int
}

impl Random for MathRandom {
  nextInt : {min: Int, max: Int} -> Int = {min, max} -> 
    native_random_int {min = min, max = max}
}

let diceRoll = {} -> Random.nextInt {min = 1, max = 6}
let result = handle diceRoll {} with MathRandom

native_print {msg = "You rolled: " + intToString {val = result}}
```

### Multiple Effects
```funk
effect Log { print : {msg: String} -> {} }
effect Random { nextInt : {min: Int, max: Int} -> Int }

impl Log for Console {
  print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
}

impl Random for MathRandom {
  nextInt : {min: Int, max: Int} -> Int = {min, max} -> 
    native_random_int {min = min, max = max}
}

let gameLogic = {} ->
  do
    Log.print {msg = "Starting game..."}
    let score = Random.nextInt {min = 100, max = 1000}
    Log.print {msg = "Your score: " + intToString {val = score}}
    score

handle gameLogic {} with Console, MathRandom
```

## 🎯 Advanced Features

### 1. **Parameterized Implementations** *(80% Complete)*

```funk
effect Logging {
  log : {msg: String} -> {}
}

# Handler with parameters
impl FileLogger {path: String} for Logging {
  log : {msg: String} -> {} = {msg} -> 
    native_print {msg = "[FILE:" + path + "] " + msg}
}

# TODO: Handler instantiation syntax
# handle expr with FileLogger {path = "/tmp/app.log"}
```

### 2. **Do Blocks with Effects**

```funk
let complexOperation = {} ->
  do
    Log.info {msg = "Starting operation"}
    let random1 = Random.nextInt {min = 1, max = 10}
    let random2 = Random.nextInt {min = 1, max = 10}
    Log.info {msg = "Generated numbers: " + intToString {val = random1} + ", " + intToString {val = random2}}
    random1 + random2

handle complexOperation {} with Console, MathRandom
```

### 3. **Effect Composition**

```funk
# Effects compose naturally
let chainedEffects = {} ->
  do
    let x = Random.nextInt {min = 1, max = 5}
    Log.print {msg = "First: " + intToString {val = x}}
    let y = Random.nextInt {min = x, max = x + 5}
    Log.print {msg = "Second: " + intToString {val = y}}
    y

handle chainedEffects {} with Console, MathRandom
```

## 🔧 Implementation Details

### Architecture
- **Parser**: ANTLR4-based with effect-specific grammar rules
- **AST**: Kotlinx Serialization with effect declarations and expressions  
- **Type System**: Effect rows and bidirectional type inference
- **Interpreter**: Direct execution with handler lookup and native function integration
- **Runtime**: Truffle/GraalVM-ready with Project Loom async support (planned)

### Effect Call Resolution
1. **Parse**: `Effect.operation {args}` → `EffectCallExpression`
2. **Type Check**: Verify effect is in scope and operation exists
3. **Runtime**: Look up active handler from `HandleExpression`
4. **Execute**: Call handler operation with arguments

### Handler Storage
- **Direct handlers**: Stored as `FunkRecord` with operation functions
- **Parameterized handlers**: Stored as `HandlerFactory` instances
- **Handler mapping**: `__impl_mappings` tracks which handler implements which effect

## 🧪 Testing

Run the examples:

```bash
# Basic effects
funk-dev exec examples/effects/simple_working_example.funk

# Random numbers  
funk-dev exec examples/effects/random_example.funk

# Parameterized parsing (works)
funk-dev exec examples/effects/parsing_test.funk
```

## 🚀 Current Status

### ✅ Fully Working
- ✅ Effect declarations and implementations
- ✅ Non-parameterized handlers  
- ✅ Native function integration
- ✅ Multiple effect handling
- ✅ Do blocks with effects
- ✅ Handler-to-effect mapping
- ✅ Complete parsing and execution pipeline

### 🔄 In Progress  
- 🔄 Parameterized handler instantiation syntax
- 🔄 Async state machine execution
- 🔄 Advanced handler parameter passing

### 📋 Planned
- 📋 Effect rows in function types
- 📋 Algebraic effect operations with resume
- 📋 Concurrent effect execution
- 📋 Effect composition operators

## 🎯 Key Benefits

1. **Direct Style**: No monads or callback hell
2. **Type Safe**: Effect tracking in the type system
3. **Composable**: Effects combine naturally
4. **Native Integration**: Seamless Kotlin interop
5. **Performance**: Truffle/GraalVM compilation target
6. **Async Ready**: Built for Project Loom virtual threads

## 📖 Language Integration

The effect system is a first-class feature of Funk, designed alongside:
- **Records**: `{name: String, age: Int}`
- **Variants**: `| Success {value: T} | Error {msg: String} |`  
- **Pattern Matching**: `match result | Success {value} -> value | Error {msg} -> 0`
- **Functions**: `let add = {x, y} -> x + y`
- **Pipes**: `value |> transform |> process`

**The Funk effect system provides the foundation for modern, safe, and performant effectful programming.** 🚀
