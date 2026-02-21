# Async Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/`

## Summary

| Module | Functions | Test Files | Coverage |
|--------|-----------|-----------|----------|
| async.mz | 7 | CoopTest.kt, CoopConcurrencyTest.kt, OSThreadTest.kt, LoomConcurrencyTest.kt | Partial |
| coop.mz | 10 | CoopTest.kt, CoopConcurrencyTest.kt | Partial |
| osthread.mz | 13 | OSThreadTest.kt, OSThreadConcurrencyTest.kt | Partial |
| structured.mz | 11 | StructuredConcurrencyTest.kt | Partial |
| thread.mz | 5 | LoomVirtualThreadsTest.kt, TruffleAndLoomIntegrationTest.kt | Partial |

**Note:** The Async effect has multiple implementations (LoomAsync, OSThreadAsync, CoopAsync) tested across various test files.

---

## async.mz (Main Effect + Helpers)
Test files: `CoopTest.kt`, `CoopConcurrencyTest.kt`, `OSThreadTest.kt`, `OSThreadConcurrencyTest.kt`, `LoomConcurrencyTest.kt`

### Async Effect Operations

| Operation | Tested | Notes |
|-----------|--------|-------|
| `spawn` | :white_check_mark: | Via all implementations |
| `await` | :white_check_mark: | Via all implementations |
| `race` | :x: | Needs verification |
| `all` | :x: | Needs verification |
| `timeout` | :x: | Needs verification |
| `cancel` | :x: | Needs verification |
| `sleep` | :white_check_mark: | Via Loom/OS implementations |
| `is_done` | :x: | Needs verification |
| `is_cancelled` | :x: | Needs verification |
| `check` | :x: | Needs verification |
| `join` | :x: | Needs verification |
| `join3` | :x: | Needs verification |
| `map` | :x: | Needs verification |
| `for_each` | :x: | Needs verification |
| `filter` | :x: | Needs verification |
| `any` | :x: | Needs verification |
| `settle` | :x: | Needs verification |
| `scoped` | :x: | Needs verification |

### Helper Functions

| Function | Tested | Notes |
|----------|--------|-------|
| `run` | :x: | Needs verification |
| `go` | :x: | Needs verification |
| `run_coop` | :x: | Needs verification |
| `scope_spawn` | :x: | Needs verification |
| `scope_cancel_all` | :x: | Needs verification |
| `scope_get_token` | :x: | Needs verification |
| `scope_is_cancelled` | :x: | Needs verification |

### Implementations

| Implementation | Test File | Status |
|----------------|-----------|--------|
| `LoomAsync` | LoomConcurrencyTest.kt | :white_check_mark: Basic coverage |
| `LoomAsyncWith` | (config variant) | :x: Needs test |
| `OSThreadAsync` | OSThreadTest.kt, OSThreadConcurrencyTest.kt | :white_check_mark: Basic coverage |
| `OSThreadAsyncWith` | (config variant) | :x: Needs test |
| `CoopAsync` | CoopTest.kt, CoopConcurrencyTest.kt | :white_check_mark: Basic coverage |

---

## coop.mz (Cooperative Async - Legacy)
Test files: `CoopAsyncModuleTest.kt`, `CoopTest.kt`, `CoopConcurrencyTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `coop_spawn` | :x: | Needs verification |
| `coop_await` | :x: | Needs verification |
| `coop_race` | :x: | Needs verification |
| `coop_all` | :x: | Needs verification |
| `coop_timeout` | :x: | Needs verification |
| `coop_cancel` | :x: | Needs verification |
| `coop_sleep` | :x: | Needs verification |
| `coop_is_done` | :x: | Needs verification |
| `coop_is_cancelled` | :x: | Needs verification |
| `coop_check` | :x: | Needs verification |

---

## osthread.mz (OS Thread Async - Legacy)
Test files: `OSThreadModuleTest.kt`, `OSThreadTest.kt`, `OSThreadConcurrencyTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `os_spawn` | :x: | Needs verification |
| `os_await` | :x: | Needs verification |
| `os_race` | :x: | Needs verification |
| `os_all` | :x: | Needs verification |
| `os_timeout` | :x: | Needs verification |
| `os_cancel` | :x: | Needs verification |
| `os_sleep` | :x: | Needs verification |
| `os_is_done` | :x: | Needs verification |
| `os_is_cancelled` | :x: | Needs verification |
| `os_check` | :x: | Needs verification |
| `os_current_thread` | :x: | Needs verification |
| `os_park` | :x: | Needs verification |
| `os_unpark` | :x: | Needs verification |

---

## structured.mz (Structured Concurrency)
Test files: `StructuredConcurrencyModuleTest.kt`, `StructuredConcurrencyTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `parallel` | :x: | Needs verification |
| `race` | :x: | Needs verification |
| `with_timeout` | :x: | Needs verification |
| `parallel_map` | :x: | Needs verification |
| `parallel_filter` | :x: | Needs verification |
| `scoped` | :x: | Needs verification |
| `join2` | :x: | Needs verification |
| `join3` | :x: | Needs verification |
| `parallel_for_each` | :x: | Needs verification |
| `any` | :x: | Needs verification |
| `settle` | :x: | Needs verification |

---

## thread.mz (Low-Level Thread)
Test files: `ThreadModuleTest.kt`, `LoomVirtualThreadsTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `park` | :x: | Needs verification |
| `unpark` | :x: | Needs verification |
| `current_thread` | :x: | Needs verification |
| `sleep` | :x: | Needs verification |
| `yield` | :x: | Needs verification |

---

## Related Test Files (for reference)

Test files in `interpreter/src/test/kotlin/com/mezze/language/interpreter/`:
- `LoomConcurrencyTest.kt` - Virtual thread integration
- `LoomVirtualThreadsTest.kt` - Virtual thread features
- `TruffleAndLoomIntegrationTest.kt` - Truffle + Loom integration
- `StructuredConcurrencyTest.kt` - Structured concurrency patterns
- `CoopConcurrencyTest.kt` - Cooperative async tests
- `OSThreadConcurrencyTest.kt` - OS thread tests

Test files in `concurrency/`:
- `AsyncConfigTest.kt` - Async configuration
- `AsyncExtendedTest.kt` - Extended async tests
- `CoopTest.kt` - Coop scheduler tests
- `OSThreadTest.kt` - OS thread tests
- `VirtualThreadConfigTest.kt` - Virtual thread configuration
- `VirtualThreadManagementTest.kt` - Thread management
- `VirtualThreadEffectIntegrationTest.kt` - Effect integration

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
