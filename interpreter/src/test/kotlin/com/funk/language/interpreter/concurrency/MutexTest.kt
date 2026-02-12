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
 * Tests for Mutex implementation using actual Funk code.
 *
 * Mutexes provide mutual exclusion using Java's ReentrantLock.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MutexTest {

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

    // Setup code for mutex operations
    private val mutexSetup = """
        newtype Mutex = Mutex __NativeLock

        let mutex: {} -> Mutex = {} -> __native_mutex_new {}
        let with_lock: {mutex: Mutex, f: {} -> 'a} -> 'a = {mutex, f} -> __native_mutex_with_lock {mutex = mutex, f = f}
        let lock: {mutex: Mutex} -> {} = {mutex} -> __native_mutex_lock {mutex = mutex}
        let unlock: {mutex: Mutex} -> {} = {mutex} -> __native_mutex_unlock {mutex = mutex}
        let try_lock: {mutex: Mutex} -> Bool = {mutex} -> __native_mutex_try_lock {mutex = mutex}
        let is_held_by_current_thread: {mutex: Mutex} -> Bool = {mutex} -> __native_mutex_is_held_by_current_thread {mutex = mutex}
    """.trimIndent()

    // Atom setup for shared state tests
    private val atomSetup = """
        newtype Atom 'a = Atom __NativeAtomicRef
        let atom: {value: 'a} -> Atom 'a = {value} -> __native_atom_new {value = value}
        let deref: {atom: Atom 'a} -> 'a = {atom} -> __native_atom_deref {atom = atom}
        let reset: {atom: Atom 'a, value: 'a} -> 'a = {atom, value} -> __native_atom_reset {atom = atom, value = value}
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
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test with_lock executes function`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            with_lock {mutex = m, f = {} -> 42}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
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
        assertEquals(FunkString("hello"), result)
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
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test is_held_by_current_thread when not held`() {
        val code = """
            $mutexSetup

            let m = mutex {}
            is_held_by_current_thread {mutex = m}
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
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
        assertEquals(FunkBool(true), result)
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
        assertEquals(FunkBool(true), result)
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
            let p1 = spawn {f = increment_5}
            let p2 = spawn {f = increment_5}
            let p3 = spawn {f = increment_5}

            let _ = await {p = p1}
            let _ = await {p = p2}
            let _ = await {p = p3}

            deref {atom = counter}
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun `test with_lock releases lock after completion`() {
        val code = """
            $mutexSetup
            $loomSetup

            let m = mutex {}

            # First thread takes lock, does work, releases
            let p1 = spawn {f = {} ->
                with_lock {mutex = m, f = {} ->
                    sleep {ms = 10}
                }
            }

            # Wait for first to finish
            let _ = await {p = p1}

            # Second thread should be able to acquire lock
            let p2 = spawn {f = {} ->
                with_lock {mutex = m, f = {} -> 42}
            }

            await {p = p2}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
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
            let p1 = spawn {f = {} -> do
                let _ = lock {mutex = m}
                let _ = sleep {ms = 100}
                unlock {mutex = m}
            }

            # Give thread 1 time to acquire
            let _ = sleep {ms = 20}

            # Thread 2 tries to acquire - should fail
            let p2 = spawn {f = {} -> do
                let acquired = try_lock {mutex = m}
                let _ = reset {atom = try_result, value = acquired}
                if acquired then unlock {mutex = m} else {}
            }

            let _ = await {p = p2}
            let _ = await {p = p1}

            deref {atom = try_result}
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
    }
}
