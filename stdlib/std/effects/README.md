# Effects Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/`

## Summary

| Module | Functions | Tested | Coverage |
|--------|-----------|--------|----------|
| time.mz | 23 | 21 | 91% |
| filesystem.mz | 8 | 8 | 100% |
| environment.mz | 3 | 0 | 0% - NO TEST FILE |
| log.mz | 2 | 2 | 100% |
| random.mz | 11 | 11 | 100% |
| generator.mz | 17 | 17 | 100% |
| exception.mz | 2 | 2 | 100% |
| early_return.mz | 3 | 3 | 100% |
| choice.mz | 1 | 1 | 100% |
| async.mz | 2 | 0 | 0% - NO TEST FILE |
| concurrency.mz | 2 | 1 | 50% |
| inspect.mz | 0 | N/A | Ability only |

**Note:** Many async/concurrency effects are tested via `concurrency/` test folder - see `std/concurrent/README.md` and `std/async/README.md`

**Overall: 70/72 functions tested (excluding environment.mz and async.mz which have no test files)**

---

## time.mz
Test file: `TimeEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `now` | :white_check_mark: | |
| `sleep` | :white_check_mark: | |
| `current_timestamp` | :white_check_mark: | |
| `current_date` | :white_check_mark: | |
| `current_time` | :white_check_mark: | |
| `current_datetime` | :white_check_mark: | |
| `add_millis` | :white_check_mark: | |
| `add_seconds` | :white_check_mark: | |
| `add_minutes` | :white_check_mark: | |
| `add_hours` | :white_check_mark: | |
| `add_days` | :white_check_mark: | |
| `diff_millis` | :white_check_mark: | |
| `diff_seconds` | :white_check_mark: | |
| `diff_minutes` | :white_check_mark: | |
| `diff_hours` | :white_check_mark: | |
| `diff_days` | :white_check_mark: | |
| `is_leap_year` | :white_check_mark: | |
| `days_in_month` | :white_check_mark: | |
| `format_iso8601` | :x: | Needs test |
| `format_date` | :white_check_mark: | |
| `format_time_only` | :x: | Needs test |
| `format_datetime` | :white_check_mark: | |
| `elapsed_since` | :white_check_mark: | |

---

## filesystem.mz
Test file: `FileSystemEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `path_join` | :white_check_mark: | |
| `file_name` | :white_check_mark: | |
| `parent` | :white_check_mark: | |
| `extension` | :white_check_mark: | |
| `has_extension` | :white_check_mark: | |
| `read_file_or` | :white_check_mark: | |
| `write_file_mkdirs` | :white_check_mark: | |
| `ensure_directory` | :white_check_mark: | |

---

## environment.mz
Test file: **NONE - NEEDS CREATION**

| Function | Tested | Notes |
|----------|--------|-------|
| `get_env` | :x: | NO TEST FILE |
| `current_dir` | :x: | NO TEST FILE |
| `os_info` | :x: | NO TEST FILE |

---

## log.mz
Test file: `LogEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `info` | :white_check_mark: | |
| `println` | :white_check_mark: | |

---

## random.mz
Test file: `RandomEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `random_float` | :white_check_mark: | |
| `random_int` | :white_check_mark: | |
| `random_nat` | :white_check_mark: | |
| `random_boolean` | :white_check_mark: | |
| `random_range` | :white_check_mark: | |
| `seed_random` | :white_check_mark: | |
| `uniform` | :white_check_mark: | |
| `normal` | :white_check_mark: | |
| `exponential` | :white_check_mark: | |
| `bernoulli` | :white_check_mark: | |
| `random_element` | :white_check_mark: | |

---

## generator.mz
Test file: `GeneratorEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `new_generator` | :white_check_mark: | |
| `yield` | :white_check_mark: | |
| `next` | :white_check_mark: | |
| `to_list_helper` | :white_check_mark: | Internal helper |
| `to_list` | :white_check_mark: | |
| `take_helper` | :white_check_mark: | Internal helper |
| `take` | :white_check_mark: | |
| `map_helper` | :white_check_mark: | Internal helper |
| `map` | :white_check_mark: | |
| `filter_helper` | :white_check_mark: | Internal helper |
| `filter` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `count_helper` | :white_check_mark: | Internal helper |
| `count` | :white_check_mark: | |
| `find` | :white_check_mark: | |
| `any` | :white_check_mark: | |
| `all` | :white_check_mark: | |

---

## exception.mz
Test file: `ExceptionEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `throw` | :white_check_mark: | |
| `unwrap` | :white_check_mark: | |

---

## early_return.mz
Test file: `EarlyReturnEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `return` | :white_check_mark: | |
| `return_if` | :white_check_mark: | |
| `return_some` | :white_check_mark: | |

---

## choice.mz
Test file: `ChoiceEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `run_all_paths` | :white_check_mark: | |

---

## async.mz
Test file: **NONE - NEEDS CREATION**

| Function | Tested | Notes |
|----------|--------|-------|
| `resolved` | :x: | NO TEST FILE |
| `rejected` | :x: | NO TEST FILE |

---

## concurrency.mz
Test file: `ConcurrencyEffectTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `run` | :white_check_mark: | |
| `all` | :x: | Needs explicit test |

---

## inspect.mz
Test file: `InspectAbilityTest.kt`

No public functions - ability definition only.

---

## Modules Needing Work

### Priority 1: Create Test Files
- **environment.mz** - 3 functions, no test file
- **async.mz** - 2 functions, no test file

### Priority 2: Add Missing Tests
- **time.mz** - Add tests for `format_iso8601`, `format_time_only`
- **concurrency.mz** - Add test for `all`

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
- "NO TEST FILE" - No test file exists for this module
