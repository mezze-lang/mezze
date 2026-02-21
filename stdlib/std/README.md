# Mezze Standard Library - Test Coverage Tracker

This directory contains the Mezze standard library modules. Each subdirectory has its own README with detailed function-level test coverage.

## Module Index

| Module | Path | Functions | Coverage |
|--------|------|-----------|----------|
| [Primitives](#primitives) | `std/` | 113 | See below |
| [Collections](collections/README.md) | `std/collections/` | 224 | See README |
| [Effects](effects/README.md) | `std/effects/` | 70 | See README |
| [Concurrent](concurrent/README.md) | `std/concurrent/` | 64 | See README |
| [Async](async/README.md) | `std/async/` | 44 | See README |
| [Math](math/README.md) | `std/math/` | 40 | See README |
| [Control](control/README.md) | `std/control/` | 1 | See README |
| [JSON](json/README.md) | `std/json/` | 2 | See README |

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
| `from_float` | :x: | Needs test |
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

### codec.mz - Serialization Utilities
Test file: `CodecModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `default_config` | :x: | Needs test |
| `empty_path` | :x: | Needs test |
| `append_path` | :x: | Needs test |
| `append_index` | :x: | Needs test |
| `format_path` | :x: | Needs test |
| `encode_error` | :x: | Needs test |
| `decode_error` | :x: | Needs test |

### json.mz - JSON Utilities
Test file: `JsonModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `default_config` | :x: | Needs verification |
| `config` | :x: | Needs verification |
| `serialize_to_string` | :x: | Needs verification |
| `to_string` | :x: | Needs verification |
| `parse` | :x: | Needs verification |
| `parse_str` | :x: | Needs verification |
| `transform_field_name` | :x: | Needs verification |
| `inverse_transform_field_name` | :x: | Needs verification |
| `transform_fields` | :x: | Needs verification |
| `inverse_transform_fields` | :x: | Needs verification |

### stm.mz - Software Transactional Memory
Test file: `concurrency/STMEffectTest.kt` (comprehensive - 50+ tests)

| Function | Tested | Notes |
|----------|--------|-------|
| `tvar` | :white_check_mark: | Creation with various types |
| `deref` | :white_check_mark: | Non-transactional read |
| `atomically` | :white_check_mark: | Helper wrapper tested |
| `transfer` | :white_check_mark: | Success and insufficient funds |
| `transfer_or_wait` | :x: | Needs test |
| `read_all` | :white_check_mark: | Bulk read tested |
| `write_all` | :x: | Needs test |
| `tmvar_empty` | :white_check_mark: | Empty TMVar creation |
| `tmvar` | :white_check_mark: | TMVar with initial value |
| `take_tmvar` | :white_check_mark: | Take from full |
| `put_tmvar` | :white_check_mark: | Put to empty |
| `read_tmvar` | :white_check_mark: | Read without taking |
| `try_take_tmvar` | :white_check_mark: | From full and empty |
| `try_put_tmvar` | :white_check_mark: | To empty and full |
| `swap_tmvar` | :white_check_mark: | Atomic swap |
| `is_empty_tmvar` | :white_check_mark: | Check empty status |
| `tqueue` | :white_check_mark: | Queue creation |
| `tqueue_from_list` | :x: | Needs test |
| `write_tqueue` | :white_check_mark: | Write to queue |
| `read_tqueue` | :white_check_mark: | Read from queue |
| `try_read_tqueue` | :white_check_mark: | From empty and non-empty |
| `peek_tqueue` | :white_check_mark: | Peek without removing |
| `is_empty_tqueue` | :white_check_mark: | True for new, false after write |
| `length_tqueue` | :x: | Needs test |
| `tbqueue` | :white_check_mark: | Bounded queue creation |
| `write_tbqueue` | :white_check_mark: | Write to bounded queue |
| `read_tbqueue` | :white_check_mark: | Read from bounded queue |
| `try_read_tbqueue` | :x: | Needs test |
| `try_write_tbqueue` | :white_check_mark: | When full returns false |
| `peek_tbqueue` | :x: | Needs test |
| `is_empty_tbqueue` | :x: | Needs test |
| `is_full_tbqueue` | :white_check_mark: | Check full status |
| `size_tbqueue` | :white_check_mark: | Current size |
| `capacity_tbqueue` | :white_check_mark: | Get capacity |

**STM Effect Operations Tested:**
- `STM.read`, `STM.write`, `STM.modify`, `STM.swap`, `STM.ensure`, `STM.check`
- `OptimisticSTM`, `OptimisticSTMWith`, `SequentialSTM` handlers
- Multiple TVars, nested transactions, read-your-own-writes

### parallel.mz - Parallel Processing
Test file: `ParallelEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `sum` | :x: | Needs verification |
| `product` | :x: | Needs verification |
| `count` | :x: | Needs verification |
| `flatten` | :x: | Needs verification |
| `flat_map` | :x: | Needs verification |

### async.mz - Async Runtime
Test file: `AsyncModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `run` | :x: | Needs verification |
| `go` | :x: | Needs verification |
| `run_coop` | :x: | Needs verification |
| `scope_spawn` | :x: | Needs verification |
| `scope_cancel_all` | :x: | Needs verification |
| `scope_get_token` | :x: | Needs verification |
| `scope_is_cancelled` | :x: | Needs verification |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
- "Needs verification" - Test file exists but coverage not confirmed

## Test File Locations

Test files are spread across the interpreter test directory:

```
interpreter/src/test/kotlin/com/mezze/language/interpreter/
├── stdlib/              # Module-specific tests (IntModuleTest.kt, etc.)
├── concurrency/         # Concurrent primitives (AtomTest.kt, STMEffectTest.kt, etc.)
├── codec/               # JSON codec tests (JsonCodecInterpreterTest.kt, etc.)
├── effects/             # Effect-specific tests
└── *.kt                 # General interpreter tests
```

**Key test files by category:**
- **Primitives**: `stdlib/IntModuleTest.kt`, `stdlib/FloatModuleTest.kt`, `stdlib/StringModuleTest.kt`, etc.
- **Collections**: `stdlib/ListModuleTest.kt`, `stdlib/ArrayModuleTest.kt`, `stdlib/MapModuleTest.kt`, etc.
- **Concurrent**: `concurrency/AtomTest.kt`, `concurrency/ChannelTest.kt`, `concurrency/STMEffectTest.kt`, etc.
- **Async**: `concurrency/CoopTest.kt`, `concurrency/OSThreadTest.kt`, `LoomConcurrencyTest.kt`, etc.
- **Effects**: `ExceptionEffectTest.kt`, `GeneratorEffectTest.kt`, `ChoiceEffectTest.kt`, etc.

---

## How to Update

1. When adding tests, update the corresponding README
2. Change :x: to :white_check_mark: when test is added
3. Add notes for any special testing considerations
