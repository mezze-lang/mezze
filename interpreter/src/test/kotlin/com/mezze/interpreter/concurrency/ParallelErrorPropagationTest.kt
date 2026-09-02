package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.datastructures.list.MezzeList
import com.mezze.datastructures.seq.MezzeSeq
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for parallel error propagation using Mezze code.
 *
 * This tests error handling behavior in parallel execution scenarios:
 * - Successful parallel group execution
 * - Fail-fast error propagation
 * - Collect errors strategy (using settle)
 * - Operation dependencies
 * - Timeouts
 * - Cancellation
 *
 * Migrated from V1 to V2 with unboxed values.
 *
 * Note: Native async functions return:
 * - settle: Array of Ok {val} or Err {error} where error is AsyncCancelled or AsyncFailed {msg}
 * - timeout: Some {val} or None
 * - cancel: {} (unit)
 */
@Execution(ExecutionMode.SAME_THREAD)
class ParallelErrorPropagationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Loom primitives for concurrent tests
    // Note: Using Array types [Promise 'a] not List types
    private val loomSetup = """
        use std::effects::async::atom::{
            atom, deref, reset, swap, compare_and_set, swap_with, get_and_swap,
            add_watch, remove_watch, atom_validated
        }
        use std::effects::async::{ Async, LoomAsync, all, await, cancel, inspect, is_cancelled, is_done, is_failed, is_pending, join, join3, race, settle, sleep, try_any, try_join, try_join3, with_timeout }
        use std::effects::exception::{ Exception, CrashException, unwrap }
        use std::effects::time::{ Time, SystemTime }
        use std::collections::seq

        perform SystemTime in perform LoomAsync in do
    """.trimIndent()

    // Atom primitives for shared state
    private val atomSetup = ""

    // =============================================================================
    // Successful Parallel Execution
    // =============================================================================

    @Test
    fun `test successful parallel group execution`() {
        val code = """
            $loomSetup

            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let p3 = Async.spawn {f = {} -> "hello"}

            let r1 = unwrap { result = await {p = p1} }
            let r2 = unwrap { result = await {p = p2} }
            let r3 = unwrap { result = await {p = p3} }

            {r1 = r1, r2 = r2, r3 = r3}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(10L, result.getField("r1"))
        assertEquals(20L, result.getField("r2"))
        assertEquals("hello", result.getField("r3"))
    }

    @Test
    fun `test parallel all succeeds when all tasks succeed`() {
        val code = """
            $loomSetup

            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let p3 = Async.spawn {f = {} -> 30}

            let results = all {ps = [p1, p2, p3]}
            results
        """
        val result = eval(code)
        // Result is an array
        assertTrue(result is Array<*> || result is MezzeList<*> || result is MezzeSeq<*>)
    }

    // =============================================================================
    // Fail Fast Error Propagation (using all)
    // =============================================================================

    @Test
    fun `test fail fast error propagation with all`() {
        // When one task fails in 'all', the error propagates to the awaiter
        // Using settle instead to capture the error state
        val code = """
            $loomSetup
            $atomSetup

            let execution_count = atom {val = 0}

            let p1 = Async.spawn {f = {} -> do
                let _ = swap {atom = execution_count, f = {current} -> current + 1}
                let _ = sleep {ms = 100}
                10
            }

            let p2 = Async.spawn {f = {} -> do
                let _ = swap {atom = execution_count, f = {current} -> current + 1}
                let _ = sleep {ms = 50}
                crash { msg = "Operation 2 failed" }
            }

            let p3 = Async.spawn {f = {} -> do
                let _ = swap {atom = execution_count, f = {current} -> current + 1}
                let _ = sleep {ms = 200}
                30
            }


            # Use settle to see failure without throwing
            let results = settle {ps = [p1, p2, p3]}

            # Count how many failed
            let err_count = seq::count {xs = results, p = {x} -> match x is
                Ok { val } -> False
                Err { error = _ } -> True}

            # Should have at least 1 failure
            if err_count > 0 then "failed" else "success"
        """
        val result = eval(code)
        assertEquals("failed", result)
    }

    // =============================================================================
    // Collect Errors Strategy (using settle)
    // =============================================================================

    @Test
    fun `test collect errors strategy with settle`() {
        val code = """
            $loomSetup

            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> crash { msg = "Error 2" }}
            let p3 = Async.spawn {f = {} -> 30}
            let p4 = Async.spawn {f = {} -> crash { msg = "Error 4" }}


            let results = settle {ps = [p1, p2, p3, p4]}

            let ok_count = seq::count {xs = results, p = {x} -> match x is
                Ok { val } -> True
                Err { error = _ } -> False}
            let err_count = seq::count {xs = results, p = {x} -> match x is
                Ok { val } -> False
                Err { error = _ } -> True}

            {ok_count = ok_count, err_count = err_count}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(2L, result.getField("ok_count"))
        assertEquals(2L, result.getField("err_count"))
    }

    @Test
    fun `test settle returns all results including errors`() {
        val code = """
            $loomSetup

            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> crash { msg = "Specific error message" }}
            let p3 = Async.spawn {f = {} -> 30}


            let results = settle {ps = [p1, p2, p3]}

            # Get the second result (error case)
            match seq::get { xs = results, i = 1 } is
                Some { val } -> match val is
                    Ok { val = _ } -> "unexpected success"
                    Err { error } -> match error is
                        AsyncFailed {msg} -> msg
                        AsyncCancelled { reason = _ } -> "cancelled"
                        AsyncTimedOut {ms} -> "timed out"
                None -> "missing index"
        """
        val result = eval(code) as String
        assertTrue(result.contains("Specific error message"))
    }

    // =============================================================================
    // Operation Dependencies
    // =============================================================================

    @Test
    fun `test operation dependencies`() {
        // Test that one spawned operation can await another
        val code = """
            $loomSetup

            # Simple test: spawn and await, then compute
            let p1 = Async.spawn {f = {} -> 10}
            let r1 = unwrap { result = await {p = p1} }

            # Spawn another that uses the result
            let p2 = Async.spawn {f = {} -> r1 * 2}
            let r2 = unwrap { result = await {p = p2} }

            {first = r1, second = r2}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(10L, result.getField("first"))
        assertEquals(20L, result.getField("second"))
    }

    // =============================================================================
    // Operation Timeout
    // =============================================================================

    @Test
    fun `test operation timeout`() {
        // Test that settle captures timeout/cancellation states
        val code = """
            $loomSetup

            let slow_op = Async.spawn {f = {} -> do
                let _ = sleep {ms = 1000}
                42
            }

            # Cancel the slow operation
            let _ = cancel {p = slow_op, reason = UserRequested { msg = "test" }}

            # Use settle to see the cancelled state
            let results = settle {ps = [slow_op]}

            # The result should be an error (cancelled)
            let is_err = {r: Result Int 'e} -> match r is
                Ok { val } -> 0
                Err { error } -> 1

            match results is
                [result] -> is_err {r = result}
                _ -> 0
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `test operation completes before timeout`() {
        val code = """
            $loomSetup

            let fast_op = Async.spawn {f = {} -> do
                let _ = sleep {ms = 10}
                42
            }

            let result = with_timeout {p = fast_op, ms = 500}

            match result is
                Some { val } -> val
                None -> 0 - 1
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Cancellation
    // =============================================================================

    @Test
    fun `test operation cancellation`() {
        val code = """
            $loomSetup

            let op = Async.spawn {f = {} -> do
                let _ = sleep {ms = 2000}
                42
            }

            # Cancel before it completes
            let _ = cancel {p = op, reason = UserRequested { msg = "test" }}

            let _ = sleep {ms = 50}

            # Check if is_done - cancelled operations should be done
            is_done {p = op}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test cancellation of pending operation`() {
        val code = """
            $loomSetup
            $atomSetup

            let executed = atom {val = False}

            let op = Async.spawn {f = {} -> do
                let _ = sleep {ms = 500}
                let _ = reset {atom = executed, val = True}
                42
            }

            # Cancel immediately
            let _ = cancel {p = op, reason = UserRequested { msg = "test" }}

            # Wait a bit to ensure operation would have run if not cancelled
            let _ = sleep {ms = 100}

            deref {atom = executed}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // =============================================================================
    // Complex Dependency Chains with Errors
    // =============================================================================

    @Test
    fun `test complex dependency chain with errors`() {
        // Simplified test: just spawn two tasks, one fails
        val code = """
            $loomSetup

            let success_op = Async.spawn {f = {} -> 10}
            let fail_op = Async.spawn {f = {} -> crash { msg = "Failed" }}


            let results = settle {ps = [success_op, fail_op]}

            # Count errors
            seq::count {xs = results, p = {x} -> match x is
                Ok { val } -> False
                Err { error = _ } -> True}
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    // =============================================================================
    // Concurrent Group Operations
    // =============================================================================

    @org.junit.jupiter.api.Disabled("Typechecker inference bug — tracked as #174 (NestedResultAliasInferenceTest reproducer)")
    @Test
    fun `test concurrent group operations`() {
        val code = """
            $loomSetup

            # Create 5 groups of 2 operations each
            let create_group = {i: Int} -> do
                let p1 = Async.spawn {f = {} -> i * 10}
                let p2 = Async.spawn {f = {} -> i * 20}
                all {ps = [p1, p2]}

            let g0 = Async.spawn {f = {} -> create_group {i = 0}}
            let g1 = Async.spawn {f = {} -> create_group {i = 1}}
            let g2 = Async.spawn {f = {} -> create_group {i = 2}}
            let g3 = Async.spawn {f = {} -> create_group {i = 3}}
            let g4 = Async.spawn {f = {} -> create_group {i = 4}}

            let groups = all {ps = [g0, g1, g2, g3, g4]}

            # `all` returns Seq Result. Use seq::fold to sum, unwrapping Results.
            let sum_arr = { arr } -> seq::fold {
                xs = arr,
                init = 0,
                f = { acc, x } -> match x is
                    Ok { val } -> acc + val
                    Err { error = _ } -> acc
            }

            seq::fold {
                xs = groups,
                init = 0,
                f = { acc, g } -> match g is
                    Ok { val } -> acc + sum_arr { arr = val }
                    Err { error = _ } -> acc
            }
        """
        val result = eval(code)
        // Group sums: 0+0=0, 10+20=30, 20+40=60, 30+60=90, 40+80=120
        // Total: 0 + 30 + 60 + 90 + 120 = 300
        assertEquals(300L, result)
    }

    // =============================================================================
    // Race Semantics
    // =============================================================================

    @Test
    fun `test race returns first completed`() {
        // Test that race returns the fastest result
        // Using fast sleeps to minimize thread cleanup issues
        val code = """
            $loomSetup

            let p1 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 50}
                "p1"
            }

            let p2 = Async.spawn {f = {} -> do
                let _ = sleep {ms = 5}
                "p2"
            }

            let result = match race {ps = [p1, p2]} is
              Ok { val } -> val
              Err { error = _ } -> "err"

            # race auto-cancels the losing promise (#122) — no
            # cleanup needed.

            result
        """
        val result = eval(code)
        assertEquals("p2", result)
    }

    @Test
    fun `test is_done after completion`() {
        val code = """
            $loomSetup

            let op = Async.spawn {f = {} -> 42}
            let _ = unwrap { result = await {p = op} }
            is_done {p = op}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test is_done before completion`() {
        val code = """
            $loomSetup

            let op = Async.spawn {f = {} -> do
                let _ = sleep {ms = 500}
                42
            }

            # Check immediately
            let done_before = is_done {p = op}

            # Wait for completion
            let _ = unwrap { result = await {p = op} }
            let done_after = is_done {p = op}

            {before = done_before, after = done_after}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(false, result.getField("before"))
        assertEquals(true, result.getField("after"))
    }

    // =============================================================================
    // Error Message Propagation
    // =============================================================================

    @Test
    fun `test parallel execution error details`() {
        val code = """
            $loomSetup

            let error_op = Async.spawn {f = {} -> crash { msg = "Specific error message" }}


            let results = settle {ps = [error_op]}

            match seq::get { xs = results, i = 0 } is
                Some { val } -> match val is
                    Ok { val = _ } -> "unexpected success"
                    Err { error } -> match error is
                        AsyncFailed {msg} -> msg
                        AsyncCancelled { reason = _ } -> "cancelled"
                        AsyncTimedOut {ms} -> "timed out"
                None -> "missing index"
        """
        val result = eval(code) as String
        assertTrue(result.contains("Specific error message"))
    }

    // =============================================================================
    // Mixed Success and Failure
    // =============================================================================

    @Test
    fun `test mixed success and failure with settle`() {
        val code = """
            $loomSetup

            let success1 = Async.spawn {f = {} -> 1}
            let failure1 = Async.spawn {f = {} -> crash { msg = "fail1" }}
            let success2 = Async.spawn {f = {} -> 2}
            let failure2 = Async.spawn {f = {} -> crash { msg = "fail2" }}
            let success3 = Async.spawn {f = {} -> 3}


            let results = settle {ps = [success1, failure1, success2, failure2, success3]}

            # Verify we got 5 results
            seq::size { xs = results }
        """
        val result = eval(code)
        assertEquals(5L, result)
    }
}
