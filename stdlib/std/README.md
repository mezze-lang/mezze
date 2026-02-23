# Mezze Standard Library - Test Coverage Tracker

This directory contains the Mezze standard library modules. Each subdirectory has its own README with detailed function-level test coverage.

## Module Index

| Module | Path | Description |
|--------|------|-------------|
| [Primitives](#primitives) | `std/` | Core types (int, float, string, etc.) |
| [Collections](collections/README.md) | `std/collections/` | List, Array, Map, Set, etc. |
| [Effects](effects/README.md) | `std/effects/` | All effect handlers and concurrent primitives |
| [Codec](codec/README.md) | `std/codec/` | Serialization (Codec, JSON) |
| [Math](math/README.md) | `std/math/` | Mathematical functions |
| [Control](control/README.md) | `std/control/` | Control flow utilities |

---

## Directory Structure

```
std/
├── bool.mz              # Boolean utilities
├── char.mz              # Character utilities
├── float.mz             # Float utilities
├── frac.mz              # Fraction utilities
├── int.mz               # Integer utilities
├── nat.mz               # Natural number utilities
├── option.mz            # Option type utilities
├── result.mz            # Result type utilities
├── string.mz            # String utilities
│
├── collections/         # Collection types
│   ├── array.mz         # Mutable arrays
│   ├── list.mz          # Immutable lists
│   ├── map.mz           # Hash maps
│   ├── set.mz           # Sets
│   └── ...
│
├── effects/             # Effect handlers and concurrent primitives
│   ├── async.mz         # Async effect (LoomAsync, CoopAsync, OSThreadAsync)
│   ├── async/           # Async utilities (stream, scope, channel, etc.)
│   ├── stm.mz           # Software Transactional Memory
│   ├── parallel.mz      # Parallel data processing
│   ├── concurrency.mz   # Low-level concurrency (Loom virtual threads)
│   ├── concurrent/      # Concurrent primitives (atom, channel, mutex, etc.)
│   ├── time.mz          # Time effect
│   ├── filesystem.mz    # File system effect
│   ├── environment.mz   # Environment variables
│   ├── random.mz        # Random number generation
│   ├── generator.mz     # Generator/iterator effect
│   ├── exception.mz     # Exception handling
│   ├── early_return.mz  # Early return effect
│   ├── choice.mz        # Non-deterministic choice
│   ├── log.mz           # Logging effect
│   └── inspect.mz       # Inspection ability
│
├── codec/               # Serialization
│   ├── core.mz          # Generic encoding/decoding
│   ├── json.mz          # JSON serialization
│   └── json/            # JSON utilities
│       └── stream.mz    # Streaming JSON
│
├── math/                # Mathematics
│   ├── core.mz          # Core math functions
│   └── trig.mz          # Trigonometric functions
│
└── control/             # Control flow
    └── identity.mz      # Identity monad
```

---

## Import Paths

```mezze
# Primitives
use std::int::{add, subtract}
use std::string::{concat, split}
use std::option::{map, get_or_else}
use std::result::{map, flat_map}

# Collections
use std::collections::list::{map, filter, fold}
use std::collections::map::{get, insert}

# Effects - Async/Concurrency
use std::effects::async::{Async, run_coop, LoomAsync}
use std::effects::stm::{STM, TVar, atomically}
use std::effects::parallel::{Parallel, JoinFork}
use std::effects::concurrent::atom::{Atom, new_atom}
use std::effects::concurrent::channel::{Channel, send, receive}

# Effects - Other
use std::effects::time::{Time, now, sleep}
use std::effects::filesystem::{FileSystem, read_file}
use std::effects::random::{Random, random_int, SeededRandom}

# Encoding
use std::codec::core::{Encoder, Decoder}
use std::codec::json::{Json, parse, to_string}
```

---

## Primitives (Root Level Modules)

### int.mz - Integer Utilities
Test file: `IntModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `add` | :white_check_mark: | |
| `subtract` | :white_check_mark: | |
| `multiply` | :white_check_mark: | |
| `divide` | :white_check_mark: | |
| `modulo` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `from_string` | :white_check_mark: | |
| `from_nat` | :white_check_mark: | |
| `abs` | :white_check_mark: | |
| `max` | :white_check_mark: | |
| `min` | :white_check_mark: | |
| `sign` | :white_check_mark: | |
| `is_even` | :white_check_mark: | |
| `is_odd` | :white_check_mark: | |
| `pow` | :white_check_mark: | |
| `gcd` | :white_check_mark: | |
| `lcm` | :white_check_mark: | |
| `clamp` | :white_check_mark: | |
| `to_nat` | :white_check_mark: | |

### float.mz - Float Utilities
Test file: `FloatModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `to_string` | :white_check_mark: | |
| `from_int` | :white_check_mark: | |
| `from_nat` | :white_check_mark: | |
| `from_string` | :white_check_mark: | |
| `abs` | :white_check_mark: | |
| `min` | :white_check_mark: | |
| `max` | :white_check_mark: | |
| `is_nan` | :white_check_mark: | |
| `is_infinite` | :white_check_mark: | |
| `is_finite` | :white_check_mark: | |
| `sign` | :white_check_mark: | |
| `clamp` | :white_check_mark: | |
| `to_int` | :white_check_mark: | |

### nat.mz - Natural Number Utilities
Test file: `NatInterpreterTest.kt`, `NatModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `subtract_to_int` | :white_check_mark: | |
| `from_int` | :white_check_mark: | |
| `from_float` | :white_check_mark: | |
| `from_string` | :white_check_mark: | |
| `min` | :white_check_mark: | |
| `max` | :white_check_mark: | |
| `is_zero` | :white_check_mark: | |
| `is_even` | :white_check_mark: | |
| `is_odd` | :white_check_mark: | |
| `to_int` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `clamp` | :white_check_mark: | |

### bool.mz - Boolean Utilities
Test file: `BoolModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `to_string` | :white_check_mark: | |
| `and` | :white_check_mark: | |
| `or` | :white_check_mark: | |
| `not` | :white_check_mark: | |
| `xor` | :white_check_mark: | |
| `implies` | :white_check_mark: | |
| `iff` | :white_check_mark: | |

### char.mz - Character Utilities
Test file: `CharModuleTest.kt`, `CharInterpreterTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `from_int` | :white_check_mark: | |
| `from_string` | :white_check_mark: | |
| `to_int` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `to_upper` | :white_check_mark: | |
| `to_lower` | :white_check_mark: | |
| `is_digit` | :white_check_mark: | |
| `is_letter` | :white_check_mark: | |
| `is_alpha_numeric` | :white_check_mark: | |
| `is_whitespace` | :white_check_mark: | |
| `is_upper` | :white_check_mark: | |
| `is_lower` | :white_check_mark: | |
| `eq` | :white_check_mark: | |
| `compare` | :white_check_mark: | |
| `digit_to_int` | :white_check_mark: | |

### string.mz - String Utilities
Test file: `StringInterpreterTest.kt`, `StringModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `concat` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `length` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `to_upper` | :white_check_mark: | |
| `to_lower` | :white_check_mark: | |
| `trim` | :white_check_mark: | |
| `reverse` | :white_check_mark: | |
| `starts_with` | :white_check_mark: | |
| `ends_with` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `repeat` | :white_check_mark: | |
| `split` | :white_check_mark: | |
| `join` | :white_check_mark: | |
| `substring` | :white_check_mark: | |
| `replace` | :white_check_mark: | |
| `replace_all` | :white_check_mark: | |
| `char_at` | :white_check_mark: | |
| `index_of` | :white_check_mark: | |
| `last_index_of` | :white_check_mark: | |
| `trim_start` | :white_check_mark: | |
| `trim_end` | :white_check_mark: | |
| `lines` | :white_check_mark: | |
| `words` | :white_check_mark: | |
| `chars` | :white_check_mark: | |
| `pad_left` | :white_check_mark: | |
| `pad_right` | :white_check_mark: | |

### frac.mz - Fraction Utilities
Test file: `FracModuleTest.kt`, `FracInterpreterTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `add` | :white_check_mark: | |
| `subtract` | :white_check_mark: | |
| `multiply` | :white_check_mark: | |
| `divide` | :white_check_mark: | |
| `equals` | :white_check_mark: | |
| `less_than` | :white_check_mark: | |
| `greater_than` | :white_check_mark: | |
| `compare` | :white_check_mark: | |
| `to_float` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `make` | :white_check_mark: | |
| `from_int` | :white_check_mark: | |
| `from_nat` | :white_check_mark: | |
| `numerator` | :white_check_mark: | |
| `denominator` | :white_check_mark: | |
| `reduce` | :white_check_mark: | |
| `abs` | :white_check_mark: | |
| `negate` | :white_check_mark: | |

### option.mz - Option Type Utilities
Test file: `OptionModuleTest.kt`, `OptionInterpreterTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `map` | :white_check_mark: | |
| `get_or_else` | :white_check_mark: | |
| `is_some` | :white_check_mark: | |
| `is_none` | :white_check_mark: | |
| `filter` | :white_check_mark: | |
| `flat_map` | :white_check_mark: | |
| `or_else` | :white_check_mark: | |
| `map2` | :white_check_mark: | |
| `if_some` | :white_check_mark: | |
| `if_none` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `flatten` | :white_check_mark: | |
| `to_result` | :white_check_mark: | |
| `to_list` | :white_check_mark: | |
| `zip` | :white_check_mark: | |
| `zip_with` | :white_check_mark: | |

### result.mz - Result Type Utilities
Test file: `ResultModuleTest.kt`, `ResultInterpreterTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `map` | :white_check_mark: | |
| `map_err` | :white_check_mark: | |
| `get_or_else` | :white_check_mark: | |
| `is_ok` | :white_check_mark: | |
| `is_err` | :white_check_mark: | |
| `flat_map` | :white_check_mark: | |
| `or_else` | :white_check_mark: | |
| `to_option` | :white_check_mark: | |
| `from_option` | :white_check_mark: | |
| `map2` | :white_check_mark: | |
| `if_ok` | :white_check_mark: | |
| `if_err` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `flatten` | :white_check_mark: | |
| `to_list` | :white_check_mark: | |
| `zip` | :white_check_mark: | |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification

## Test File Locations

Test files are in the interpreter test directory:

```
interpreter/src/test/kotlin/com/mezze/language/interpreter/
├── stdlib/              # Module-specific tests (IntModuleTest.kt, etc.)
├── concurrency/         # Concurrent primitives (AtomTest.kt, STMEffectTest.kt, etc.)
├── codec/               # JSON codec tests (JsonCodecInterpreterTest.kt, etc.)
├── effects/             # Effect-specific tests
└── *.kt                 # General interpreter tests
```

---

## How to Update

1. When adding tests, update the corresponding README
2. Change :x: to :white_check_mark: when test is added
3. Add notes for any special testing considerations
