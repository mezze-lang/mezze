# V1 Interpreter Test Failures Analysis

**Total: 117 failures out of 3080 tests (96% passing)**

## Fixed Issues
- **AtomModuleTest.swap_with** - Fixed by passing `{current, arg}` record directly instead of wrapping it

## Failure Categories

### 1. HTTP Mock Tests (26 failures)
**Error**: `expected: <200> but was: <-1>`

The HTTP mock implementation is returning -1 status codes instead of the mocked responses.
This suggests the mock matching logic isn't working in V2.

**Files**: `HttpMockTest.kt`

### 2. Codec Auto-Derivation Tests (19 failures)
**Error**: `Cannot call record directly: {name = age, config = {field_transform = Identity}}`

The codec system is trying to invoke a record as a function. This is likely an issue with how
the auto-derived codec functions handle field configurations. The `fold_fields` or similar
function is receiving a record instead of a callable.

**Files**: `CodecAutoDerivationTest.kt`

### 3. Choice Effect Tests (12 failures)
**Error**: `Cannot multiply [5, 10] and 2`

The Choice effect is returning a list `[5, 10]` instead of properly handling the continuation.
The effect handler is collecting all choices into a list but not properly resuming the
continuation for each choice path.

**Files**: `ChoiceEffectTest.kt`

### 4. Agent Tests (9 + 11 failures)
**Error**: `class MezzeNull cannot be cast to class MezzeInt`

Agent operations are returning null/unit instead of the expected values. The `deref_agent`
or similar functions aren't returning values correctly. Also some NPE issues in global
definitions.

**Files**: `AgentTest.kt`, `AgentModuleTest.kt`

### 5. Runtime Effect Validation Tests (6 failures)
Need investigation - likely related to effect tracking differences between V1 and V2.

**Files**: `RuntimeEffectValidationTest.kt`

### 6. Async Extended Tests (6 failures)
Async operations like `map`, `filter`, `settle` not working correctly.

**Files**: `AsyncExtendedTest.kt`

### 7. Semaphore/STM/WaitGroup Tests (8 failures)
Various concurrency primitive issues.

**Files**: `SemaphoreModuleTest.kt`, `STMModuleTest.kt`, `WaitGroupModuleTest.kt`

### 8. Cancellation Token Tests (6 failures)
Child token cancellation propagation issues.

**Files**: `CancellationTokenTest.kt`, `CancellationModuleTest.kt`

### 9. Atom Module Tests (2 failures)
`swap_with` function not working correctly with string arguments.

**Files**: `AtomModuleTest.kt`

### 10. Miscellaneous (5 failures)
- `ContinuationModuleTest` - extract_suspended on suspended value
- `TimeEffectTest` - MockTime parse_timestamp
- `ConcurrencyEffectTest` - all runs multiple computations
- `DebugHandlersTest` - nested function calls
- `JsonCodecInterpreterTest` - parse invalid JSON

## Recommended Fix Order

1. **Codec tests** - "Cannot call record directly" suggests a fundamental issue with how
   functions/records are being invoked. This could affect many other tests.

2. **Choice effect** - Understanding why continuations aren't working for Choice will help
   fix Exception effect too.

3. **Agent/Atom tests** - These are simpler concurrency primitives, fixing them helps
   validate the async machinery.

4. **HTTP Mock** - Likely an issue with pattern matching or the mock registry.

5. **Async/Semaphore/STM** - More complex concurrency that builds on the above.

## Root Causes to Investigate

1. **Record invocation issue**: Something is treating records as callables incorrectly
2. **Continuation-based effects**: Choice/Exception effects need proper continuation handling
3. **Return value propagation**: Agent tests show values not being returned correctly
4. **Mock registry**: HTTP mock matching not working
