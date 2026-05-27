package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Mutex implementation using actual Mezze code.
 *
 * Mutexes provide mutual exclusion using Java's ReentrantLock.
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MutexTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Post-#170 atom+mutex: every primitive op is <Async>. Tests must
    // import from stdlib and open a `perform LoomAsync in do` block.
    private val mutexSetup = """
        use std::effects::concurrent::mutex::{
            mutex, lock, unlock, with_lock, try_lock, is_held_by_current_thread
        }
        use std::effects::concurrent::atom::{
            atom, deref, reset, swap, compare_and_set, swap_with, get_and_swap,
            add_watch, remove_watch, atom_validated, set_validator
        }
        use std::effects::async::{ Async, LoomAsync }
        use std::effects::exception::{ Exception, CrashException, unwrap }

        perform LoomAsync in do
    """.trimIndent()

    private val atomSetup = ""
    private val loomSetup = ""

    // =============================================================================
    // Basic Mutex Operations
    // =============================================================================

    @Test
    fun `test mutex creation`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            42  # Just verify mutex can be created
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_lock executes function`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            with_lock {mutex = m, f = {} -> 42}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_lock returns function result`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            let result = with_lock {mutex = m, f = {} -> "hello"}
            result
        """
        val result = eval(code)
        assertEquals("hello", result)
    }

    @Test
    fun `test manual lock and unlock`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            let _ = lock {mutex = m}
            let held = is_held_by_current_thread {mutex = m}
            let _ = unlock {mutex = m}
            held
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test is_held_by_current_thread when not held`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            is_held_by_current_thread {mutex = m}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test try_lock succeeds on unlocked mutex`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            let acquired = try_lock {mutex = m}
            let _ = if acquired then unlock {mutex = m} else {}
            acquired
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test reentrant lock - same thread can lock twice`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            let _ = lock {mutex = m}
            let _ = lock {mutex = m}  # Reentrant - should succeed
            let held = is_held_by_current_thread {mutex = m}
            let _ = unlock {mutex = m}
            let _ = unlock {mutex = m}
            held
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // Concurrent Tests with Loom
    // =============================================================================

    @Test
    fun `test mutex protects shared state`() {
        val code = """
            $mutexSetup
            $atomSetup
            $loomSetup

            let m = mutex {}
            let counter = atom {value = 0}

            # Function that increments counter 5 times with mutex protection
            let increment_5 = {} -> do
                with_lock {mutex = m, f = {} -> do
                    let _ = reset {atom = counter, value = deref {atom = counter} + 1}
                    let _ = reset {atom = counter, value = deref {atom = counter} + 1}
                    let _ = reset {atom = counter, value = deref {atom = counter} + 1}
                    let _ = reset {atom = counter, value = deref {atom = counter} + 1}
                    reset {atom = counter, value = deref {atom = counter} + 1}
                }

            # Spawn 3 threads
            let p1 = Async.spawn {f = increment_5}
            let p2 = Async.spawn {f = increment_5}
            let p3 = Async.spawn {f = increment_5}

            let _ = unwrap { result = Async.await {p = p1} }
            let _ = unwrap { result = Async.await {p = p2} }
            let _ = unwrap { result = Async.await {p = p3} }

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test with_lock releases lock after completion`() {
        val code = """
            $mutexSetup
            $loomSetup

            let m = mutex {}

            # First thread takes lock, does work, releases
            let p1 = Async.spawn {f = {} ->
                with_lock {mutex = m, f = {} ->
                    Async.sleep {ms = 10}
                }
            }

            # Wait for first to finish
            let _ = unwrap { result = Async.await {p = p1} }

            # Second thread should be able to acquire lock
            let p2 = Async.spawn {f = {} ->
                with_lock {mutex = m, f = {} -> 42}
            }

            unwrap { result = Async.await {p = p2} }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test try_lock fails when held by another thread`() {
        val code = """
            $mutexSetup
            $atomSetup
            $loomSetup

            let m = mutex {}
            let try_result = atom {value = True}

            # Thread 1 holds lock for a while
            let p1 = Async.spawn {f = {} -> do
                let _ = lock {mutex = m}
                let _ = Async.sleep {ms = 100}
                unlock {mutex = m}
            }

            # Give thread 1 time to acquire
            let _ = Async.sleep {ms = 20}

            # Thread 2 tries to acquire - should fail
            let p2 = Async.spawn {f = {} -> do
                let acquired = try_lock {mutex = m}
                let _ = reset {atom = try_result, value = acquired}
                if acquired then unlock {mutex = m} else {}
            }

            let _ = unwrap { result = Async.await {p = p2} }
            let _ = unwrap { result = Async.await {p = p1} }

            deref {atom = try_result}
        """
        val result = eval(code)
        assertEquals(false, result)
    }
}
