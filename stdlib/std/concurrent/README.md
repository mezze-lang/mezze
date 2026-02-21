# Concurrent Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/concurrency/`

## Summary

| Module | Functions | Test File | Coverage |
|--------|-----------|-----------|----------|
| atom.mz | 11 | AtomTest.kt | 100% |
| agent.mz | 10 | AgentTest.kt | 90% |
| channel.mz | 2 | ChannelTest.kt | 100% |
| mutex.mz | 6 | MutexTest.kt | 100% |
| semaphore.mz | 9 | SemaphoreTest.kt | 100% |
| latch.mz | 5 | LatchTest.kt | 100% |
| waitgroup.mz | 6 | WaitGroupTest.kt | 100% |
| cancellation.mz | 13 | CancellationTokenTest.kt | Needs audit |
| scope.mz | 2 | ScopedConcurrencyTest.kt | Needs audit |

---

## atom.mz
Test file: `concurrency/AtomTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `atom` | :white_check_mark: | Basic creation tested |
| `deref` | :white_check_mark: | Tested with various types |
| `reset` | :white_check_mark: | Sets new value, returns new value |
| `swap` | :white_check_mark: | Multiple swap tests |
| `compare_and_set` | :white_check_mark: | Success and failure cases |
| `swap_with` | :white_check_mark: | Via swap with complex functions |
| `get_and_swap` | :white_check_mark: | Via swap tests |
| `add_watch` | :white_check_mark: | Watch callback tests |
| `remove_watch` | :white_check_mark: | Stops notifications |
| `atom_validated` | :white_check_mark: | Validator tests |
| `set_validator` | :white_check_mark: | Dynamic validator tests |

---

## agent.mz
Test file: `concurrency/AgentTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `agent` | :white_check_mark: | Basic creation |
| `deref_agent` | :white_check_mark: | Read current value |
| `send` | :white_check_mark: | Single and multiple updates |
| `send_off` | :x: | Not explicitly tested |
| `await_agent` | :white_check_mark: | Wait for completion |
| `await_agent_timeout` | :white_check_mark: | Timeout test |
| `agent_error` | :white_check_mark: | No error initially |
| `has_error` | :white_check_mark: | False initially |
| `restart_agent` | :white_check_mark: | Sets new value |
| `set_error_handler` | :x: | Not explicitly tested |

---

## channel.mz
Test file: `concurrency/ChannelTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `chan` | :white_check_mark: | Unbounded creation |
| `chan_bounded` | :white_check_mark: | Bounded creation, rejects non-positive |

**Native Channel Operations Tested:**
- `send/recv` - Single value, multiple values, order preservation
- `try_send/try_recv` - Non-blocking operations
- `close` - Idempotent, marks closed, can receive remaining values
- `is_closed` - Correct status tracking
- `recv_any` - Select from multiple channels
- `recv_any_timeout` - Timeout handling
- `mock_channel_*` - Mock operations for testing

---

## mutex.mz
Test file: `concurrency/MutexTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `mutex` | :white_check_mark: | Creation |
| `with_lock` | :white_check_mark: | Executes function, returns result |
| `lock` | :white_check_mark: | Manual lock |
| `unlock` | :white_check_mark: | Manual unlock |
| `try_lock` | :white_check_mark: | Succeeds/fails appropriately |
| `is_held_by_current_thread` | :white_check_mark: | Correct reporting |

**Additional Tests:**
- Reentrant locking (same thread can lock twice)
- Concurrent protection with Loom
- Lock release after completion

---

## semaphore.mz
Test file: `concurrency/SemaphoreTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `semaphore` | :white_check_mark: | Creation with permits |
| `acquire` | :white_check_mark: | Decreases available |
| `acquire_n` | :white_check_mark: | Multiple permits |
| `release` | :white_check_mark: | Increases available |
| `release_n` | :white_check_mark: | Multiple permits |
| `try_acquire` | :white_check_mark: | Success/failure cases |
| `try_acquire_n` | :x: | Needs explicit test |
| `available` | :white_check_mark: | Returns permit count |
| `with_permit` | :white_check_mark: | Executes and releases |

**Additional Tests:**
- Concurrency limiting with Loom
- Zero permits blocking until released

---

## latch.mz
Test file: `concurrency/LatchTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `latch` | :white_check_mark: | Creation with count |
| `count_down` | :white_check_mark: | Decreases count |
| `await_latch` | :white_check_mark: | Immediate return at zero |
| `await_latch_timeout` | :white_check_mark: | Returns true/false |
| `get_count` | :white_check_mark: | Current count |

**Additional Tests:**
- Count doesn't go negative
- Thread synchronization
- Multiple threads waiting on same latch

---

## waitgroup.mz
Test file: `concurrency/WaitGroupTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `wait_group` | :white_check_mark: | Creation |
| `add` | :white_check_mark: | Increases count |
| `done` | :white_check_mark: | Decreases count |
| `wait` | :white_check_mark: | Immediate return at zero |
| `wait_timeout` | :white_check_mark: | Returns true/false |
| `get_count_wg` | :white_check_mark: | Current count |

**Additional Tests:**
- Dynamic task adding
- Incremental add
- Blocking until all done

---

## cancellation.mz
Test file: `concurrency/CancellationTokenTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `token` | :x: | Needs verification |
| `new` | :x: | Needs verification |
| `cancel` | :x: | Needs verification |
| `cancel_default` | :x: | Needs verification |
| `is_cancelled` | :x: | Needs verification |
| `get_reason` | :x: | Needs verification |
| `check_cancelled` | :x: | Needs verification |
| `throw_if_cancelled` | :x: | Needs verification |
| `on_cancel` | :x: | Needs verification |
| `on_cancellation` | :x: | Needs verification |
| `remove_callback` | :x: | Needs verification |
| `create_child` | :x: | Needs verification |
| `with_cancellation` | :x: | Needs verification |

---

## scope.mz
Test file: `concurrency/ScopedConcurrencyTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `with_scope` | :x: | Needs verification |
| `fork_all` | :x: | Needs verification |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
