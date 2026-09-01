package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for WaitGroup implementation using actual Mezze code.
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class WaitGroupTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Post-#170: all primitive ops are <Async>; tests use stdlib imports
    // and open a `perform SystemTime in perform LoomAsync in do` block.
    private val waitGroupSetup = """
        use std::effects::async::waitgroup::{
            wait_group, add, done, wait, wait_timeout, get_count_wg
        }
        use std::effects::async::atom::{
            atom, deref, reset, swap, compare_and_set, swap_with, get_and_swap,
            add_watch, remove_watch, atom_validated
        }
        use std::effects::async::{ Async, LoomAsync, await, sleep }
        use std::effects::time::{ Time, SystemTime }
        use std::effects::exception::{ Exception, CrashException, unwrap }

        perform SystemTime in perform LoomAsync in do
    """.trimIndent()

    private val atomSetup = ""
    private val loomSetup = ""

    // =============================================================================
    // Basic WaitGroup Operations
    // =============================================================================

    @Test
    fun `test wait_group creation`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            get_count_wg {wg = wg}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test add increases count`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 3}
            get_count_wg {wg = wg}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test done decreases count`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 3}
            let _ = done {wg = wg}
            get_count_wg {wg = wg}
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    @Test
    fun `test add and done sequence`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 2}
            let _ = done {wg = wg}
            let _ = done {wg = wg}
            get_count_wg {wg = wg}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test wait returns immediately when count is zero`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            let _ = wait {wg = wg}
            True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test wait_timeout returns true when count is zero`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            wait_timeout {wg = wg, ms = 100}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test wait_timeout returns false on timeout`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 1}
            # Don't call done, so it should timeout
            wait_timeout {wg = wg, ms = 50}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test incremental add`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 1}
            let _ = add {wg = wg, delta = 2}
            let _ = add {wg = wg, delta = 3}
            get_count_wg {wg = wg}
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    // =============================================================================
    // Concurrent Tests with Loom
    // =============================================================================

    @Test
    fun `test wait_group synchronizes dynamic tasks`() {
        val code = """
            $waitGroupSetup
            $atomSetup
            $loomSetup

            let wg = wait_group {}
            let counter = atom {val = 0}

            # Add 3 tasks
            let _ = add {wg = wg, delta = 3}

            # Spawn 3 workers
            let p1 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }
            let p2 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }
            let p3 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            # Wait for all workers to finish
            let _ = wait {wg = wg}

            # Counter should be 3
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test wait_group with incremental task adding`() {
        val code = """
            $waitGroupSetup
            $atomSetup
            $loomSetup

            let wg = wait_group {}
            let counter = atom {val = 0}

            # Add tasks one at a time
            let _ = add {wg = wg, delta = 1}
            let p1 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            let _ = add {wg = wg, delta = 1}
            let p2 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            let _ = add {wg = wg, delta = 1}
            let p3 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            # Wait for all
            let _ = wait {wg = wg}

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test wait_group blocks until all done`() {
        val code = """
            $waitGroupSetup
            $atomSetup
            $loomSetup

            let wg = wait_group {}
            let result = atom {val = 0}

            let _ = add {wg = wg, delta = 1}

            # Thread 1: Wait for waitgroup, then set result
            let p1 = Async.spawn {f = {} -> do
                let _ = wait {wg = wg}
                swap {atom = result, f = {current} -> 42}
            }

            # Give thread 1 time to start waiting
            let _ = sleep {ms = 20}

            # Result should still be 0 (thread 1 is blocked)
            let before = deref {atom = result}

            # Done to release thread 1
            let _ = done {wg = wg}

            let _ = perform CrashException in unwrap { result = await {p = p1} }

            {before = before, after = deref {atom = result}}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(0L, result.getField("before"))
        assertEquals(42L, result.getField("after"))
    }
}
