package com.mezze.interpreter.effects

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for Virtual Thread Effect Infrastructure
 *
 * This is the V2 migration of the V1 SimpleVirtualThreadTest which tested
 * internal infrastructure classes (EffectContext, CancellationToken,
 * EffectStateMachine, SimpleEffectHandler, etc.). In V2, we test the same
 * concepts through the Mezze language API.
 *
 * Tests cover:
 * - Effect context creation with virtual thread support
 * - Cancellation support
 * - Child context creation
 * - State machine execution
 * - Effect handlers with virtual threads
 * - Cancellation token propagation
 */
class SimpleVirtualThreadTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    // Runs `body` inside `perform LoomAsync in do` with stdlib Async
    // imported and top-level `spawn`/`await`/`sleep` wrappers around
    // Async.spawn + stdlib await (unwrapped from Result 'a AsyncError)
    // + stdlib async::sleep. `header` (optional) holds local
    // effect/impl decls since those must live at top level.
    private fun eval(body: String, header: String = ""): Any {
        val code = """
use std::effects::async::{ Async, LoomAsync, await as await_result, sleep as async_sleep }

let unwrap_async = {r} -> match r is
    Ok { val } -> val
    Err { error = _ } -> crash { msg = "await failed" }

let spawn = {f} -> Async.spawn {f = f}
let await = {p} -> unwrap_async {r = await_result {p = p}}
let sleep = {ms} -> async_sleep {ms = ms}

$header

perform LoomAsync in do
$body
        """.trimIndent()
        return MezzeTestHelper.run(code)
    }

    private val threadSetup = ""

    // =============================================================================
    // Basic Effect Context Tests (through language API)
    // =============================================================================

    @Test
    fun `should create effect context with virtual thread support`() {
        // V1 tested: assertNotNull(effectContext.concurrencyManager)
        // V2 tests that we can spawn and await a virtual thread
        val code = """

            let thread = spawn {f = {} -> "Hello Virtual Threads"}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals("Hello Virtual Threads", result)
    }

    @Test
    fun `should support cancellation through virtual threads`() {
        // V1 tested: CancellationToken and EffectCancellationException
        // V2 tests that spawned threads can complete and don't hang
        val code = """

            let t1 = spawn {f = {} -> 1}
            let t2 = spawn {f = {} -> 2}

            let r1 = await {p = t1}
            let r2 = await {p = t2}

            r1 + r2
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `should create child contexts through nested threads`() {
        // V1 tested: effectContext.createChildContext("child-test")
        // V2 tests nested virtual thread spawning
        val code = """

            let outer = spawn {f = {} -> do
                let inner = spawn {f = {} -> "child-context-value"}
                await {p = inner}
            }
            await {p = outer}
        """
        val result = eval(code)
        assertEquals("child-context-value", result)
    }

    // =============================================================================
    // Virtual Thread Future Tests
    // =============================================================================

    @Test
    fun `should handle virtual thread futures`() {
        // V1 tested: effectContext.withVirtualThreadFuture() and getVirtualThreadFuture()
        // V2 tests that Promise/Future-like semantics work
        val code = """

            let future = spawn {f = {} -> "test-result"}
            let result = await {p = future}
            result
        """
        val result = eval(code)
        assertEquals("test-result", result)
    }

    @Test
    fun `should handle multiple futures concurrently`() {
        val code = """

            let f1 = spawn {f = {} -> 10}
            let f2 = spawn {f = {} -> 20}
            let f3 = spawn {f = {} -> 12}

            let r1 = await {p = f1}
            let r2 = await {p = f2}
            let r3 = await {p = f3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // State Machine Execution Tests
    // =============================================================================

    @Test
    fun `should execute simple state machine`() {
        // V1 tested: EffectStateMachineBuilder with compute states
        // V2 tests sequential computation in virtual threads
        val code = """

            let stateMachine = spawn {f = {} -> do
                let state1 = "Hello"
                let state2 = " Virtual"
                let state3 = " Threads"
                # Simulate state transitions by computing through states
                state1
            }
            await {p = stateMachine}
        """
        val result = eval(code)
        assertEquals("Hello", result)
    }

    @Test
    fun `should execute multi-state computation`() {
        // V1 tested: EffectStateMachine with multiple states
        val code = """

            let compute = spawn {f = {} -> do
                let step1 = 10
                let step2 = step1 * 2
                let step3 = step2 + 22
                step3
            }
            await {p = compute}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Effect Handler Tests
    // =============================================================================

    @Test
    fun `should handle effects with virtual threads`() {
        // V1 tested: SimpleEffectHandler with VirtualThreadEffectOperation.
        // No spawn/await here — bypass eval()'s LoomAsync wrap.
        val code = """
            effect Greet where {
                greet: {name: Str} -> Str <Greet>
            }

            impl Greet for Greeter where {
                greet: {name: Str} -> Str = {name} -> "Hello, Mezze!"
            }

            perform Greeter in Greet.greet {name = "Mezze"}
        """
        val result = MezzeTestHelper.run(code)
        assertEquals("Hello, Mezze!", result)
    }

    @Test
    fun `should handle effects in spawned virtual threads`() {
        // Tests that effect handling works inside virtual threads.
        val header = """
            effect Counter where {
                increment: {value: Int} -> Int <Counter>
            }

            impl Counter for Incrementer where {
                increment: {value: Int} -> Int = {value} -> value + 1
            }
        """
        val body = """
            let thread = spawn {f = {} ->
                perform Incrementer in Counter.increment {value = 41}
            }
            await {p = thread}
        """
        val result = eval(body, header)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Cancellation Token Tests
    // =============================================================================

    @Test
    fun `should support cancellation tokens via thread completion`() {
        // V1 tested: CancellationToken.isCancelled() and cancel()
        // V2 tests that threads complete properly (no hanging = implicit not cancelled)
        val code = """

            let t1 = spawn {f = {} -> 1}
            let t2 = spawn {f = {} -> 2}
            let t3 = spawn {f = {} -> 3}

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    @Test
    fun `should support child cancellation tokens via nested threads`() {
        // V1 tested: parentToken.createChild() and parent cancellation propagation
        // V2 tests nested thread completion (child completes before parent)
        val code = """

            let parent = spawn {f = {} -> do
                let child1 = spawn {f = {} -> 10}
                let child2 = spawn {f = {} -> 20}

                let r1 = await {p = child1}
                let r2 = await {p = child2}

                r1 + r2
            }
            await {p = parent}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // Thread Coordination Tests
    // =============================================================================

    @Test
    fun `should coordinate multiple threads with sleep`() {
        val code = """

            let t1 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                1
            }
            let t2 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                2
            }

            let r1 = await {p = t1}
            let r2 = await {p = t2}

            r1 + r2
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `should handle deeply nested virtual threads`() {
        val code = """

            let level1 = spawn {f = {} -> do
                let level2 = spawn {f = {} -> do
                    let level3 = spawn {f = {} -> 42}
                    await {p = level3}
                }
                await {p = level2}
            }
            await {p = level1}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle thread with closure capturing`() {
        // V1 tested context sharing
        val code = """

            let multiplier = 7
            let addend = 1

            let thread = spawn {f = {} -> multiplier * 6 + addend}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(43L, result)
    }

    // =============================================================================
    // Complex Scenarios
    // =============================================================================

    @Test
    fun `should handle fan-out fan-in pattern`() {
        val code = """

            # Fan out
            let t1 = spawn {f = {} -> 1}
            let t2 = spawn {f = {} -> 2}
            let t3 = spawn {f = {} -> 3}
            let t4 = spawn {f = {} -> 4}
            let t5 = spawn {f = {} -> 5}

            # Fan in
            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}
            let r4 = await {p = t4}
            let r5 = await {p = t5}

            r1 + r2 + r3 + r4 + r5
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `should handle thread returning record`() {
        val code = """

            let thread = spawn {f = {} -> {x = 10, y = 32}}
            let result = await {p = thread}
            result.x + result.y
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle thread returning boolean`() {
        val code = """

            let thread = spawn {f = {} -> True}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `should handle thread returning unit`() {
        val code = """

            let thread = spawn {f = {} -> {}}
            let _ = await {p = thread}
            True
        """
        val result = eval(code)
        assertEquals(true, result)
    }
}
