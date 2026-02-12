package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Clojure-style Atom implementation using actual Funk code.
 *
 * Atoms provide thread-safe, lock-free state management using compare-and-swap.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AtomTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // Setup code for atom operations
    private val atomSetup = """
        # Atom type - wraps native atomic reference
        newtype Atom 'a = Atom __NativeAtomicRef

        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let reset: {atom: Atom 'a, value: 'a} -> 'a = {atom, value} -> __native_atom_reset {atom = atom, value = value}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
        let compare_and_set: {atom: Atom 'a, expected: 'a, new: 'a} -> Bool = {atom, expected, new} -> __native_atom_cas {atom = atom, expected = expected, new = new}
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
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test atom with string value`() {
        val code = """
            $atomSetup

            let name = atom {value = "hello"}
            deref {atom = name}
        """
        val result = eval(code)
        assertEquals(FunkString("hello"), result)
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
        assertEquals(FunkInt(100), result)
    }

    @Test
    fun `test reset returns new value`() {
        val code = """
            $atomSetup

            let counter = atom {value = 0}
            reset {atom = counter, value = 42}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
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
        assertEquals(FunkInt(11), result)
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
        assertEquals(FunkInt(10), result)
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
        assertEquals(FunkInt(3), result)
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
        assertEquals(FunkInt(10), result)
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
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test compare_and_set fails when expected differs`() {
        val code = """
            $atomSetup

            let counter = atom {value = 1}
            compare_and_set {atom = counter, expected = 999, new = 2}
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
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
        assertEquals(FunkInt(20), result)
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
        assertEquals(FunkInt(10), result)
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
        assertEquals(FunkInt(30), result)
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
        assertEquals(FunkInt(0), result)
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
        assertEquals(FunkInt(2), result)
    }
}
