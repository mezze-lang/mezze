package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for CountDownLatch implementation using actual Mezze code.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LatchTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // Setup code for latch operations
    private val latchSetup = """
        newtype Latch = Latch __NativeLatch

        let latch: {count: Int} -> Latch = {count} -> __native_latch_new {count = count}
        let count_down: {latch: Latch} -> {} = {latch} -> __native_latch_count_down {latch = latch}
        let await_latch: {latch: Latch} -> {} = {latch} -> __native_latch_await {latch = latch}
        let await_latch_timeout: {latch: Latch, ms: Int} -> Bool = {latch, ms} -> __native_latch_await_timeout {latch = latch, ms = ms}
        let get_count: {latch: Latch} -> Int = {latch} -> __native_latch_get_count {latch = latch}
    """.trimIndent()

    // Atom setup for shared state tests
    private val atomSetup = """
        newtype Atom 'a = Atom __NativeAtomicRef
        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
    """.trimIndent()

    // Loom primitives for concurrent tests
    private val loomSetup = """
        newtype Promise 'a = Promise __NativePromise
        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
        let sleep: {ms: Int} -> {} = {ms} -> __native_loom_sleep {ms = ms}
    """.trimIndent()

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
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test latch with zero count`() {
        val code = """
            $latchSetup

            let l = latch {count = 0}
            get_count {latch = l}
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
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
        assertEquals(MezzeInt(2), result)
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
        assertEquals(MezzeInt(0), result)
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
        assertEquals(MezzeInt(0), result)
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
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test await_latch_timeout returns true when count is zero`() {
        val code = """
            $latchSetup

            let l = latch {count = 0}
            await_latch_timeout {latch = l, ms = 100}
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeBool(false), result)
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
            let counter = atom {value = 0}

            # Spawn 3 workers that each count down
            let p1 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                count_down {latch = l}
            }
            let p2 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                count_down {latch = l}
            }
            let p3 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                count_down {latch = l}
            }

            # Wait for all workers to finish
            let _ = await_latch {latch = l}

            # Counter should be 3
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test latch blocks until count reaches zero`() {
        val code = """
            $latchSetup
            $atomSetup
            $loomSetup

            let l = latch {count = 1}
            let result = atom {value = 0}

            # Thread 1: Wait for latch, then set result
            let p1 = spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = result, f = {current} -> 42}
            }

            # Give thread 1 time to start waiting
            let _ = sleep {ms = 20}

            # Result should still be 0 (thread 1 is blocked)
            let before = deref {atom = result}

            # Count down to release thread 1
            let _ = count_down {latch = l}

            let _ = await {p = p1}

            {before = before, after = deref {atom = result}}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeInt(0), result.fields["before"])
        assertEquals(MezzeInt(42), result.fields["after"])
    }

    @Test
    fun `test multiple threads waiting on same latch`() {
        val code = """
            $latchSetup
            $atomSetup
            $loomSetup

            let l = latch {count = 1}
            let counter = atom {value = 0}

            # Spawn 3 threads all waiting on the same latch
            let p1 = spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let p2 = spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = counter, f = {current} -> current + 1}
            }
            let p3 = spawn {f = {} -> do
                let _ = await_latch {latch = l}
                swap {atom = counter, f = {current} -> current + 1}
            }

            # Give threads time to start waiting
            let _ = sleep {ms = 20}

            # Release all threads at once
            let _ = count_down {latch = l}

            let _ = await {p = p1}
            let _ = await {p = p2}
            let _ = await {p = p3}

            # All threads should have incremented
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }
}
