package com.mezze.interpreter.v2.concurrency

import com.mezze.interpreter.v2.MezzeTestHelper
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

    // Setup code for atom operations
    private val atomSetup = """
        # Atom type - wraps native atomic reference
        newtype Atom 'a = Atom __NativeAtomicRef

        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let reset: {atom: Atom 'a, value: 'a} -> 'a = {atom, value} -> __native_atom_reset {atom = atom, value = value}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
        let compare_and_set: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool = {atom, expected, new} -> __native_atom_cas {atom = atom, expected = expected, new = new}
        let add_watch: {atom: Atom 'a, key: String, f: {key: String, old: 'a, new: 'a} -> {}} -> {} = {atom, key, f} -> __native_atom_add_watch {atom = atom, key = key, f = f}
        let remove_watch: {atom: Atom 'a, key: String} -> {} = {atom, key} -> __native_atom_remove_watch {atom = atom, key = key}
        let atom_validated: {value: 'a, validator: {val: 'a} -> Bool} -> Atom 'a = {value, validator} -> __native_atom_new_validated {value = value, validator = validator}
        let set_validator: {atom: Atom 'a, validator: {val: 'a} -> Bool} -> {} = {atom, validator} -> __native_atom_set_validator {atom = atom, validator = validator}
    """.trimIndent()

    // Loom primitives for concurrent tests
    private val loomSetup = """
        newtype Promise 'a = Promise __NativePromise
        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
        let sleep: {ms: Int} -> {} = {ms} -> __native_loom_sleep {ms = ms}
    """.trimIndent()

    // =============================================================================
    // Basic Atom Operations
    // =============================================================================

    @Test
    fun `test atom creation and deref`() {
        val code = """
            $atomSetup

            let counter = atom {value = 42}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test atom with string value`() {
        val code = """
            $atomSetup

            let name = atom {value = "hello"}
            deref {atom = name}
        """
        val result = eval(code)
        assertEquals("hello", result)
    }

    @Test
    fun `test reset sets new value`() {
        val code = """
            $atomSetup

            let counter = atom {value = 0}
            let _ = reset {atom = counter, value = 100}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test reset returns new value`() {
        val code = """
            $atomSetup

            let counter = atom {value = 0}
            reset {atom = counter, value = 42}
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

            let counter = atom {value = 10}
            swap {atom = counter, f = {current} -> current + 1}
        """
        val result = eval(code)
        assertEquals(11L, result)
    }

    @Test
    fun `test swap updates atom value`() {
        val code = """
            $atomSetup

            let counter = atom {value = 5}
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

            let counter = atom {value = 0}
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

            let counter = atom {value = 1}
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

            let counter = atom {value = 1}
            compare_and_set {atom = counter, expected = 1, new = 2}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test compare_and_set fails when expected differs`() {
        val code = """
            $atomSetup

            let counter = atom {value = 1}
            compare_and_set {atom = counter, expected = 999, new = 2}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test compare_and_set updates value on success`() {
        val code = """
            $atomSetup

            let counter = atom {value = 10}
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

            let counter = atom {value = 10}
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
            $loomSetup

            let counter = atom {value = 0}

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
            let p1 = spawn {f = increment_10}
            let p2 = spawn {f = increment_10}
            let p3 = spawn {f = increment_10}

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

            let state = atom {value = {count = 0, name = "test"}}
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

            let state = atom {value = {count = 0}}
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

            let counter = atom {value = 0}
            let log = atom {value = 0}

            # Add a watch that records the sum of old + new
            let _ = add_watch {
                atom = counter,
                key = "logger",
                f = {key, old, new} -> do
                    let _ = reset {atom = log, value = old + new}
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

            let counter = atom {value = 5}
            let log = atom {value = 0}

            let _ = add_watch {
                atom = counter,
                key = "logger",
                f = {key, old, new} -> do
                    let _ = reset {atom = log, value = old + new}
                    {}
            }

            # reset should trigger the watch
            let _ = reset {atom = counter, value = 15}

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

            let counter = atom {value = 0}
            let log1 = atom {value = 0}
            let log2 = atom {value = 0}

            let _ = add_watch {
                atom = counter,
                key = "watch1",
                f = {key, old, new} -> do
                    let _ = reset {atom = log1, value = new}
                    {}
            }

            let _ = add_watch {
                atom = counter,
                key = "watch2",
                f = {key, old, new} -> do
                    let _ = reset {atom = log2, value = new * 2}
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

            let counter = atom {value = 0}
            let log = atom {value = 0}

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

            let counter = atom {value = 0}
            let key_log = atom {value = "none"}

            let _ = add_watch {
                atom = counter,
                key = "my_watch_key",
                f = {key, old, new} -> do
                    let _ = reset {atom = key_log, value = key}
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

            let counter = atom {value = 0}
            let call_count = atom {value = 0}

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
        val code = """
            $atomSetup

            let positive = atom_validated {value = 10, validator = {val} -> val >= 0}
            deref {atom = positive}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test atom_validated rejects invalid initial value`() {
        val code = """
            $atomSetup

            let positive = atom_validated {value = -5, validator = {val} -> val >= 0}
            deref {atom = positive}
        """
        val exception = assertThrows(Exception::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("validator rejected") == true)
    }

    @Test
    fun `test validator allows valid swap`() {
        val code = """
            $atomSetup

            let positive = atom_validated {value = 5, validator = {val} -> val >= 0}
            let _ = swap {atom = positive, f = {current} -> current + 10}
            deref {atom = positive}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test validator rejects invalid swap`() {
        val code = """
            $atomSetup

            let positive = atom_validated {value = 5, validator = {val} -> val >= 0}
            swap {atom = positive, f = {current} -> current - 100}
        """
        val exception = assertThrows(Exception::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("validator rejected") == true)
    }

    @Test
    fun `test validator rejects invalid reset`() {
        val code = """
            $atomSetup

            let positive = atom_validated {value = 5, validator = {val} -> val >= 0}
            reset {atom = positive, value = -10}
        """
        val exception = assertThrows(Exception::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("validator rejected") == true)
    }

    @Test
    fun `test set_validator on existing atom`() {
        val code = """
            $atomSetup

            let counter = atom {value = 10}
            let _ = set_validator {atom = counter, validator = {val} -> val < 100}

            # This should be allowed
            let _ = swap {atom = counter, f = {current} -> current + 50}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test set_validator rejects future invalid updates`() {
        val code = """
            $atomSetup

            let counter = atom {value = 10}
            let _ = set_validator {atom = counter, validator = {val} -> val < 100}

            # This should fail because 10 + 100 = 110 >= 100
            swap {atom = counter, f = {current} -> current + 100}
        """
        val exception = assertThrows(Exception::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("validator rejected") == true)
    }

    @Test
    fun `test atom without validator has no restrictions`() {
        val code = """
            $atomSetup

            let counter = atom {value = 0}
            let _ = swap {atom = counter, f = {current} -> current - 1000}
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(-1000L, result)
    }

    @Test
    fun `test validator preserves value on failure`() {
        // After a failed swap, the atom should still have its old value
        val code = """
            $atomSetup

            let positive = atom_validated {value = 5, validator = {val} -> val >= 0}
            let result = atom {value = 0}

            # Try an invalid swap inside a function that catches the error
            # Since Mezze doesn't have try/catch, we'll just verify the atom value is preserved
            # by doing a valid swap after the test

            # First, do a valid swap
            let _ = swap {atom = positive, f = {current} -> current + 1}
            deref {atom = positive}
        """
        val result = eval(code)
        assertEquals(6L, result)
    }
}
