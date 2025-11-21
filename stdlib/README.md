# Funk Standard Library (stdlib)

The Funk standard library provides a comprehensive set of modules for common programming tasks, organized into logical categories.

## Organization

```
stdlib/std/
├── int.funk           # Integer utilities
├── float.funk         # Float utilities
├── string.funk        # String operations
├── boolean.funk       # Boolean utilities
├── option.funk        # Optional values
├── result.funk        # Result/error handling
├── nat.funk           # Natural numbers (0, 1, 2, ...)
├── frac.funk          # Fractions/rationals
├── collections/       # Collection types and operations
│   ├── array.funk
│   ├── map.funk
│   └── set.funk
├── math/              # Mathematical functions
│   ├── core.funk
│   ├── trig.funk
│   └── random.funk
└── effects/           # Algebraic effects
    ├── log.funk
    ├── filesystem.funk
    ├── time.funk
    ├── random.funk
    └── environment.funk
```

## Module Reference

### Core Types

**`std::option`** - Optional values
```funk
use std::option::{Some, None, map, flat_map, unwrap_or}

let value = Some { val = 42 }
let result = map { opt = value, f = { x } -> x + 1 }
```

**`std::result`** - Result type for error handling
```funk
use std::result::{Ok, Err, map, map_error, unwrap_or}

let success = Ok { val = 42 }
let failure = Err { err = "Something went wrong" }
```

### Primitive Types

**`std::int`** - Integer utilities
```funk
use std::int

let absolute = int::abs { x = -5 }  # 5
let power = int::pow { base = 2, exp = 10 }  # 1024
let greatest = int::gcd { l = 48, r = 18 }  # 6
let str = int::to_string { val = 42 }  # "42"
```

**`std::float`** - Floating-point utilities
```funk
use std::float

let rounded = float::round { x = 3.7 }  # 4.0
let floored = float::floor { x = 3.7 }  # 3.0
let str = float::to_string { val = 3.14 }  # "3.14"
```

**`std::string`** - String operations
```funk
use std::string

let upper = string::to_upper { s = "hello" }  # "HELLO"
let length = string::length { s = "hello" }  # 5
let contains = string::contains { s = "hello", sub = "ell" }  # true
```

**`std::boolean`** - Boolean utilities
```funk
use std::boolean

let str = boolean::to_string { val = true }  # "true"
```

### Numeric Types

**`std::nat`** - Natural numbers (0, 1, 2, ...)
```funk
use std::nat

let n = nat::from_int { x = 5 }  # Some (Nat 5)
let invalid = nat::from_int { x = -1 }  # None
```

**`std::frac`** - Fractions/rationals
```funk
use std::frac

let half = frac::create { num = 1, denom = 2 }
let sum = frac::add { l = half, r = half }  # 1/1
```

### Generators

**`std::gen`** - Thunk-based lazy generators (Roc-style CIS pattern)
```funk
use std::gen::{fib, take, from, nth, drop}

# Fibonacci sequence
let fibs = take { gen = fib {}, n = 10 }  # [0, 1, 1, 2, 3, 5, 8, 13, 21, 34]

# Get nth Fibonacci number
let fib_10 = nth { gen = fib {}, n = 10 }  # 55

# Infinite integer sequence
let nums = take { gen = from { start = 1 }, n = 5 }  # [1, 2, 3, 4, 5]

# Skip first 5, take next 5
let skipped = take { gen = drop { gen = from { start = 1 }, n = 5 }, n = 5 }  # [6, 7, 8, 9, 10]
```

**`std::effects::generator`** - Continuation-based generators (effect system)
```funk
use std::effects::generator::{Generator, Yieldable, take}

# Define a generator using effects
let count_up = { start: Int } -> perform Yieldable in do
    Generator.yield { value = start }
    count_up { start = start + 1 }

# Use take to get values
let main = {} -> do
    let g = count_up { start = 1 }
    let result = take { gen = g, n = 5 }
    result.values  # [1, 2, 3, 4, 5]
```

### Collections (`std::collections`)

**`std::collections::array`** - Array/list operations
```funk
use std::collections::array

let numbers = [1, 2, 3, 4, 5]
let doubled = array::map { xs = numbers, f = { x } -> x * 2 }
let evens = array::filter { xs = numbers, p = { x } -> x % 2 == 0 }
let sum = array::fold { xs = numbers, init = 0, f = { acc, x } -> acc + x }
```

**`std::collections::map`** - Map/dictionary operations
```funk
use std::collections::map

let m = map::empty { }
let m2 = map::insert { m = m, key = "foo", value = 42 }
let value = map::get { m = m2, key = "foo" }  # Some 42
```

**`std::collections::set`** - Set operations
```funk
use std::collections::set

let s = set::from_array { xs = [1, 2, 3, 2, 1] }
let has = set::contains { s = s, x = 2 }  # true
let size = set::size { s = s }  # 3
```

### Math (`std::math`)

**`std::math::core`** - Core mathematical functions
```funk
use std::math::core

let root = core::sqrt { x = 16.0 }  # 4.0
let power = core::pow { base = 2.0, exp = 3.0 }  # 8.0
let log = core::ln { x = 2.718281828 }  # ~1.0
```

**`std::math::trig`** - Trigonometric functions
```funk
use std::math::trig

let sine = trig::sin { value = trig::pi / 2.0 }  # 1.0
let cosine = trig::cos { value = 0.0 }  # 1.0
let radians = trig::deg_to_rad { degrees = 180.0 }  # pi
```

**`std::math::random`** - Mathematical random utilities
```funk
use std::math::random

# Used with Random effect - see effects below
```

### Effects (`std::effects`)

Effects provide algebraic effects for side-effectful operations.

**`std::effects::log`** - Logging
```funk
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
```funk
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
```funk
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
```funk
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
```funk
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
```funk
use std::int

int::to_string { val = 42 }
```

### Selective import
```funk
use std::collections::array::{map, filter, fold}

map { xs = [1, 2, 3], f = { x } -> x * 2 }
```

### Aliased import
```funk
use std::collections::array as arr

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
- `std::gen` - Thunk-based lazy generators

### Collections
- `std::collections::array` - Arrays/lists
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

```funk
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

The stdlib is automatically included when you build the Funk compiler:

- **JAR**: Resources are packaged in `cli/build/libs/funk.jar`
- **Native**: Resources are embedded in `cli/build/native/nativeCompile/funk`

You don't need to distribute stdlib files separately - they're embedded in the binary!
