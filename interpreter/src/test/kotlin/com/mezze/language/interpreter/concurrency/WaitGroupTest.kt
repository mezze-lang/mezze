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
 * Tests for WaitGroup implementation using actual Mezze code.
 */
@Execution(ExecutionMode.SAME_THREAD)
class WaitGroupTest {

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

    // Setup code for wait group operations
    private val waitGroupSetup = """
        newtype WaitGroup = WaitGroup __NativeWaitGroup

        let wait_group: {} -> WaitGroup = {} -> __native_waitgroup_new {}
        let add: {wg: WaitGroup, delta: Int} -> {} = {wg, delta} -> __native_waitgroup_add {wg = wg, delta = delta}
        let done: {wg: WaitGroup} -> {} = {wg} -> __native_waitgroup_done {wg = wg}
        let wait: {wg: WaitGroup} -> {} = {wg} -> __native_waitgroup_wait {wg = wg}
        let wait_timeout: {wg: WaitGroup, ms: Int} -> Bool = {wg, ms} -> __native_waitgroup_wait_timeout {wg = wg, ms = ms}
        let get_count_wg: {wg: WaitGroup} -> Int = {wg} -> __native_waitgroup_get_count {wg = wg}
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
        let await_promise: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
        let sleep: {ms: Int} -> {} = {ms} -> __native_loom_sleep {ms = ms}
    """.trimIndent()

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
        assertEquals(MezzeInt(0), result)
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
        assertEquals(MezzeInt(3), result)
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
        assertEquals(MezzeInt(2), result)
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
        assertEquals(MezzeInt(0), result)
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
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test wait_timeout returns true when count is zero`() {
        val code = """
            $waitGroupSetup

            let wg = wait_group {}
            wait_timeout {wg = wg, ms = 100}
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
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
        assertEquals(MezzeBool(false), result)
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
        assertEquals(MezzeInt(6), result)
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
            let counter = atom {value = 0}

            # Add 3 tasks
            let _ = add {wg = wg, delta = 3}

            # Spawn 3 workers
            let p1 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }
            let p2 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }
            let p3 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            # Wait for all workers to finish
            let _ = wait {wg = wg}

            # Counter should be 3
            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test wait_group with incremental task adding`() {
        val code = """
            $waitGroupSetup
            $atomSetup
            $loomSetup

            let wg = wait_group {}
            let counter = atom {value = 0}

            # Add tasks one at a time
            let _ = add {wg = wg, delta = 1}
            let p1 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            let _ = add {wg = wg, delta = 1}
            let p2 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            let _ = add {wg = wg, delta = 1}
            let p3 = spawn {f = {} -> do
                let _ = swap {atom = counter, f = {current} -> current + 1}
                done {wg = wg}
            }

            # Wait for all
            let _ = wait {wg = wg}

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test wait_group blocks until all done`() {
        val code = """
            $waitGroupSetup
            $atomSetup
            $loomSetup

            let wg = wait_group {}
            let result = atom {value = 0}

            let _ = add {wg = wg, delta = 1}

            # Thread 1: Wait for waitgroup, then set result
            let p1 = spawn {f = {} -> do
                let _ = wait {wg = wg}
                swap {atom = result, f = {current} -> 42}
            }

            # Give thread 1 time to start waiting
            let _ = sleep {ms = 20}

            # Result should still be 0 (thread 1 is blocked)
            let before = deref {atom = result}

            # Done to release thread 1
            let _ = done {wg = wg}

            let _ = await_promise {p = p1}

            {before = before, after = deref {atom = result}}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeInt(0), result.fields["before"])
        assertEquals(MezzeInt(42), result.fields["after"])
    }
}
