# Stdlib Analysis and Enhancement Plan

## Current Test Coverage Analysis

### Well-Tested Modules (Interpreter + TypeChecker)

| Module | Test File | Coverage Level | Notes |
|--------|-----------|----------------|-------|
| `std::int` | `IntInterpreterTest.kt` | **High** | 23 tests, all functions covered |
| `std::nat` | `NatInterpreterTest.kt` | **High** | 18 tests, all functions covered |
| `std::float` | `FloatInterpreterTest.kt` | **High** | 21 tests, all functions covered |
| `std::string` | `StringInterpreterTest.kt` | **High** | 35 tests, all functions covered |
| `std::option` | `OptionInterpreterTest.kt` | **High** | 23 tests, all functions covered |
| `std::result` | `ResultInterpreterTest.kt` | **High** | 24 tests, all functions covered |
| `std::collections::array` | `ArrayModuleInterpreterTest.kt` | **High** | 45 tests |
| `std::collections::list` | `ListInterpreterTest.kt` | **High** | 50+ tests |
| `std::frac` | `FracInterpreterTest.kt` | **High** | 30+ tests, operators well covered |
| `std::char` | `CharInterpreterTest.kt` | **High** | ~20 tests |

### Partially Tested Modules (TypeChecker only, no execution tests)

| Module | Test File | Coverage Level | Missing |
|--------|-----------|----------------|---------|
| `std::math::core` | `MathCoreModuleTest.kt` | **Medium** | Only typechecking, no execution verification |
| `std::math::trig` | `MathTrigModuleTest.kt` | **Medium** | Only typechecking, no execution verification |
| `std::bool` | `BooleanModuleTest.kt` | **Medium** | Only typechecking |

### Untested or Minimally Tested Modules

| Module | Functions | Test Status |
|--------|-----------|-------------|
| `std::math::random` | 10+ pure distribution functions | **No tests** |
| `std::json` | parse, serialize, field transforms | **No unit tests** (JsonCodecInterpreterTest exists) |
| `std::json::stream` | streaming encode/decode | **No tests** |
| `std::codec` | Encode/Decode abilities | **Partial** (CodecGeneratorTest exists) |
| `std::effects::async` | Promise, async/await | **No unit tests** |
| `std::effects::choice` | Nondeterministic choice | `ChoiceEffectTest.kt` exists |
| `std::effects::environment` | Env vars, system info | **No tests** (mock available) |
| `std::effects::exception` | Type-safe exceptions | `ExceptionEffectTest.kt` exists |
| `std::effects::inspect` | Value inspection | `InspectAbilityTest.kt` exists |
| `std::effects::early_return` | Early return control flow | **No tests** |
| `std::effects::log` | Logging | **No tests** (mock available) |
| `std::effects::time` | Time/date operations | **No tests** (mock available) |
| `std::effects::generator` | Generator/iterator pattern | `GeneratorEffectTest.kt` exists |
| `std::effects::concurrency` | Virtual threads | Extensive concurrency tests exist |
| `std::effects::filesystem` | File I/O | **No tests** (MemoryFS mock available) |
| `std::effects::random` | Random number generation | **No tests** |

---

## Modules Needing More Tests

### Priority 1: Add Execution Tests for Math Modules

**`std::math::core`** - Currently only has type-checking tests

Functions to test with execution:
- `pow`, `sqrt`, `ln`, `log10`, `exp` - verify actual values
- `floor`, `ceil`, `round` - edge cases
- `is_nan`, `is_infinite`, `is_finite` - boundary conditions
- `sign`, `sign_int` - negative, zero, positive

**`std::math::trig`** - Currently only has type-checking tests

Functions to test with execution:
- `sin`, `cos`, `tan` - known values (0, π/4, π/2, π)
- `asin`, `acos`, `atan`, `atan2` - inverse function properties
- `sinh`, `cosh`, `tanh` - hyperbolic functions
- `degrees_to_radians`, `radians_to_degrees` - round-trip

**`std::math::random`** - No tests at all

Pure functions (no effects):
- `box_muller` - Box-Muller transform
- `exponential_inverse` - Inverse transform
- `uniform_transform` - Linear interpolation
- `normal_pdf`, `normal_cdf_approx` - Probability density
- `exponential_pdf`, `exponential_cdf` - Exponential distribution

### Priority 2: Add Tests for Bool Module

**`std::bool`** - Only type-checking tests

Functions:
- `to_string` - "True" / "False"
- `and`, `or`, `not` - basic logic
- `xor`, `implies`, `iff` - advanced logic

### Priority 3: Effects with Mock Implementations (Easy to Test)

**`std::effects::time`** - Has MockTime
- `now`, `current_timestamp`, `current_date`
- `add_millis`, `add_seconds`, `diff_millis`
- `is_leap_year`, `days_in_month`
- `format_iso8601`, `format_date`

**`std::effects::filesystem`** - Has MemoryFS
- `read_file`, `write_file`, `delete_file`
- `exists`, `is_file`, `is_directory`
- `list_directory`, `create_directory`
- `path_join`, `file_name`, `parent`, `extension`

**`std::effects::environment`** - Has MockEnvironment
- `get_env`, `set_env`
- `current_dir`, `home_dir`
- `os_info`

**`std::effects::log`** - Has QuietConsole
- `info`, `warn`, `error`, `debug`, `trace`
- `print`, `println`

---

## Stdlib Enhancement Plan: What's Missing

### Tier 1: Essential Missing Functions

#### 1. **Text Processing (`std::text` or enhance `std::string`)**
```
# Regex support
regex_match: {s: String, pattern: String} -> Bool
regex_find: {s: String, pattern: String} -> Option String
regex_find_all: {s: String, pattern: String} -> [String]
regex_replace: {s: String, pattern: String, replacement: String} -> String

# Parsing utilities
parse_int: {s: String} -> Option Int  # (already exists as int::from_string)
parse_float: {s: String} -> Option Float
parse_bool: {s: String} -> Option Bool

# Unicode normalization
normalize_nfc: {s: String} -> String
normalize_nfd: {s: String} -> String
```

#### 2. **Ordering Module (`std::ord` or `std::ordering`)**
```
# Ordering utilities
compare: {a: 'a, b: 'a} -> Ordering having Ord 'a
min: {a: 'a, b: 'a} -> 'a having Ord 'a
max: {a: 'a, b: 'a} -> 'a having Ord 'a
clamp: {value: 'a, min: 'a, max: 'a} -> 'a having Ord 'a
between: {value: 'a, min: 'a, max: 'a} -> Bool having Ord 'a
```

#### 3. **Tuple Module (`std::tuple`)**
```
# Pair operations
type Pair 'a 'b = { fst: 'a, snd: 'b }

pair: {fst: 'a, snd: 'b} -> Pair 'a 'b
fst: {p: Pair 'a 'b} -> 'a
snd: {p: Pair 'a 'b} -> 'b
swap: {p: Pair 'a 'b} -> Pair 'b 'a
map_fst: {p: Pair 'a 'b, f: {a: 'a} -> 'c} -> Pair 'c 'b
map_snd: {p: Pair 'a 'b, f: {b: 'b} -> 'c} -> Pair 'a 'c
bimap: {p: Pair 'a 'b, f: {a: 'a} -> 'c, g: {b: 'b} -> 'd} -> Pair 'c 'd

# Triple operations
type Triple 'a 'b 'c = { fst: 'a, snd: 'b, thd: 'c }
```

#### 4. **Either Type (`std::either`)**
```
type Either 'a 'b = | Left { val: 'a } | Right { val: 'b } |

is_left: {e: Either 'a 'b} -> Bool
is_right: {e: Either 'a 'b} -> Bool
from_left: {e: Either 'a 'b, def: 'a} -> 'a
from_right: {e: Either 'a 'b, def: 'b} -> 'b
map_left: {e: Either 'a 'b, f: {val: 'a} -> 'c} -> Either 'c 'b
map_right: {e: Either 'a 'b, f: {val: 'b} -> 'c} -> Either 'a 'c
bimap: {e: Either 'a 'b, f: {l: 'a} -> 'c, g: {r: 'b} -> 'd} -> Either 'c 'd
either: {e: Either 'a 'b, left: {val: 'a} -> 'c, right: {val: 'b} -> 'c} -> 'c
partition_eithers: {xs: [Either 'a 'b]} -> {lefts: ['a], rights: ['b]}
```

### Tier 2: Data Processing

#### 5. **Iterator/Sequence Module (`std::iter` or `std::seq`)**
```
# Lazy sequence operations
type Seq 'a  # Lazy sequence

from_array: {xs: ['a]} -> Seq 'a
from_list: {xs: List 'a} -> Seq 'a
from_range: {start: Int, end: Int} -> Seq Int
iterate: {seed: 'a, f: {x: 'a} -> 'a} -> Seq 'a  # Infinite sequence
repeat: {x: 'a} -> Seq 'a  # Infinite
cycle: {xs: ['a]} -> Seq 'a  # Infinite

# Transformations (lazy)
map: {seq: Seq 'a, f: {x: 'a} -> 'b} -> Seq 'b
filter: {seq: Seq 'a, p: {x: 'a} -> Bool} -> Seq 'a
take: {seq: Seq 'a, n: Int} -> Seq 'a
drop: {seq: Seq 'a, n: Int} -> Seq 'a
take_while: {seq: Seq 'a, p: {x: 'a} -> Bool} -> Seq 'a
drop_while: {seq: Seq 'a, p: {x: 'a} -> Bool} -> Seq 'a
flat_map: {seq: Seq 'a, f: {x: 'a} -> Seq 'b} -> Seq 'b
zip: {a: Seq 'a, b: Seq 'b} -> Seq {fst: 'a, snd: 'b}
enumerate: {seq: Seq 'a} -> Seq {index: Int, value: 'a}

# Consumers (strict)
to_array: {seq: Seq 'a} -> ['a]
to_list: {seq: Seq 'a} -> List 'a
fold: {seq: Seq 'a, init: 'b, f: {acc: 'b, x: 'a} -> 'b} -> 'b
for_each: {seq: Seq 'a, f: {x: 'a} -> {}} -> {}
count: {seq: Seq 'a} -> Int
sum: {seq: Seq Int} -> Int  # (or polymorphic with Num)
```

#### 6. **Validation Module (`std::validation`)**
```
# Validation type (accumulates errors unlike Result)
type Validation 'e 'a = | Valid { val: 'a } | Invalid { errors: ['e] } |

is_valid: {v: Validation 'e 'a} -> Bool
is_invalid: {v: Validation 'e 'a} -> Bool
from_result: {result: Result 'a 'e} -> Validation 'e 'a
to_result: {v: Validation 'e 'a} -> Result 'a ['e]
map: {v: Validation 'e 'a, f: {val: 'a} -> 'b} -> Validation 'e 'b
map_errors: {v: Validation 'e 'a, f: {e: 'e} -> 'f} -> Validation 'f 'a

# Applicative combination (accumulates all errors)
ap: {vf: Validation 'e ({a: 'a} -> 'b), va: Validation 'e 'a} -> Validation 'e 'b
combine2: {v1: Validation 'e 'a, v2: Validation 'e 'b, f: {a: 'a, b: 'b} -> 'c} -> Validation 'e 'c
combine3: ...
sequence: {vs: [Validation 'e 'a]} -> Validation 'e ['a]
```

### Tier 3: System Integration

#### 7. **HTTP Client (`std::http`)**
```
type HttpMethod = | GET | POST | PUT | DELETE | PATCH | HEAD | OPTIONS |
type HttpHeader = { name: String, value: String }
type HttpRequest = {
    url: String,
    method: HttpMethod,
    headers: [HttpHeader],
    body: Option Bytes
}
type HttpResponse = {
    status: Int,
    headers: [HttpHeader],
    body: Bytes
}

effect Http {
    request: {req: HttpRequest} -> Result HttpResponse HttpError
    get: {url: String} -> Result HttpResponse HttpError
    post: {url: String, body: Bytes} -> Result HttpResponse HttpError
}
```

#### 8. **UUID Module (`std::uuid`)**
```
type UUID = { bytes: Bytes }  # Or newtype wrapping String

v4_random: {} -> UUID having Random
v5_name: {namespace: UUID, name: String} -> UUID
parse: {s: String} -> Option UUID
to_string: {uuid: UUID} -> String
nil: UUID  # 00000000-0000-0000-0000-000000000000
eq: {a: UUID, b: UUID} -> Bool
```

#### 9. **Base64/Hex Encoding (`std::encoding`)**
```
# Base64
base64_encode: {bytes: Bytes} -> String
base64_decode: {s: String} -> Result Bytes EncodingError
base64_url_encode: {bytes: Bytes} -> String
base64_url_decode: {s: String} -> Result Bytes EncodingError

# Hex
hex_encode: {bytes: Bytes} -> String
hex_decode: {s: String} -> Result Bytes EncodingError
```

#### 10. **Hashing (`std::hash` or `std::crypto`)**
```
# Cryptographic hashes
sha256: {bytes: Bytes} -> Bytes
sha512: {bytes: Bytes} -> Bytes
blake3: {bytes: Bytes} -> Bytes

# Non-cryptographic (fast)
hash: {x: 'a} -> Int having Hash 'a
fnv1a: {bytes: Bytes} -> Int
xxhash: {bytes: Bytes} -> Int

# HMAC
hmac_sha256: {key: Bytes, message: Bytes} -> Bytes
```

### Tier 4: Specialized

#### 11. **Decimal/BigDecimal (`std::decimal`)**
```
type Decimal  # Arbitrary precision decimal

from_string: {s: String} -> Option Decimal
from_int: {n: Int} -> Decimal
from_frac: {f: Frac} -> Decimal
to_string: {d: Decimal, precision: Int} -> String

add: {l: Decimal, r: Decimal} -> Decimal
subtract: {l: Decimal, r: Decimal} -> Decimal
multiply: {l: Decimal, r: Decimal} -> Decimal
divide: {l: Decimal, r: Decimal, scale: Int} -> Decimal

round: {d: Decimal, scale: Int} -> Decimal
floor: {d: Decimal, scale: Int} -> Decimal
ceil: {d: Decimal, scale: Int} -> Decimal
```

#### 12. **Duration (`std::duration`)**
```
type Duration  # Time duration

from_millis: {ms: Int} -> Duration
from_seconds: {s: Int} -> Duration
from_minutes: {m: Int} -> Duration
from_hours: {h: Int} -> Duration
from_days: {d: Int} -> Duration

to_millis: {d: Duration} -> Int
to_seconds: {d: Duration} -> Int

add: {l: Duration, r: Duration} -> Duration
subtract: {l: Duration, r: Duration} -> Duration
multiply: {d: Duration, n: Int} -> Duration

format: {d: Duration} -> String  # "1h 30m 45s"
```

#### 13. **State Effect (`std::effects::state`)**
```
effect State having 's {
    get: {} -> 's
    put: {state: 's} -> {}
    modify: {f: {s: 's} -> 's} -> {}
}

impl State for WithState  # Run with initial state

run_state: {init: 's, f: {} -> 'a having State 's} -> {result: 'a, state: 's}
eval_state: {init: 's, f: {} -> 'a having State 's} -> 'a  # Discard final state
exec_state: {init: 's, f: {} -> 'a having State 's} -> 's  # Discard result
```

#### 14. **Reader Effect (`std::effects::reader`)**
```
effect Reader having 'r {
    ask: {} -> 'r
    local: {modify: {r: 'r} -> 'r, f: {} -> 'a} -> 'a
}

impl Reader for WithReader

run_reader: {env: 'r, f: {} -> 'a having Reader 'r} -> 'a
```

#### 15. **Writer Effect (`std::effects::writer`)**
```
effect Writer having 'w {
    tell: {w: 'w} -> {}
    listen: {f: {} -> 'a} -> {result: 'a, written: 'w}
}

impl Writer for WithWriter  # Requires Monoid 'w

run_writer: {f: {} -> 'a having Writer 'w} -> {result: 'a, written: 'w}
```

---

## Implementation Priority Order

### Phase 1: Testing (No new code, just tests)
1. Add execution tests for `std::math::core`
2. Add execution tests for `std::math::trig`
3. Add execution tests for `std::bool`
4. Add tests using mocks for `std::effects::time`
5. Add tests using mocks for `std::effects::filesystem`

### Phase 2: Small Additions
1. `std::tuple` - Simple data structures
2. `std::either` - Common functional type
3. `std::encoding` - Base64/Hex (pure functions)
4. `std::uuid` - Commonly needed

### Phase 3: Medium Additions
1. `std::iter` / `std::seq` - Lazy sequences
2. `std::validation` - Error accumulation
3. `std::duration` - Time durations
4. `std::ord` - Ordering utilities

### Phase 4: Large Additions (Effects/Integration)
1. `std::effects::state` - State effect
2. `std::effects::reader` - Reader effect
3. `std::effects::writer` - Writer effect
4. `std::http` - HTTP client
5. `std::hash` - Hashing functions

### Phase 5: Specialized
1. `std::decimal` - Arbitrary precision
2. `std::text` - Regex support
3. Additional crypto primitives

---

## Summary

**Current Stdlib Strengths:**
- Solid primitive types (Int, Nat, Float, String, Char, Frac)
- Good Option/Result handling
- Comprehensive collections (Array, List, Map, Set, Deque, SortedMap, SortedSet)
- Rich effect system foundation
- JSON serialization

**Current Gaps:**
1. No lazy sequences/iterators
2. No Either/Validation types
3. No Tuple utilities
4. Limited encoding (no Base64, Hex)
5. No UUID support
6. No HTTP client
7. No hashing utilities
8. No Duration type (time effects exist but no Duration value type)
9. No Regex support
10. No State/Reader/Writer effects

**Testing Gaps:**
1. Math modules only have type-check tests, no execution verification
2. Bool module not execution-tested
3. Effects with mocks (Time, FileSystem, Environment, Log) not tested despite mocks existing
4. Math/random distribution functions not tested
