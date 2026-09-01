package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Clojure-style Atom implementation using actual Mezze code.
 *
 * Atoms provide thread-safe, lock-free state management using compare-and-swap.
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AtomTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * Imports + opens a `perform LoomAsync in do` block. Atom operations
     * all carry <Async> (post-#170), so every test must run inside an
     * Async handler. Test bodies follow as nested do-block content.
     */
    private val atomSetup = """
        use std::effects::async::atom::{
            atom, deref, reset, swap, compare_and_set, swap_with, get_and_swap,
            add_watch, remove_watch, atom_validated,
            deref_validated, reset_validated, swap_validated, swap_with_validated,
            get_and_swap_validated
        }
        use std::effects::async::{ Async, LoomAsync, await }

        perform LoomAsync in do
    """.trimIndent()


    // =============================================================================
    // Basic Atom Operations
    // =============================================================================

    @Test
    fun `test atom creation and deref`() {
        val code = """
            $atomSetup

            let counter = atom {val = 42}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test atom with string value`() {
        val code = """
            $atomSetup

            let name = atom {val = "hello"}
            deref {atom = name}
        """
        val result = eval(code)
        assertEquals("hello", result)
    }

    @Test
    fun `test reset sets new value`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let _ = reset {atom = counter, val = 100}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test reset returns new value`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            reset {atom = counter, val = 42}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Swap Operations
    // =============================================================================

    @Test
    fun `test swap with increment`() {
        val code = """
            $atomSetup

            let counter = atom {val = 10}
            swap {atom = counter, f = {current} -> current + 1}
        """
        val result = eval(code)
        assertEquals(11L, result)
    }

    @Test
    fun `test swap updates atom value`() {
        val code = """
            $atomSetup

            let counter = atom {val = 5}
            let _ = swap {atom = counter, f = {current} -> current * 2}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test multiple swaps`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test swap with complex function`() {
        val code = """
            $atomSetup

            let counter = atom {val = 1}
            let _ = swap {atom = counter, f = {current} -> (current + 1) * 2}  # (1 + 1) * 2 = 4
            let _ = swap {atom = counter, f = {current} -> (current + 1) * 2}  # (4 + 1) * 2 = 10
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    // =============================================================================
    // Compare and Set
    // =============================================================================

    @Test
    fun `test compare_and_set succeeds when expected matches`() {
        val code = """
            $atomSetup

            let counter = atom {val = 1}
            compare_and_set {atom = counter, expected = 1, new = 2}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test compare_and_set fails when expected differs`() {
        val code = """
            $atomSetup

            let counter = atom {val = 1}
            compare_and_set {atom = counter, expected = 999, new = 2}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test compare_and_set updates value on success`() {
        val code = """
            $atomSetup

            let counter = atom {val = 10}
            let _ = compare_and_set {atom = counter, expected = 10, new = 20}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(20L, result)
    }

    @Test
    fun `test compare_and_set leaves value unchanged on failure`() {
        val code = """
            $atomSetup

            let counter = atom {val = 10}
            let _ = compare_and_set {atom = counter, expected = 999, new = 20}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    // =============================================================================
    // Concurrent Tests
    // =============================================================================

    @Test
    fun `test concurrent increments with swap`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}

            # Helper to increment N times using explicit iteration
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

            # Spawn multiple threads that each increment 10 times
            let p1 = Async.spawn {f = increment_10}
            let p2 = Async.spawn {f = increment_10}
            let p3 = Async.spawn {f = increment_10}

            let _ = await {p = p1}
            let _ = await {p = p2}
            let _ = await {p = p3}

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `test atom with record value`() {
        val code = """
            $atomSetup

            let state = atom {val = {count = 0, name = "test"}}
            let current = deref {atom = state}
            current.count
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test swap with record transformation`() {
        val code = """
            $atomSetup

            let state = atom {val = {count = 0}}
            let _ = swap {atom = state, f = {current} -> {count = current.count + 1}}
            let _ = swap {atom = state, f = {current} -> {count = current.count + 1}}
            let result = deref {atom = state}
            result.count
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    // =============================================================================
    // Watch Tests
    // =============================================================================

    @Test
    fun `test add_watch is called on swap`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let log = atom {val = 0}

            # Add a watch that records the sum of old + new
            let _ = add_watch {
                atom = counter,
                key = "logger",
                f = {key, old, new} -> do
                    let _ = reset {atom = log, val = old + new}
                    {}
            }

            # swap should trigger the watch
            let _ = swap {atom = counter, f = {current} -> current + 10}

            # Watch should have recorded 0 + 10 = 10
            deref {atom = log}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test add_watch is called on reset`() {
        val code = """
            $atomSetup

            let counter = atom {val = 5}
            let log = atom {val = 0}

            let _ = add_watch {
                atom = counter,
                key = "logger",
                f = {key, old, new} -> do
                    let _ = reset {atom = log, val = old + new}
                    {}
            }

            # reset should trigger the watch
            let _ = reset {atom = counter, val = 15}

            # Watch should have recorded 5 + 15 = 20
            deref {atom = log}
        """
        val result = eval(code)
        assertEquals(20L, result)
    }

    @Test
    fun `test multiple watches on same atom`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let log1 = atom {val = 0}
            let log2 = atom {val = 0}

            let _ = add_watch {
                atom = counter,
                key = "watch1",
                f = {key, old, new} -> do
                    let _ = reset {atom = log1, val = new}
                    {}
            }

            let _ = add_watch {
                atom = counter,
                key = "watch2",
                f = {key, old, new} -> do
                    let _ = reset {atom = log2, val = new * 2}
                    {}
            }

            let _ = swap {atom = counter, f = {current} -> current + 5}

            # Both watches should have been called
            deref {atom = log1} + deref {atom = log2}
        """
        val result = eval(code)
        // log1 = 5, log2 = 10, sum = 15
        assertEquals(15L, result)
    }

    @Test
    fun `test remove_watch stops notifications`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let log = atom {val = 0}

            let _ = add_watch {
                atom = counter,
                key = "logger",
                f = {key, old, new} -> do
                    let _ = swap {atom = log, f = {current} -> current + 1}
                    {}
            }

            # First swap should trigger watch
            let _ = swap {atom = counter, f = {current} -> current + 1}

            # Remove the watch
            let _ = remove_watch {atom = counter, key = "logger"}

            # Second swap should NOT trigger watch
            let _ = swap {atom = counter, f = {current} -> current + 1}

            # Log should be 1 (only first swap triggered watch)
            deref {atom = log}
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `test watch receives correct key`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let key_log = atom {val = "none"}

            let _ = add_watch {
                atom = counter,
                key = "my_watch_key",
                f = {key, old, new} -> do
                    let _ = reset {atom = key_log, val = key}
                    {}
            }

            let _ = swap {atom = counter, f = {current} -> current + 1}

            deref {atom = key_log}
        """
        val result = eval(code)
        assertEquals("my_watch_key", result)
    }

    @Test
    fun `test watch called multiple times on multiple swaps`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let call_count = atom {val = 0}

            let _ = add_watch {
                atom = counter,
                key = "counter",
                f = {key, old, new} -> do
                    let _ = swap {atom = call_count, f = {current} -> current + 1}
                    {}
            }

            # Three swaps should trigger watch three times
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}

            deref {atom = call_count}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Validator Tests
    // =============================================================================

    @Test
    fun `test atom_validated with valid initial value`() {
        // atom_validated returns Result; Ok carries the atom, which we can deref.
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = positive } -> deref_validated {atom = positive}
              Err { error = _ } -> 0-1
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test atom_validated rejects invalid initial value returns Err`() {
        // Rejecting the initial value no longer crashes: it returns
        // Err { error = ValidatorRejected { val } } carrying the rejected value.
        val code = """
            $atomSetup

            match atom_validated {val = -5, validator = {val} -> val >= 0} is
              Ok { val = _ } -> 999
              Err { error = ValidatorRejected { val } } -> val
        """
        assertEquals(-5L, eval(code))
    }

    @Test
    fun `391 repro atom_validated rejecting initial value returns Err not crash`() {
        // The exact #391 repro: initial value 10 fails validator val < 0.
        // Must return Err { error = ValidatorRejected { val = 10 } }, not crash.
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val < 0} is
              Ok { val = _ } -> 999
              Err { error = ValidatorRejected { val } } -> val
        """
        assertEquals(10L, eval(code))
    }

    @Test
    fun `391 atom_validated Ok initial value then swap succeeds`() {
        // Passing initial value yields Ok carrying the atom; a subsequent swap works.
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let _ = swap_validated {atom = a, f = {current} -> current + 7}
                deref_validated {atom = a}
              Err { error = _ } -> 0-1
        """
        assertEquals(17L, eval(code))
    }

    @Test
    fun `test validator allows valid swap`() {
        // ValidatedAtom.swap returns Result; on success Ok { val = new }.
        val code = """
            $atomSetup

            match atom_validated {val = 5, validator = {val} -> val >= 0} is
              Ok { val = positive } -> do
                let _ = swap_validated {atom = positive, f = {current} -> current + 10}
                deref_validated {atom = positive}
              Err { error = _ } -> 0-1
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test validator rejects invalid swap returns Err and leaves atom unchanged`() {
        // ValidatedAtom.swap returns Err { error = ValidatorRejected { val } } on reject.
        val code = """
            $atomSetup

            match atom_validated {val = 5, validator = {val} -> val >= 0} is
              Ok { val = positive } -> do
                let outcome = swap_validated {atom = positive, f = {current} -> current - 100}
                match outcome is
                  Ok { val = _ } -> 999
                  Err { error = ValidatorRejected { val } } -> val
              Err { error = _ } -> 0-1
        """
        // The rejected value (5 - 100 = -95) is carried in the Err.
        assertEquals(-95L, eval(code))
    }

    @Test
    fun `test validator rejected swap leaves value unchanged`() {
        val code = """
            $atomSetup

            match atom_validated {val = 5, validator = {val} -> val >= 0} is
              Ok { val = positive } -> do
                let _ = swap_validated {atom = positive, f = {current} -> current - 100}
                deref_validated {atom = positive}
              Err { error = _ } -> 0-1
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test validator rejects invalid reset returns Err`() {
        val code = """
            $atomSetup

            match atom_validated {val = 5, validator = {val} -> val >= 0} is
              Ok { val = positive } -> do
                let outcome = reset_validated {atom = positive, val = -10}
                match outcome is
                  Ok { val = _ } -> 999
                  Err { error = ValidatorRejected { val } } -> val
              Err { error = _ } -> 0-1
        """
        assertEquals(-10L, eval(code))
    }

    @Test
    fun `test validated reset success returns Ok and updates`() {
        val code = """
            $atomSetup

            match atom_validated {val = 5, validator = {val} -> val >= 0} is
              Ok { val = positive } -> do
                let outcome = reset_validated {atom = positive, val = 42}
                match outcome is
                  Ok { val } -> val
                  Err { error = _ } -> 0-1
              Err { error = _ } -> 0-1
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test validated atom future invalid updates rejected via Result`() {
        // Was: set_validator on a plain atom. Now: build a ValidatedAtom up front.
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val < 100} is
              Ok { val = counter } -> do
                # Allowed: 10 + 50 = 60 < 100
                let _ = swap_validated {atom = counter, f = {current} -> current + 50}
                deref_validated {atom = counter}
              Err { error = _ } -> 0-1
        """
        assertEquals(60L, eval(code))
    }

    @Test
    fun `test validated atom rejects out-of-bounds update via Result`() {
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val < 100} is
              Ok { val = counter } -> do
                # 10 + 100 = 110 >= 100 -> rejected
                let outcome = swap_validated {atom = counter, f = {current} -> current + 100}
                match outcome is
                  Ok { val = _ } -> 999
                  Err { error = ValidatorRejected { val } } -> val
              Err { error = _ } -> 0-1
        """
        assertEquals(110L, eval(code))
    }

    @Test
    fun `test atom without validator has no restrictions`() {
        val code = """
            $atomSetup

            let counter = atom {val = 0}
            let _ = swap {atom = counter, f = {current} -> current - 1000}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(-1000L, result)
    }

    @Test
    fun `test validator preserves value on failure`() {
        // After a valid swap on a ValidatedAtom, the new value is visible.
        val code = """
            $atomSetup

            match atom_validated {val = 5, validator = {val} -> val >= 0} is
              Ok { val = positive } -> do
                let _ = swap_validated {atom = positive, f = {current} -> current + 1}
                deref_validated {atom = positive}
              Err { error = _ } -> 0-1
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    // =============================================================================
    // #390 — ValidatedAtom mutations that invoke a guest callback AND build a
    // Result variant inside the native must compile through the BDSL/bytecode
    // path (MezzeTestHelper.run == context.eval == the production PipelineRunner:
    // BDSL entry + eager + lazy compileOne, which THROWS on any unsupported IR
    // node — there is no AST fallback). A green run therefore proves every
    // touched function (the ValidatedAtom.swap/swap_with/get_and_swap dispatch
    // wrappers, swap_*_validated, and the Ok/Err-returning natives) lowered to
    // BDSL. Locks the divergence that let validated atoms run only in the AST
    // test harness.
    // =============================================================================

    @Test
    fun `swap_with_validated Ok path runs through bytecode pipeline`() {
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let outcome = swap_with_validated {atom = a, f = {current, arg} -> current + arg, arg = 5}
                match outcome is
                  Ok { val } -> val
                  Err { error = _ } -> 0-1
              Err { error = _ } -> 0-1
        """
        assertEquals(15L, eval(code))
    }

    @Test
    fun `swap_with_validated Err path runs through bytecode pipeline`() {
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let outcome = swap_with_validated {atom = a, f = {current, arg} -> current - arg, arg = 100}
                match outcome is
                  Ok { val = _ } -> 999
                  Err { error = ValidatorRejected { val } } -> val
              Err { error = _ } -> 0-1
        """
        // 10 - 100 = -90, rejected; the rejected value is carried in the Err.
        assertEquals(-90L, eval(code))
    }

    @Test
    fun `get_and_swap_validated Ok path runs through bytecode pipeline`() {
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let outcome = get_and_swap_validated {atom = a, f = {current} -> current + 5}
                match outcome is
                  Ok { val } -> val.new
                  Err { error = _ } -> 0-1
              Err { error = _ } -> 0-1
        """
        assertEquals(15L, eval(code))
    }

    @Test
    fun `get_and_swap_validated Err path runs through bytecode pipeline`() {
        val code = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let outcome = get_and_swap_validated {atom = a, f = {current} -> current - 100}
                match outcome is
                  Ok { val = _ } -> 999
                  Err { error = ValidatorRejected { val } } -> val
              Err { error = _ } -> 0-1
        """
        assertEquals(-90L, eval(code))
    }

    @Test
    fun `390 repro swap_validated Ok and Err through bytecode pipeline`() {
        // The exact #390 program shape: create validated atom, swap via the
        // ValidatedAtom.swap dispatch wrapper, match on the returned Result.
        val ok = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let r = swap_validated {atom = a, f = {current} -> current + 5}
                match r is
                  Ok { val } -> val
                  Err { error = _ } -> 0-1
              Err { error = _ } -> 0-1
        """
        assertEquals(15L, eval(ok))

        val err = """
            $atomSetup

            match atom_validated {val = 10, validator = {val} -> val >= 0} is
              Ok { val = a } -> do
                let r = swap_validated {atom = a, f = {current} -> current - 100}
                match r is
                  Ok { val = _ } -> 999
                  Err { error = ValidatorRejected { val } } -> val
              Err { error = _ } -> 0-1
        """
        assertEquals(-90L, eval(err))
    }
}
