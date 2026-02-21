# Math Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/stdlib/`

## Summary

| Module | Functions | Test File | Coverage |
|--------|-----------|-----------|----------|
| core.mz | 19 | MathCoreModuleTest.kt | 100% |
| trig.mz | 14 | MathTrigModuleTest.kt | 100% |
| random.mz | 7 | MathRandomModuleTest.kt | 100% |

---

## core.mz
Test file: `MathCoreModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `pi` | :white_check_mark: | |
| `e` | :white_check_mark: | |
| `min` | :white_check_mark: | |
| `max` | :white_check_mark: | |
| `pow` | :white_check_mark: | |
| `pow_int` | :white_check_mark: | |
| `pow_nat` | :white_check_mark: | |
| `sqrt` | :white_check_mark: | |
| `ln` | :white_check_mark: | |
| `log10` | :white_check_mark: | |
| `exp` | :white_check_mark: | |
| `floor` | :white_check_mark: | |
| `ceil` | :white_check_mark: | |
| `round` | :white_check_mark: | |
| `is_nan` | :white_check_mark: | |
| `is_infinite` | :white_check_mark: | |
| `is_finite` | :white_check_mark: | |
| `sign` | :white_check_mark: | |
| `sign_int` | :white_check_mark: | |

---

## trig.mz
Test file: `MathTrigModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `sin` | :white_check_mark: | |
| `cos` | :white_check_mark: | |
| `tan` | :white_check_mark: | |
| `asin` | :white_check_mark: | |
| `acos` | :white_check_mark: | |
| `atan` | :white_check_mark: | |
| `atan2` | :white_check_mark: | |
| `pi` | :white_check_mark: | |
| `e` | :white_check_mark: | |
| `degrees_to_radians` | :white_check_mark: | |
| `radians_to_degrees` | :white_check_mark: | |
| `tanh` | :white_check_mark: | |
| `sinh` | :white_check_mark: | |
| `cosh` | :white_check_mark: | |

---

## random.mz
Test file: `MathRandomModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `box_muller` | :white_check_mark: | |
| `exponential_inverse` | :white_check_mark: | |
| `uniform_transform` | :white_check_mark: | |
| `normal_pdf` | :white_check_mark: | |
| `normal_cdf_approx` | :white_check_mark: | |
| `exponential_pdf` | :white_check_mark: | |
| `exponential_cdf` | :white_check_mark: | |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
