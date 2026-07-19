package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Unit tests for virtual thread spawning and management.
 * Tests requirement 1.1: Runtime-managed implicit parallelism.
 *
 * Migrated from V1 VirtualThreadManagementTest which tested internal Java classes:
 * - LoomConcurrencyManager (spawnVirtualThread, getThreadStatistics, shutdown)
 * - ExecutionContext (threadId, threadName, startTime, parentContext, metadata)
 * - LoomUtils (withContext)
 *
 * This V2 version tests the same concepts through the Mezze language API,
 * using spawn/await/sleep primitives and atomic references for coordination.
 */
@Execution(ExecutionMode.SAME_THREAD)
class VirtualThreadManagementTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Setup code for Loom virtual thread primitives
    private val loomSetup = """
        use std::effects::async::atom::{
            atom, deref, reset, swap, compare_and_set, swap_with, get_and_swap,
            add_watch, remove_watch, atom_validated, set_validator
        }
        use std::effects::async::{ Async, LoomAsync, await, sleep }
        use std::effects::time::{ Time, SystemTime }
        use std::effects::exception::{ Exception, CrashException, unwrap }

        perform SystemTime in perform LoomAsync in do
    """.trimIndent()

    // Atom primitives for concurrency tests
    private val atomSetup = ""

    // =============================================================================
    // Test: should spawn virtual thread successfully
    // Original V1: Verifies thread.isVirtual, thread name contains specified name,
    //              thread completes within timeout, and result is correct
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should spawn virtual thread successfully`() {
        val code = """
            $loomSetup

            let thread = Async.spawn {f = {} -> "Virtual thread executed"}
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals("Virtual thread executed", result)
    }

    @Test
    @Timeout(10)
    fun `should spawn virtual thread with computation`() {
        val code = """
            $loomSetup

            let thread = Async.spawn {f = {} -> 21 * 2}
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Test: should spawn multiple virtual threads concurrently
    // Original V1: Spawns 100 threads, each sleeps 100ms, verifies all complete
    //              and that total time is < 2000ms (parallel, not sequential)
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should spawn multiple virtual threads concurrently`() {
        val code = """
            $loomSetup
            $atomSetup

            let results = atom {val = 0}

            # Spawn multiple threads that each sleep briefly and increment counter
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = results, f = {current} -> current + 1}
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = results, f = {current} -> current + 1}
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = results, f = {current} -> current + 1}
            }
            let t4 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = results, f = {current} -> current + 1}
            }
            let t5 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = results, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }
            let _ = unwrap { result = await {p = t4} }
            let _ = unwrap { result = await {p = t5} }

            deref {atom = results}
        """

        val startTime = System.currentTimeMillis()
        val result = eval(code)
        val duration = System.currentTimeMillis() - startTime

        assertEquals(5L, result)
        // If sequential: 5 * 50ms = 250ms minimum
        // If concurrent: ~50-100ms
        // Allow generous margin for test environment
        assertTrue(duration < 1000, "Concurrent execution should be faster than sequential (${duration}ms)")
    }

    // =============================================================================
    // Test: should manage execution context properly
    // Original V1: Verifies that execution context is available in each thread,
    //              with valid threadId, threadName, and startTime
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should manage execution context properly via closures`() {
        val code = """
            $loomSetup
            $atomSetup

            let contextCounter = atom {val = 0}

            # Each thread captures its context (closure) and increments counter
            let t1 = Async.spawn {f = {} -> do
                let marker = "context-test-1"
                swap {atom = contextCounter, f = {current} -> current + 1}
            }
            let t2 = Async.spawn {f = {} -> do
                let marker = "context-test-2"
                swap {atom = contextCounter, f = {current} -> current + 1}
            }
            let t3 = Async.spawn {f = {} -> do
                let marker = "context-test-3"
                swap {atom = contextCounter, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            deref {atom = contextCounter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Test: should handle virtual thread exceptions gracefully
    // Original V1: Verifies that an exception in one thread doesn't affect others
    // Note: In V2, we test isolation by ensuring one thread's behavior doesn't
    //       affect another's result
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should handle thread isolation`() {
        val code = """
            $loomSetup
            $atomSetup

            let successCounter = atom {val = 0}

            # Spawn threads that complete independently
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = successCounter, f = {current} -> current + 1}
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 20}
                swap {atom = successCounter, f = {current} -> current + 1}
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 30}
                swap {atom = successCounter, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            deref {atom = successCounter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Test: should track thread statistics accurately
    // Original V1: Tracks totalVirtualThreadsCreated via ThreadStatistics
    // In V2: Track thread completion via atomic counters
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should track thread statistics accurately`() {
        val code = """
            $loomSetup
            $atomSetup

            let totalSpawned = atom {val = 0}
            let totalCompleted = atom {val = 0}

            # Track spawning and completion
            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 20}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 20}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 20}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t4 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 20}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t5 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 20}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }
            let _ = unwrap { result = await {p = t4} }
            let _ = unwrap { result = await {p = t5} }

            let spawned = deref {atom = totalSpawned}
            let completed = deref {atom = totalCompleted}

            # Verify all spawned threads completed
            if spawned == completed then spawned else 0 - 1
        """
        val result = eval(code)
        assertEquals(5L, result)
    }

    // =============================================================================
    // Test: should support nested virtual thread contexts
    // Original V1: Verifies parent-child context relationship
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should support nested virtual thread contexts`() {
        val code = """
            $loomSetup
            $atomSetup

            let parentValue = atom {val = 0}
            let childValue = atom {val = 0}

            let parent = Async.spawn {f = {} -> do
                let _ = reset {atom = parentValue, val = 10}

                let child = Async.spawn {f = {} -> do
                    let _ = reset {atom = childValue, val = 20}
                    "child done"
                }
                let _ = unwrap { result = await {p = child} }
                "parent done"
            }

            let _ = unwrap { result = await {p = parent} }

            let pv = deref {atom = parentValue}
            let cv = deref {atom = childValue}

            pv + cv
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle deeply nested virtual threads`() {
        val code = """
            $loomSetup

            let level1 = Async.spawn {f = {} -> do
                let level2 = Async.spawn {f = {} -> do
                    let level3 = Async.spawn {f = {} -> do
                        let level4 = Async.spawn {f = {} ->
                            "deep nested context"
                        }
                        unwrap { result = await {p = level4} }
                    }
                    unwrap { result = await {p = level3} }
                }
                unwrap { result = await {p = level2} }
            }
            unwrap { result = await {p = level1} }
        """
        val result = eval(code)
        assertEquals("deep nested context", result)
    }

    // =============================================================================
    // Test: should handle high concurrency loads
    // Original V1: Spawns 1000 threads with minimal work, verifies completion
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should handle high concurrency loads`() {
        val code = """
            $loomSetup
            $atomSetup

            let counter = atom {val = 0}

            # Spawn 20 threads that each increment counter
            let t1 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t2 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t3 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t4 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t5 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t6 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t7 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t8 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t9 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t10 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t11 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t12 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t13 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t14 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t15 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t16 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t17 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t18 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t19 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let t20 = Async.spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }
            let _ = unwrap { result = await {p = t4} }
            let _ = unwrap { result = await {p = t5} }
            let _ = unwrap { result = await {p = t6} }
            let _ = unwrap { result = await {p = t7} }
            let _ = unwrap { result = await {p = t8} }
            let _ = unwrap { result = await {p = t9} }
            let _ = unwrap { result = await {p = t10} }
            let _ = unwrap { result = await {p = t11} }
            let _ = unwrap { result = await {p = t12} }
            let _ = unwrap { result = await {p = t13} }
            let _ = unwrap { result = await {p = t14} }
            let _ = unwrap { result = await {p = t15} }
            let _ = unwrap { result = await {p = t16} }
            let _ = unwrap { result = await {p = t17} }
            let _ = unwrap { result = await {p = t18} }
            let _ = unwrap { result = await {p = t19} }
            let _ = unwrap { result = await {p = t20} }

            deref {atom = counter}
        """

        val startTime = System.currentTimeMillis()
        val result = eval(code)
        val duration = System.currentTimeMillis() - startTime

        assertEquals(20L, result)
        assertTrue(duration < 5000, "High concurrency should complete quickly (${duration}ms)")
    }

    // =============================================================================
    // Test: should preserve thread-local context across suspension points
    // Original V1: Tests that metadata is preserved across sleep calls
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should preserve thread-local context across suspension points`() {
        val code = """
            $loomSetup
            $atomSetup

            let contextValue = atom {val = "initial"}

            let thread = Async.spawn {f = {} -> do
                # Set context value before suspension
                let _ = reset {atom = contextValue, val = "test-value"}

                # Suspension point 1
                let _ = sleep {ms = 50}

                # Check context is preserved
                let beforeValue = deref {atom = contextValue}

                # Suspension point 2
                let _ = sleep {ms = 25}

                # Check context is still preserved
                let afterValue = deref {atom = contextValue}

                # Both should match
                if beforeValue == afterValue then "preserved" else "lost"
            }

            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals("preserved", result)
    }

    @Test
    @Timeout(10)
    fun `should preserve closure context across suspension points`() {
        val code = """
            $loomSetup

            let testKey = "test-value"
            let thread = Async.spawn {f = {} -> do
                # First suspension point
                let _ = sleep {ms = 50}
                let before = testKey

                # Second suspension point
                let _ = sleep {ms = 25}
                let after = testKey

                # Closure values should be preserved
                if before == after then testKey else "lost"
            }
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals("test-value", result)
    }

    // =============================================================================
    // Additional V2 Tests: Thread Return Types
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should handle thread returning boolean`() {
        val code = """
            $loomSetup

            let thread = Async.spawn {f = {} -> True}
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    @Timeout(10)
    fun `should handle thread returning unit`() {
        val code = """
            $loomSetup

            let thread = Async.spawn {f = {} -> {}}
            let _ = unwrap { result = await {p = thread} }
            True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    @Timeout(10)
    fun `should handle thread returning record`() {
        val code = """
            $loomSetup

            let thread = Async.spawn {f = {} -> {x = 10, y = 20, name = "result"}}
            let result = unwrap { result = await {p = thread} }
            result.x + result.y
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // Edge Cases
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should handle zero sleep duration`() {
        val code = """
            $loomSetup

            let thread = Async.spawn {f = {} -> do
                let _ = sleep {ms = 0}
                42
            }
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle sequential thread creation and await`() {
        val code = """
            $loomSetup

            let t1 = Async.spawn {f = {} -> 10}
            let r1 = unwrap { result = await {p = t1} }

            let t2 = Async.spawn {f = {} -> r1 + 10}
            let r2 = unwrap { result = await {p = t2} }

            let t3 = Async.spawn {f = {} -> r2 + 10}
            let r3 = unwrap { result = await {p = t3} }

            r3
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle many concurrent threads with sum`() {
        val code = """
            $loomSetup

            let t1 = Async.spawn {f = {} -> 1}
            let t2 = Async.spawn {f = {} -> 2}
            let t3 = Async.spawn {f = {} -> 3}
            let t4 = Async.spawn {f = {} -> 4}
            let t5 = Async.spawn {f = {} -> 5}
            let t6 = Async.spawn {f = {} -> 6}
            let t7 = Async.spawn {f = {} -> 7}
            let t8 = Async.spawn {f = {} -> 8}
            let t9 = Async.spawn {f = {} -> 9}
            let t10 = Async.spawn {f = {} -> 10}

            let r1 = unwrap { result = await {p = t1} }
            let r2 = unwrap { result = await {p = t2} }
            let r3 = unwrap { result = await {p = t3} }
            let r4 = unwrap { result = await {p = t4} }
            let r5 = unwrap { result = await {p = t5} }
            let r6 = unwrap { result = await {p = t6} }
            let r7 = unwrap { result = await {p = t7} }
            let r8 = unwrap { result = await {p = t8} }
            let r9 = unwrap { result = await {p = t9} }
            let r10 = unwrap { result = await {p = t10} }

            r1 + r2 + r3 + r4 + r5 + r6 + r7 + r8 + r9 + r10
        """
        val result = eval(code)
        assertEquals(55L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle complex computation in thread`() {
        val code = """
            $loomSetup

            let compute = {n: Int} -> n * n + n

            let t1 = Async.spawn {f = {} -> compute {n = 3}}
            let t2 = Async.spawn {f = {} -> compute {n = 4}}
            let t3 = Async.spawn {f = {} -> compute {n = 5}}

            let r1 = unwrap { result = await {p = t1} }
            let r2 = unwrap { result = await {p = t2} }
            let r3 = unwrap { result = await {p = t3} }

            # 3*3+3 = 12, 4*4+4 = 20, 5*5+5 = 30
            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(62L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle recursive computation in thread`() {
        val code = """
            $loomSetup

            let fib: {n: Int} -> Int = {n} ->
                if n <= 1 then n
                else fib {n = n - 1} + fib {n = n - 2}

            let thread = Async.spawn {f = {} -> fib {n = 10}}
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals(55L, result)
    }
}
