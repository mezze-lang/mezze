package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for CountDownLatch implementation using actual Mezze code.
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Post-#170: all primitive ops are <Async>; tests use stdlib imports
    // and open a `perform SystemTime in perform LoomAsync in do` block.
    private val latchSetup = """
        use std::effects::async::latch::{
            latch, count_down, await_latch, await_latch_timeout, get_count
        }
        use std::effects::async::atom::{
            atom, deref, reset, swap, compare_and_set, swap_with, get_and_swap,
            add_watch, remove_watch, atom_validated, set_validator
        }
        use std::effects::async::{ Async, LoomAsync, await, sleep }
        use std::effects::time::{ Time, SystemTime }
        use std::effects::exception::{ Exception, CrashException, unwrap }

        perform SystemTime in perform LoomAsync in do
    """.trimIndent()

    private val atomSetup = ""
    private val loomSetup = ""

    // =============================================================================
    // Basic Latch Operations
    // =============================================================================

    @Test
    fun `test latch creation`() {
        val code = """
            $latchSetup

            let l = latch {count = 3}
            get_count {latch = l}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test latch with zero count`() {
        val code = """
            $latchSetup

            let l = latch {count = 0}
            get_count {latch = l}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test count_down decreases count`() {
        val code = """
            $latchSetup

            let l = latch {count = 3}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    @Test
    fun `test count_down to zero`() {
        val code = """
            $latchSetup

            let l = latch {count = 2}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test count_down past zero does not go negative`() {
        val code = """
            $latchSetup

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}  # Extra count_down
            let _ = count_down {latch = l}  # Another extra
            get_count {latch = l}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test await_latch returns immediately when count is zero`() {
        val code = """
            $latchSetup

            let l = latch {count = 0}
            let _ = await_latch {latch = l}
            True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test await_latch_timeout returns true when count is zero`() {
        val code = """
            $latchSetup

            let l = latch {count = 0}
            await_latch_timeout {latch = l, ms = 100}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test await_latch_timeout returns false on timeout`() {
        val code = """
            $latchSetup

            let l = latch {count = 1}
            # Don't count down, so it should timeout
            await_latch_timeout {latch = l, ms = 50}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // =============================================================================
    // Concurrent Tests with Loom
    // =============================================================================

    @Test
    fun `test latch synchronizes threads`() {
        val code = """
            $latchSetup
            $atomSetup
            $loomSetup

            let l = latch {count = 3}
            let counter = atom {val = 0}

            # Spawn 3 workers that each count down
            let p1 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                count_down {latch = l}
            }
            let p2 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                count_down {latch = l}
            }
            let p3 = Async.spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                count_down {latch = l}
            }

            # Wait for all workers to finish
            let _ = await_latch {latch = l}

            # Counter should be 3
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test latch blocks until count reaches zero`() {
        val code = """
            $latchSetup
            $atomSetup
            $loomSetup

            let l = latch {count = 1}
            let result = atom {val = 0}

            # Thread 1: Wait for latch, then set result
            let p1 = Async.spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = result, f = {current} -> 42}
            }

            # Give thread 1 time to start waiting
            let _ = sleep {ms = 20}

            # Result should still be 0 (thread 1 is blocked)
            let before = deref {atom = result}

            # Count down to release thread 1
            let _ = count_down {latch = l}

            let _ = unwrap { result = await {p = p1} }

            {before = before, after = deref {atom = result}}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(0L, result.getField("before"))
        assertEquals(42L, result.getField("after"))
    }

    @Test
    fun `test multiple threads waiting on same latch`() {
        val code = """
            $latchSetup
            $atomSetup
            $loomSetup

            let l = latch {count = 1}
            let counter = atom {val = 0}

            # Spawn 3 threads all waiting on the same latch
            let p1 = Async.spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let p2 = Async.spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let p3 = Async.spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = counter, f = {current} -> current + 1}
            }

            # Give threads time to start waiting
            let _ = sleep {ms = 20}

            # Release all threads at once
            let _ = count_down {latch = l}

            let _ = unwrap { result = await {p = p1} }
            let _ = unwrap { result = await {p = p2} }
            let _ = unwrap { result = await {p = p3} }

            # All threads should have incremented
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }
}
