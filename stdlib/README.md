# Mezze Standard Library (stdlib)

The Mezze standard library provides a comprehensive set of modules for common programming tasks, organized into logical categories.

## Organization

```
stdlib/std/
├── int.mz           # Integer utilities
├── float.mz         # Float utilities
├── string.mz        # String operations
├── boolean.mz       # Boolean utilities
├── option.mz        # Optional values
├── result.mz        # Result/error handling
├── nat.mz           # Natural numbers (0, 1, 2, ...)
├── frac.mz          # Fractions/rationals
├── collections/       # Collection types and operations
│   ├── seq.mz
│   ├── map.mz
│   └── set.mz
├── math/              # Mathematical functions
│   ├── core.mz
│   ├── trig.mz
│   └── random.mz
└── effects/           # Algebraic effects
    ├── log.mz
    ├── filesystem.mz
    ├── time.mz
    ├── random.mz
    └── environment.mz
```

## Module Reference

### Core Types

**`std::option`** - Optional values
```mezze
use std::option::{Some, None, map, flat_map, unwrap_or}

let value = Some { val = 42 }
let result = map { opt = value, f = { x } -> x + 1 }
```

**`std::result`** - Result type for error handling
```mezze
use std::result::{Ok, Err, map, map_error, unwrap_or}

let success = Ok { val = 42 }
let failure = Err { err = "Something went wrong" }
```

### Primitive Types

**`std::int`** - Integer utilities
```mezze
use std::int

let absolute = int::abs { x = -5 }  # 5
let power = int::pow { base = 2, exp = 10 }  # 1024
let greatest = int::gcd { l = 48, r = 18 }  # 6
let str = int::to_string { val = 42 }  # "42"
```

**`std::float`** - Floating-point utilities
```mezze
use std::float

let rounded = float::round { x = 3.7 }  # 4.0
let floored = float::floor { x = 3.7 }  # 3.0
let str = float::to_string { val = 3.14 }  # "3.14"
```

**`std::string`** - String operations
```mezze
use std::string

let upper = string::to_upper { s = "hello" }  # "HELLO"
let length = string::length { s = "hello" }  # 5
let contains = string::contains { s = "hello", sub = "ell" }  # true
```

**`std::boolean`** - Boolean utilities
```mezze
use std::boolean

let str = boolean::to_string { val = true }  # "true"
```

### Numeric Types

**`std::nat`** - Natural numbers (0, 1, 2, ...)
```mezze
use std::nat

let n = nat::from_int { x = 5 }  # Some (Nat 5)
let invalid = nat::from_int { x = -1 }  # None
```

**`std::frac`** - Fractions/rationals
```mezze
use std::frac

let half = frac::create { num = 1, denom = 2 }
let sum = frac::add { l = half, r = half }  # 1/1
```

### Generators

**`std::effects::generator`** - Continuation-based generators with associated types
```mezze
use std::effects::generator::{Generator, Yieldable, next, to_list}

# Define a generator using the Generator effect
let fib_gen = { n: Int } -> perform Yieldable in do
    let value = if n == 0 then 0
                else if n == 1 then 1
                else n  # Simplified for example
    Generator.yield { value = value }

# Use next to extract values
let main = {} -> do
    let g = fib_gen { n = 5 }
    let result = next { gen = g }
    match result.value is
        Some { val } -> val
        None -> 0

# Or use to_list to consume the generator
let main2 = {} -> do
    let g = fib_gen { n = 7 }
    to_list { gen = g }  # [13]
```

**Key Features:**
- Built on algebraic effects and delimited continuations
- Type-safe with associated types: `effect Generator having 'item`
- Composable with other effects (Log, FileSystem, etc.)
- Supports map, filter, zip operations for generator transformations
```

### Collections (`std::collections`)

**`std::collections::seq`** - Sequence/list operations
```mezze
use std::collections::seq

let numbers = [1, 2, 3, 4, 5]
let doubled = seq::map { xs = numbers, f = { x } -> x * 2 }
let evens = seq::filter { xs = numbers, p = { x } -> x % 2 == 0 }
let sum = seq::fold { xs = numbers, init = 0, f = { acc, x } -> acc + x }
```

**`std::collections::map`** - Map/dictionary operations
```mezze
use std::collections::map

let m = map::empty { }
let m2 = map::insert { m = m, key = "foo", value = 42 }
let value = map::get { m = m2, key = "foo" }  # Some 42
```

**`std::collections::set`** - Set operations
```mezze
use std::collections::set

let s = set::from_seq { xs = [1, 2, 3, 2, 1] }
let has = set::contains { s = s, x = 2 }  # true
let size = set::size { s = s }  # 3
```

### Math (`std::math`)

**`std::math::core`** - Core mathematical functions
```mezze
use std::math::core

let root = core::sqrt { x = 16.0 }  # 4.0
let power = core::pow { base = 2.0, exp = 3.0 }  # 8.0
let log = core::ln { x = 2.718281828 }  # ~1.0
```

**`std::math::trig`** - Trigonometric functions
```mezze
use std::math::trig

let sine = trig::sin { value = trig::pi / 2.0 }  # 1.0
let cosine = trig::cos { value = 0.0 }  # 1.0
let radians = trig::deg_to_rad { degrees = 180.0 }  # pi
```

**`std::math::random`** - Mathematical random utilities
```mezze
use std::math::random

# Used with Random effect - see effects below
```

### Effects (`std::effects`)

Effects provide algebraic effects for side-effectful operations.

**`std::effects::log`** - Logging
```mezze
use std::effects::log::{Log, Console}

let program = {} -> do
    Log.info { msg = "Starting..." }
    Log.println { msg = "Hello, World!" }
    Log.debug { msg = "Debug info" }

# Run with handler
let main = {} ->
    perform Console in program {}
```

**`std::effects::filesystem`** - File system operations
```mezze
use std::effects::filesystem::{FileSystem, LocalDisk, MemoryFS}

let program = {} -> do
    FileSystem.write_file { path = "test.txt", content = "Hello!" }
    let content = FileSystem.read_file { path = "test.txt" }
    content

# Use LocalDisk for real filesystem
let main = {} ->
    perform LocalDisk in program {}

# Or MemoryFS for testing
let test = {} ->
    perform MemoryFS in program {}
```

**`std::effects::time`** - Time operations
```mezze
use std::effects::time::{Time, SystemTime, MockTime}

let program = {} -> do
    let now = Time.now { }
    let formatted = Time.format_timestamp { timestamp = now }
    formatted

# Use SystemTime for real time
let main = {} ->
    perform SystemTime in program {}

# Or MockTime for testing
let test = {} ->
    perform MockTime { current_time = 1000 } in program {}
```

**`std::effects::random`** - Random number generation
```mezze
use std::effects::random::{Random, SystemRandom, SeededRandom}

let program = {} -> do
    let value = Random.random_int { min = 1, max = 100 }
    let coin = Random.random_bool { }
    value

# Use SystemRandom for crypto random
let main = {} ->
    perform SystemRandom in program {}

# Or SeededRandom for reproducible testing
let test = {} ->
    perform SeededRandom { seed = 42 } in program {}
```

**`std::effects::environment`** - Environment and system info
```mezze
use std::effects::environment::{Environment, SystemEnvironment}

let program = {} -> do
    let home = Environment.get_env { name = "HOME" }
    let cwd = Environment.current_dir { }
    let os = Environment.os_name { }
    { home = home, cwd = cwd, os = os }

let main = {} ->
    perform SystemEnvironment in program {}
```

## Import Styles

### Full module import
```mezze
use std::int

int::to_string { val = 42 }
```

### Selective import
```mezze
use std::collections::seq::{map, filter, fold}

map { xs = [1, 2, 3], f = { x } -> x * 2 }
```

### Aliased import
```mezze
use std::collections::seq as arr

arr::map { xs = [1, 2, 3], f = { x } -> x * 2 }
```

## All Available Modules

### Direct under `std::`
- `std::int` - Integer utilities
- `std::float` - Float utilities
- `std::string` - String operations
- `std::boolean` - Boolean utilities
- `std::option` - Optional values
- `std::result` - Result/error handling
- `std::nat` - Natural numbers
- `std::frac` - Fractions

### Collections
- `std::collections::seq` - Sequences/lists
- `std::collections::map` - Maps/dictionaries
- `std::collections::set` - Sets

### Math
- `std::math::core` - Core math (sqrt, pow, ln, exp, etc.)
- `std::math::trig` - Trigonometry (sin, cos, tan, etc.)
- `std::math::random` - Math random utilities

### Effects
- `std::effects::log` - Logging (Console, QuietConsole)
- `std::effects::filesystem` - Files (LocalDisk, MemoryFS)
- `std::effects::time` - Time (SystemTime, MockTime)
- `std::effects::random` - Random (SystemRandom, SeededRandom)
- `std::effects::environment` - Environment (SystemEnvironment, MockEnvironment)
- `std::effects::generator` - Continuation-based generators (Yieldable)
- `std::effects::continuation` - Delimited continuations (Delimited)

## Testing Patterns

Most effect modules provide both production and testing implementations:

- **Log**: `Console` (real), `QuietConsole` (testing)
- **FileSystem**: `LocalDisk` (real), `MemoryFS` (testing)
- **Time**: `SystemTime` (real), `MockTime` (testing)
- **Random**: `SystemRandom` (crypto), `SeededRandom` (reproducible)
- **Environment**: `SystemEnvironment` (real), `MockEnvironment` (testing)

This allows you to write testable code by swapping implementations:

```mezze
# Production
let main = {} ->
    perform LocalDisk in
    perform SystemTime in
    perform Console in
        my_program {}

# Testing
let test = {} ->
    perform MemoryFS in
    perform MockTime { current_time = 0 } in
    perform QuietConsole in
        my_program {}
```

## Building and Packaging

The stdlib is automatically included when you build the Mezze compiler:

- **JAR**: Resources are packaged in `cli/build/libs/mezze.jar`
- **Native**: Resources are embedded in `cli/build/native/nativeCompile/mezze`

You don't need to distribute stdlib files separately - they're embedded in the binary!
