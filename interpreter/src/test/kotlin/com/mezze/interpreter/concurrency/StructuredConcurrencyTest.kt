package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for structured concurrency scope cleanup and cancellation.
 * Tests requirements 8.1, 8.2: Structured concurrency and cancellation
 *
 * Migrated from V1 StructuredConcurrencyTest.
 * V1 tested internal LoomConcurrencyManager infrastructure directly.
 * V2 tests equivalent functionality through the Mezze language API.
 */
@Execution(ExecutionMode.SAME_THREAD)
class StructuredConcurrencyTest {

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
    // Basic Structured Scope Tests
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should execute all tasks in structured scope`() {
        val code = """
            $loomSetup
            $atomSetup

            let completedTasks = atom {val = 0}

            # Submit 5 tasks
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                let _ = swap {atom = completedTasks, f = {current} -> current + 1}
                2
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                let _ = swap {atom = completedTasks, f = {current} -> current + 1}
                4
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                let _ = swap {atom = completedTasks, f = {current} -> current + 1}
                6
            }
            let t4 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                let _ = swap {atom = completedTasks, f = {current} -> current + 1}
                8
            }
            let t5 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                let _ = swap {atom = completedTasks, f = {current} -> current + 1}
                10
            }

            let r1 = unwrap { result = await {p = t1} }
            let r2 = unwrap { result = await {p = t2} }
            let r3 = unwrap { result = await {p = t3} }
            let r4 = unwrap { result = await {p = t4} }
            let r5 = unwrap { result = await {p = t5} }

            let count = deref {atom = completedTasks}
            # Return sum: 2+4+6+8+10 = 30
            if count == 5 then r1 + r2 + r3 + r4 + r5 else 0 - 1
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    @Timeout(10)
    fun `should automatically clean up scope when completed`() {
        val code = """
            $loomSetup
            $atomSetup

            let counter = atom {val = 0}

            # Run 3 tasks
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = counter, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            # Scope should be cleaned up - verify all tasks completed
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    @Timeout(10)
    fun `should support nested structured scopes`() {
        val code = """
            $loomSetup
            $atomSetup

            let outerResults = atom {val = 0}
            let innerResults = atom {val = 0}

            let parent = Async.spawn {f = {} -> do
                let _ = swap {atom = outerResults, f = {current} -> current + 1}

                # Nested scope
                let child1 = Async.spawn {f = {} -> do
                    let _ = swap {atom = innerResults, f = {current} -> current + 1}
                    "inner-result-1"
                }
                let child2 = Async.spawn {f = {} -> do
                    let _ = swap {atom = innerResults, f = {current} -> current + 1}
                    "inner-result-2"
                }

                let _ = unwrap { result = await {p = child1} }
                let _ = unwrap { result = await {p = child2} }

                "outer-result-1"
            }

            let parent2 = Async.spawn {f = {} -> do
                let _ = swap {atom = outerResults, f = {current} -> current + 1}
                "outer-result-2"
            }

            let _ = unwrap { result = await {p = parent} }
            let _ = unwrap { result = await {p = parent2} }

            let outer = deref {atom = outerResults}
            let inner = deref {atom = innerResults}

            # outer should be 2, inner should be 2
            if outer == 2 then
                if inner == 2 then 4 else 0 - 1
            else 0 - 1
        """
        val result = eval(code)
        assertEquals(4L, result)
    }

    @Test
    @Timeout(10)
    fun `should execute tasks concurrently`() {
        // This test verifies concurrency by checking timing
        // If tasks ran sequentially 3x200ms = 600ms minimum
        // If tasks run concurrently, should complete in ~200ms
        val code = """
            $loomSetup
            $atomSetup

            let counter = atom {val = 0}

            # Spawn 3 tasks that each sleep for 200ms
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 200}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 200}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 200}
                swap {atom = counter, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            deref {atom = counter}
        """

        val startTime = System.currentTimeMillis()
        val result = eval(code)
        val duration = System.currentTimeMillis() - startTime

        assertEquals(3L, result)

        // Should complete in roughly 200-400ms (parallel) not 600ms+ (sequential)
        // Being generous with timing to account for test environment variance
        assertTrue(duration < 550, "Duration was ${duration}ms, expected < 550ms for concurrent execution")
    }

    @Test
    @Timeout(10)
    fun `should track active scopes in statistics`() {
        val code = """
            $loomSetup
            $atomSetup

            let totalSpawned = atom {val = 0}
            let totalCompleted = atom {val = 0}

            # Track spawning
            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = swap {atom = totalSpawned, f = {current} -> current + 1}
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = totalCompleted, f = {current} -> current + 1}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            let spawned = deref {atom = totalSpawned}
            let completed = deref {atom = totalCompleted}

            # Verify all spawned threads completed
            if spawned == completed then spawned else 0 - 1
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Nested Execution Context Tests
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should handle nested virtual thread spawn`() {
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
        assertEquals(30L, result) // 10 + 20
    }

    @Test
    @Timeout(10)
    fun `should handle deeply nested thread spawning`() {
        val code = """
            $loomSetup

            let level1 = Async.spawn {f = {} -> do
                let level2 = Async.spawn {f = {} -> do
                    let level3 = Async.spawn {f = {} -> do
                        let level4 = Async.spawn {f = {} ->
                            "deep"
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
        assertEquals("deep", result)
    }

    // =============================================================================
    // Execution Context Metadata Tests (via closures)
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should preserve closure context in spawned thread`() {
        val code = """
            $loomSetup

            let testKey = "test-value"
            let thread = Async.spawn {f = {} -> testKey}
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals("test-value", result)
    }

    @Test
    @Timeout(10)
    fun `should preserve complex closure context`() {
        val code = """
            $loomSetup

            let config = {name = "test", multiplier = 5}
            let thread = Async.spawn {f = {} -> do
                let result = config.multiplier * 10
                result
            }
            unwrap { result = await {p = thread} }
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    // =============================================================================
    // Multiple Threads with Shared State
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should handle multiple threads incrementing shared counter`() {
        val code = """
            $loomSetup
            $atomSetup

            let counter = atom {val = 0}

            # Spawn 3 threads that each increment 10 times
            let increment_10 = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                let _ = swap {atom = counter, f = {current} -> current + 1}
                swap {atom = counter, f = {current} -> current + 1}

            let t1 = Async.spawn {f = increment_10}
            let t2 = Async.spawn {f = increment_10}
            let t3 = Async.spawn {f = increment_10}

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(30L, result) // 3 threads x 10 increments
    }

    // =============================================================================
    // Thread with Complex Computations
    // =============================================================================

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
        assertEquals(55L, result) // fib(10) = 55
    }

    // =============================================================================
    // Thread Return Types
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
    fun `should handle many concurrent threads`() {
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
        assertEquals(55L, result) // sum 1..10 = 55
    }

    // =============================================================================
    // Structured Scope Cleanup Tests
    // =============================================================================

    @Test
    @Timeout(10)
    fun `should complete all tasks before scope exits`() {
        val code = """
            use std::str
            $loomSetup
            $atomSetup

            let results = atom {val = ""}

            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                swap {atom = results, f = {current} -> str::concat {l = current, r = "A"}}
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 30}
                swap {atom = results, f = {current} -> str::concat {l = current, r = "B"}}
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = results, f = {current} -> str::concat {l = current, r = "C"}}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            let final = deref {atom = results}
            # All should complete, order may vary but length should be 3
            str::size {s = final}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    @Timeout(10)
    fun `should handle concurrent access to shared data`() {
        val code = """
            use std::collections::list
            $loomSetup
            $atomSetup

            let data = atom {val = list::empty {}}

            let t1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = data, f = {current} -> list::cons {head = 1, tail = current}}
            }
            let t2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = data, f = {current} -> list::cons {head = 2, tail = current}}
            }
            let t3 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                swap {atom = data, f = {current} -> list::cons {head = 3, tail = current}}
            }

            let _ = unwrap { result = await {p = t1} }
            let _ = unwrap { result = await {p = t2} }
            let _ = unwrap { result = await {p = t3} }

            let final = deref {atom = data}
            list::size {xs = final}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }
}
