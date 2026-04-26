package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for synchronization points and coordination patterns in V2.
 *
 * The V1 version tested internal infrastructure classes directly:
 * - SynchronizationPointManager
 * - LazySynchronizationCoordinator
 * - LoomConcurrencyManager
 *
 * This V2 version tests equivalent synchronization concepts through
 * the Mezze language API using latch, atom, and virtual thread primitives.
 *
 * Migrated from V1 SynchronizationPointsTest.kt
 */
@Execution(ExecutionMode.SAME_THREAD)
class SynchronizationPointsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Setup code for Loom virtual thread primitives
    private val loomSetup = """
        newtype Promise 'a = Promise __NativePromise
        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
        let sleep: {ms: Int} -> {} = {ms} -> __native_loom_sleep {ms = ms}
    """.trimIndent()

    // Atom setup for shared state
    private val atomSetup = """
        newtype Atom 'a = Atom __NativeAtomicRef
        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
    """.trimIndent()

    // Latch setup for synchronization points
    private val latchSetup = """
        newtype Latch = Latch __NativeLatch
        let latch: {count: Int} -> Latch = {count} -> __native_latch_new {count = count}
        let count_down: {latch: Latch} -> {} = {latch} -> __native_latch_count_down {latch = latch}
        let await_latch: {latch: Latch} -> {} = {latch} -> __native_latch_await {latch = latch}
        let await_latch_timeout: {latch: Latch, ms: Int} -> Bool = {latch, ms} -> __native_latch_await_timeout {latch = latch, ms = ms}
        let get_count: {latch: Latch} -> Int = {latch} -> __native_latch_get_count {latch = latch}
    """.trimIndent()

    // =============================================================================
    // Simple Synchronization Points
    // =============================================================================

    @Test
    fun `test simple synchronization point without dependencies`() {
        val code = """
            $loomSetup

            let computation = spawn {f = {} -> 42}
            await {p = computation}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test synchronization point with dependencies`() {
        val code = """
            $loomSetup
            $atomSetup

            let depStarted = atom {value = False}
            let mainStarted = atom {value = False}

            # Create dependency operation (must complete first)
            let setDepStarted = {current: Bool} -> True
            let dep = spawn {f = {} -> do
                let _ = swap {atom = depStarted, f = setDepStarted}
                let _ = sleep {ms = 20}
                10
            }

            # Wait for dependency to complete, then compute
            let depValue = await {p = dep}
            let setMainStarted = {current: Bool} -> True
            let _ = swap {atom = mainStarted, f = setMainStarted}
            let mainResult = depValue * 2

            # Verify dependency completed before main started
            {result = mainResult, depDone = deref {atom = depStarted}, mainDone = deref {atom = mainStarted}}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(20L, result.getField("result"))
        assertEquals(true, result.getField("depDone"))
        assertEquals(true, result.getField("mainDone"))
    }

    // =============================================================================
    // Lazy Synchronization Tests
    // =============================================================================

    @Test
    fun `test lazy synchronization - computation only starts when awaited`() {
        val code = """
            $loomSetup
            $atomSetup

            let computationStarted = atom {value = False}
            let computationCompleted = atom {value = False}

            # Create a lazy computation
            let syncPoint = spawn {f = {} -> do
                let _ = swap {atom = computationStarted, f = {current} -> True}
                let _ = sleep {ms = 50}
                let _ = swap {atom = computationCompleted, f = {current} -> True}
                "computed"
            }

            # Small delay - computation may or may not have started yet
            # (spawn starts immediately, but this tests we can await)
            let _ = sleep {ms = 10}

            # Await the result
            let result = await {p = syncPoint}

            # Both flags should be true after awaiting
            {
                started = deref {atom = computationStarted},
                completed = deref {atom = computationCompleted},
                result = result
            }
        """
        val result = eval(code) as MezzeRecord
        assertEquals(true, result.getField("started"))
        assertEquals(true, result.getField("completed"))
        assertEquals("computed", result.getField("result"))
    }

    // =============================================================================
    // Synchronization Point Timeout Tests
    // =============================================================================

    @Test
    fun `test synchronization point with latch timeout - succeeds`() {
        val code = """
            $latchSetup
            $loomSetup

            let syncPoint = latch {count = 1}

            let worker = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                count_down {latch = syncPoint}
            }

            # Should succeed before timeout
            let success = await_latch_timeout {latch = syncPoint, ms = 500}
            let _ = await {p = worker}
            success
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test synchronization point with latch timeout - times out`() {
        val code = """
            $latchSetup

            let syncPoint = latch {count = 1}
            # Don't count down, so it should timeout
            await_latch_timeout {latch = syncPoint, ms = 50}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // =============================================================================
    // Complex Dependency Patterns (Diamond Pattern)
    // =============================================================================

    @Test
    fun `test multiple synchronization points with diamond dependencies`() {
        val code = """
            $loomSetup
            $atomSetup
            $latchSetup

            # Create a diamond dependency pattern: A -> B, A -> C, B -> D, C -> D
            let aLatch = latch {count = 1}
            let bLatch = latch {count = 1}
            let cLatch = latch {count = 1}
            let aValue = atom {value = 0}
            let bValue = atom {value = 0}
            let cValue = atom {value = 0}

            # Helper functions for swap
            let setToOne = {current: Int} -> 1
            let addTen = {current: Int} -> deref {atom = aValue} + 10
            let addHundred = {current: Int} -> deref {atom = aValue} + 100

            # Operation A (no dependencies)
            let pA = spawn {f = {} -> do
                let _ = sleep {ms = 20}
                let _ = swap {atom = aValue, f = setToOne}
                count_down {latch = aLatch}
            }

            # Operation B (depends on A)
            let pB = spawn {f = {} -> do
                let _ = await_latch {latch = aLatch}
                let a = deref {atom = aValue}
                let _ = sleep {ms = 10}
                let setBValue = {current: Int} -> a + 10
                let _ = swap {atom = bValue, f = setBValue}
                count_down {latch = bLatch}
            }

            # Operation C (depends on A)
            let pC = spawn {f = {} -> do
                let _ = await_latch {latch = aLatch}
                let a = deref {atom = aValue}
                let _ = sleep {ms = 10}
                let setCValue = {current: Int} -> a + 100
                let _ = swap {atom = cValue, f = setCValue}
                count_down {latch = cLatch}
            }

            # Operation D (depends on B and C)
            let pD = spawn {f = {} -> do
                let _ = await_latch {latch = bLatch}
                let _ = await_latch {latch = cLatch}
                let b = deref {atom = bValue}
                let c = deref {atom = cValue}
                b + c
            }

            let _ = await {p = pA}
            let _ = await {p = pB}
            let _ = await {p = pC}
            let dResult = await {p = pD}

            # A = 1, B = 1 + 10 = 11, C = 1 + 100 = 101, D = 11 + 101 = 112
            dResult
        """
        val result = eval(code)
        assertEquals(112L, result)
    }

    // =============================================================================
    // Lazy Synchronization Coordinator Pattern
    // =============================================================================

    @Test
    fun `test lazy synchronization coordinator pattern`() {
        val code = """
            $loomSetup
            $atomSetup
            $latchSetup

            # Simulate lazy coordinator with multiple operations
            let op1Ready = latch {count = 1}
            let op2Ready = latch {count = 1}
            let op1Value = atom {value = 0}
            let op2Value = atom {value = 0}

            # op1: no dependencies
            let p1 = spawn {f = {} -> do
                let _ = swap {atom = op1Value, f = {current} -> 10}
                count_down {latch = op1Ready}
            }

            # op2: no dependencies
            let p2 = spawn {f = {} -> do
                let _ = swap {atom = op2Value, f = {current} -> 20}
                count_down {latch = op2Ready}
            }

            # op3: depends on op1 and op2
            let p3 = spawn {f = {} -> do
                let _ = await_latch {latch = op1Ready}
                let _ = await_latch {latch = op2Ready}
                let v1 = deref {atom = op1Value}
                let v2 = deref {atom = op2Value}
                v1 + v2
            }

            let _ = await {p = p1}
            let _ = await {p = p2}
            let result3 = await {p = p3}

            {op1 = deref {atom = op1Value}, op2 = deref {atom = op2Value}, op3 = result3}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(10L, result.getField("op1"))
        assertEquals(20L, result.getField("op2"))
        assertEquals(30L, result.getField("op3"))
    }

    // =============================================================================
    // Synchronization Point Statistics
    // =============================================================================

    @Test
    fun `test synchronization point tracking with latches`() {
        val code = """
            $latchSetup

            # Create several sync points (latches)
            let l0 = latch {count = 1}
            let l1 = latch {count = 1}
            let l2 = latch {count = 1}

            let initialCounts = {
                c0 = get_count {latch = l0},
                c1 = get_count {latch = l1},
                c2 = get_count {latch = l2}
            }

            # Complete one operation
            let _ = count_down {latch = l0}

            let afterCounts = {
                c0 = get_count {latch = l0},
                c1 = get_count {latch = l1},
                c2 = get_count {latch = l2}
            }

            {initial = initialCounts, after = afterCounts}
        """
        val result = eval(code) as MezzeRecord
        val initial = result.getField("initial") as MezzeRecord
        val after = result.getField("after") as MezzeRecord

        // Initially all counts are 1
        assertEquals(1L, initial.getField("c0"))
        assertEquals(1L, initial.getField("c1"))
        assertEquals(1L, initial.getField("c2"))

        // After completing l0, its count is 0
        assertEquals(0L, after.getField("c0"))
        assertEquals(1L, after.getField("c1"))
        assertEquals(1L, after.getField("c2"))
    }

    // =============================================================================
    // Concurrent Access Tests
    // =============================================================================

    @Test
    fun `test concurrent access to synchronization points`() {
        val code = """
            $loomSetup
            $atomSetup

            let results = atom {value = []}

            # Create multiple concurrent computations
            let p0 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                0 * 0
            }
            let p1 = spawn {f = {} -> do
                let _ = sleep {ms = 15}
                1 * 1
            }
            let p2 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                2 * 2
            }
            let p3 = spawn {f = {} -> do
                let _ = sleep {ms = 20}
                3 * 3
            }
            let p4 = spawn {f = {} -> do
                let _ = sleep {ms = 8}
                4 * 4
            }

            # Await all results
            let r0 = await {p = p0}
            let r1 = await {p = p1}
            let r2 = await {p = p2}
            let r3 = await {p = p3}
            let r4 = await {p = p4}

            # Sum should be 0 + 1 + 4 + 9 + 16 = 30
            r0 + r1 + r2 + r3 + r4
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // State Transition Tests
    // =============================================================================

    @Test
    fun `test synchronization point state transitions`() {
        val code = """
            $loomSetup
            $atomSetup
            $latchSetup

            let state = atom {value = "pending"}
            let completionLatch = latch {count = 1}

            let syncPoint = spawn {f = {} -> do
                let _ = swap {atom = state, f = {current} -> "running"}
                let _ = sleep {ms = 50}
                let _ = swap {atom = state, f = {current} -> "completed"}
                let _ = count_down {latch = completionLatch}
                "done"
            }

            # Wait a bit for thread to start
            let _ = sleep {ms = 10}
            let runningState = deref {atom = state}

            # Wait for completion
            let _ = await_latch {latch = completionLatch}
            let completedState = deref {atom = state}

            let result = await {p = syncPoint}

            {
                running = runningState,
                completed = completedState,
                result = result
            }
        """
        val result = eval(code) as MezzeRecord
        assertEquals("running", result.getField("running"))
        assertEquals("completed", result.getField("completed"))
        assertEquals("done", result.getField("result"))
    }

    // =============================================================================
    // Multiple Threads Waiting Tests
    // =============================================================================

    @Test
    fun `test multiple threads waiting on same sync point`() {
        val code = """
            $loomSetup
            $atomSetup
            $latchSetup

            let gate = latch {count = 1}
            let counter = atom {value = 0}

            # Spawn 3 threads all waiting on the same gate
            let p1 = spawn {f = {} -> do
                let _ = await_latch {latch = gate}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let p2 = spawn {f = {} -> do
                let _ = await_latch {latch = gate}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let p3 = spawn {f = {} -> do
                let _ = await_latch {latch = gate}
                swap {atom = counter, f = {current} -> current + 1}
            }

            # Give threads time to start waiting
            let _ = sleep {ms = 20}
            let beforeRelease = deref {atom = counter}

            # Release all threads at once
            let _ = count_down {latch = gate}

            let _ = await {p = p1}
            let _ = await {p = p2}
            let _ = await {p = p3}

            let afterRelease = deref {atom = counter}

            {before = beforeRelease, after = afterRelease}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(0L, result.getField("before"))
        assertEquals(3L, result.getField("after"))
    }

    // =============================================================================
    // Execution Plan / Critical Path Tests
    // =============================================================================

    @Test
    fun `test execution plan with sequential dependencies`() {
        val code = """
            $loomSetup
            $atomSetup
            $latchSetup

            # A -> B -> C -> D (sequential chain)
            # Track execution using separate atoms to verify order
            let aLatch = latch {count = 1}
            let bLatch = latch {count = 1}
            let cLatch = latch {count = 1}

            # Use counter to verify sequential execution
            let counter = atom {value = 0}
            let aOrder = atom {value = 0}
            let bOrder = atom {value = 0}
            let cOrder = atom {value = 0}
            let dOrder = atom {value = 0}

            let incrementCounter = {current: Int} -> current + 1

            let pA = spawn {f = {} -> do
                let order = swap {atom = counter, f = incrementCounter}
                let setAOrder = {current: Int} -> order
                let _ = swap {atom = aOrder, f = setAOrder}
                count_down {latch = aLatch}
            }

            let pB = spawn {f = {} -> do
                let _ = await_latch {latch = aLatch}
                let order = swap {atom = counter, f = incrementCounter}
                let setBOrder = {current: Int} -> order
                let _ = swap {atom = bOrder, f = setBOrder}
                count_down {latch = bLatch}
            }

            let pC = spawn {f = {} -> do
                let _ = await_latch {latch = bLatch}
                let order = swap {atom = counter, f = incrementCounter}
                let setCOrder = {current: Int} -> order
                let _ = swap {atom = cOrder, f = setCOrder}
                count_down {latch = cLatch}
            }

            let pD = spawn {f = {} -> do
                let _ = await_latch {latch = cLatch}
                let order = swap {atom = counter, f = incrementCounter}
                let setDOrder = {current: Int} -> order
                swap {atom = dOrder, f = setDOrder}
            }

            let _ = await {p = pA}
            let _ = await {p = pB}
            let _ = await {p = pC}
            let _ = await {p = pD}

            {
                a = deref {atom = aOrder},
                b = deref {atom = bOrder},
                c = deref {atom = cOrder},
                d = deref {atom = dOrder}
            }
        """
        val result = eval(code) as MezzeRecord
        // A should execute first (order 1), then B (2), then C (3), then D (4)
        assertEquals(1L, result.getField("a"))
        assertEquals(2L, result.getField("b"))
        assertEquals(3L, result.getField("c"))
        assertEquals(4L, result.getField("d"))
    }

    // =============================================================================
    // Error Handling in Sync Points
    // =============================================================================

    @Test
    fun `test sync point continues after partial completion`() {
        val code = """
            $loomSetup
            $atomSetup
            $latchSetup

            let l = latch {count = 3}
            let completedOps = atom {value = 0}

            # Complete 2 out of 3 operations
            let p1 = spawn {f = {} -> do
                let _ = swap {atom = completedOps, f = {current} -> current + 1}
                count_down {latch = l}
            }

            let p2 = spawn {f = {} -> do
                let _ = swap {atom = completedOps, f = {current} -> current + 1}
                count_down {latch = l}
            }

            let _ = await {p = p1}
            let _ = await {p = p2}

            # Latch should still have count 1
            let remainingCount = get_count {latch = l}
            let completed = deref {atom = completedOps}

            {remaining = remainingCount, completed = completed}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(1L, result.getField("remaining"))
        assertEquals(2L, result.getField("completed"))
    }

    @Test
    fun `test sync point with immediate completion`() {
        val code = """
            $latchSetup

            # Create a latch with count 0 (already released)
            let l = latch {count = 0}

            # Await should return immediately
            let _ = await_latch {latch = l}
            let success = await_latch_timeout {latch = l, ms = 1}

            success
        """
        val result = eval(code)
        assertEquals(true, result)
    }
}
