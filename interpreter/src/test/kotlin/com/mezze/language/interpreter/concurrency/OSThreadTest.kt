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
 * Tests for OS platform thread implementation using actual Mezze code.
 */
@Execution(ExecutionMode.SAME_THREAD)
class OSThreadTest {

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

    // Setup code for OS thread operations
    private val osThreadSetup = """
        newtype OSPromise 'a = OSPromise __NativePromise
        newtype OSThread = OSThread __NativeThread

        let os_spawn: {f: {} -> 'a} -> OSPromise 'a = {f} -> __native_os_spawn {f = f}
        let os_await: {p: OSPromise 'a} -> 'a = {p} -> __native_os_await {p = p}
        let os_sleep: {ms: Int} -> {} = {ms} -> __native_os_sleep {ms = ms}
        let os_is_done: {p: OSPromise 'a} -> Bool = {p} -> __native_os_is_done {p = p}
        let os_current_thread: {} -> OSThread = {} -> __native_os_current_thread {}
        let os_park: {} -> {} = {} -> __native_os_park {}
        let os_unpark: {thread: OSThread} -> {} = {thread} -> __native_os_unpark {thread = thread}
    """.trimIndent()

    // Atom setup for shared state tests
    private val atomSetup = """
        newtype Atom 'a = Atom __NativeAtomicRef
        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let swap: {atom: Atom 'a, f: {current: 'a} -> 'a} -> 'a = {atom, f} -> __native_atom_swap {atom = atom, f = f}
    """.trimIndent()

    // =============================================================================
    // Basic OS Thread Operations
    // =============================================================================

    @Test
    fun `test os_spawn and os_await`() {
        val code = """
            $osThreadSetup

            let p = os_spawn {f = {} -> 42}
            os_await {p = p}
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test os_spawn with computation`() {
        val code = """
            $osThreadSetup

            let p = os_spawn {f = {} -> 10 + 20 + 12}
            os_await {p = p}
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test os_is_done`() {
        val code = """
            $osThreadSetup

            let p = os_spawn {f = {} -> 42}
            let _ = os_await {p = p}
            os_is_done {p = p}
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test os_sleep`() {
        val code = """
            $osThreadSetup

            let _ = os_sleep {ms = 10}
            True
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test os_current_thread`() {
        val code = """
            $osThreadSetup

            let t = os_current_thread {}
            # Just verify we can get the thread without error
            True
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    // =============================================================================
    // Thread Coordination Tests
    // =============================================================================

    @Test
    fun `test os_park and os_unpark`() {
        val code = """
            $osThreadSetup
            $atomSetup

            let result = atom {value = 0}
            let main_thread = os_current_thread {}

            # Spawn a thread that will unpark us
            let p = os_spawn {f = {} -> do
                let _ = os_sleep {ms = 20}  # Give main thread time to park
                let _ = swap {atom = result, f = {current} -> 1}
                os_unpark {thread = main_thread}
            }

            # Park and wait to be unparked
            let _ = os_park {}

            let _ = os_await {p = p}
            deref {atom = result}
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test multiple os threads`() {
        val code = """
            $osThreadSetup
            $atomSetup

            let counter = atom {value = 0}

            let p1 = os_spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let p2 = os_spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}
            let p3 = os_spawn {f = {} -> swap {atom = counter, f = {current} -> current + 1}}

            let _ = os_await {p = p1}
            let _ = os_await {p = p2}
            let _ = os_await {p = p3}

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }
}
