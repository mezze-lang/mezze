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
 * Tests for Semaphore implementation using actual Funk code.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SemaphoreTest {

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

    // Setup code for semaphore operations
    private val semaphoreSetup = """
        newtype Semaphore = Semaphore __NativeSemaphore

        let semaphore: {permits: Int} -> Semaphore = {permits} -> __native_semaphore_new {permits = permits}
        let acquire: {sem: Semaphore} -> {} = {sem} -> __native_semaphore_acquire {sem = sem}
        let acquire_n: {sem: Semaphore, permits: Int} -> {} = {sem, permits} -> __native_semaphore_acquire_n {sem = sem, permits = permits}
        let release: {sem: Semaphore} -> {} = {sem} -> __native_semaphore_release {sem = sem}
        let release_n: {sem: Semaphore, permits: Int} -> {} = {sem, permits} -> __native_semaphore_release_n {sem = sem, permits = permits}
        let try_acquire: {sem: Semaphore} -> Bool = {sem} -> __native_semaphore_try_acquire {sem = sem}
        let try_acquire_n: {sem: Semaphore, permits: Int} -> Bool = {sem, permits} -> __native_semaphore_try_acquire_n {sem = sem, permits = permits}
        let available: {sem: Semaphore} -> Int = {sem} -> __native_semaphore_available {sem = sem}
        let with_permit: {sem: Semaphore, f: {} -> 'a} -> 'a = {sem, f} -> __native_semaphore_with_permit {sem = sem, f = f}
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
    // Basic Semaphore Operations
    // =============================================================================

    @Test
    fun `test semaphore creation`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 3}
            available {sem = sem}
        """
        val result = eval(code)
        assertEquals(FunkInt(3), result)
    }

    @Test
    fun `test acquire decreases available permits`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 3}
            let _ = acquire {sem = sem}
            available {sem = sem}
        """
        val result = eval(code)
        assertEquals(FunkInt(2), result)
    }

    @Test
    fun `test release increases available permits`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 1}
            let _ = acquire {sem = sem}
            let _ = release {sem = sem}
            available {sem = sem}
        """
        val result = eval(code)
        assertEquals(FunkInt(1), result)
    }

    @Test
    fun `test acquire_n and release_n`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 5}
            let _ = acquire_n {sem = sem, permits = 3}
            let after_acquire = available {sem = sem}
            let _ = release_n {sem = sem, permits = 2}
            let after_release = available {sem = sem}
            {acquired = after_acquire, released = after_release}
        """
        val result = eval(code) as FunkRecord
        assertEquals(FunkInt(2), result.fields["acquired"])
        assertEquals(FunkInt(4), result.fields["released"])
    }

    @Test
    fun `test try_acquire succeeds when permits available`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 1}
            let acquired = try_acquire {sem = sem}
            let _ = if acquired then release {sem = sem} else {}
            acquired
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test try_acquire fails when no permits`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 0}
            try_acquire {sem = sem}
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
    }

    @Test
    fun `test with_permit executes and releases`() {
        val code = """
            $semaphoreSetup

            let sem = semaphore {permits = 1}
            let result = with_permit {sem = sem, f = {} -> 42}
            let after = available {sem = sem}
            {result = result, available = after}
        """
        val result = eval(code) as FunkRecord
        assertEquals(FunkInt(42), result.fields["result"])
        assertEquals(FunkInt(1), result.fields["available"])
    }

    // =============================================================================
    // Concurrent Tests with Loom
    // =============================================================================

    @Test
    fun `test semaphore limits concurrency`() {
        val code = """
            $semaphoreSetup
            $atomSetup
            $loomSetup

            # Semaphore with 2 permits - max 2 concurrent
            let sem = semaphore {permits = 2}
            let max_concurrent = atom {value = 0}
            let current = atom {value = 0}

            # Function that tracks concurrent execution
            let work = {} -> do
                let _ = acquire {sem = sem}
                # Increment current count
                let now = swap {atom = current, f = {current} -> current + 1}
                # Update max if needed
                let _ = swap {atom = max_concurrent, f = {current} ->
                    if now > current then now else current
                }
                # Small delay to overlap with other threads
                let _ = sleep {ms = 20}
                # Decrement current count
                let _ = swap {atom = current, f = {current} -> current - 1}
                release {sem = sem}

            # Spawn 5 threads
            let p1 = spawn {f = work}
            let p2 = spawn {f = work}
            let p3 = spawn {f = work}
            let p4 = spawn {f = work}
            let p5 = spawn {f = work}

            let _ = await {p = p1}
            let _ = await {p = p2}
            let _ = await {p = p3}
            let _ = await {p = p4}
            let _ = await {p = p5}

            # Max concurrent should be <= 2
            deref {atom = max_concurrent}
        """
        val result = eval(code) as FunkInt
        assertTrue(result.value <= 2, "Max concurrent should be <= 2, was ${result.value}")
    }

    @Test
    fun `test semaphore with zero permits blocks until released`() {
        val code = """
            $semaphoreSetup
            $atomSetup
            $loomSetup

            let sem = semaphore {permits = 0}
            let result = atom {value = 0}

            # Thread 1: Wait for permit, then set result
            let p1 = spawn {f = {} -> do
                let _ = acquire {sem = sem}
                swap {atom = result, f = {current} -> 42}
            }

            # Give thread 1 time to start waiting
            let _ = sleep {ms = 20}

            # Result should still be 0 (thread 1 is blocked)
            let before_release = deref {atom = result}

            # Release a permit to unblock thread 1
            let _ = release {sem = sem}

            let _ = await {p = p1}

            {before = before_release, after = deref {atom = result}}
        """
        val result = eval(code) as FunkRecord
        assertEquals(FunkInt(0), result.fields["before"])
        assertEquals(FunkInt(42), result.fields["after"])
    }
}
