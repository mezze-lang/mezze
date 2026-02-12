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
 * Tests for Coop (cooperative) async implementation using actual Funk code.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CoopTest {

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

    // Setup code for Coop operations
    private val coopSetup = """
        newtype CoopPromise 'a = CoopPromise __NativePromise

        let coop_spawn: {f: {} -> 'a} -> CoopPromise 'a = {f} -> __native_coop_spawn {f = f}
        let coop_await: {p: CoopPromise 'a} -> 'a = {p} -> __native_coop_await {p = p}
        let coop_sleep: {ms: Int} -> {} = {ms} -> __native_coop_sleep {ms = ms}
        let coop_is_done: {p: CoopPromise 'a} -> Bool = {p} -> __native_coop_is_done {p = p}
        let coop_is_cancelled: {} -> Bool = {} -> __native_coop_is_cancelled {}
        let coop_check: {} -> {} = {} -> __native_coop_check {}
    """.trimIndent()

    // =============================================================================
    // Basic Coop Operations
    // =============================================================================

    @Test
    fun `test coop_spawn stores computation`() {
        val code = """
            $coopSetup

            let p = coop_spawn {f = {} -> 42}
            # Promise is created but computation not executed yet
            coop_is_done {p = p}
        """
        val result = eval(code)
        // In Coop, promises are always "ready" since computation is stored
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test coop_await executes computation`() {
        val code = """
            $coopSetup

            let p = coop_spawn {f = {} -> 42}
            coop_await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test coop_await with complex computation`() {
        val code = """
            $coopSetup

            let p = coop_spawn {f = {} -> 10 + 20 + 12}
            coop_await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test coop_sleep is no-op`() {
        val code = """
            $coopSetup

            # In Coop, sleep is a no-op (single-threaded)
            let _ = coop_sleep {ms = 1000}
            True
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test coop_is_cancelled always false`() {
        val code = """
            $coopSetup

            # In single-threaded Coop, cancellation is not applicable
            coop_is_cancelled {}
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
    }

    @Test
    fun `test coop_check is no-op`() {
        val code = """
            $coopSetup

            let _ = coop_check {}
            True
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    // =============================================================================
    // Sequential Execution (Coop is single-threaded)
    // =============================================================================

    @Test
    fun `test coop executes sequentially`() {
        val code = """
            $coopSetup

            # In Coop, spawn stores computation, await executes it
            # This is deterministic and sequential
            let p1 = coop_spawn {f = {} -> 1}
            let p2 = coop_spawn {f = {} -> 2}
            let p3 = coop_spawn {f = {} -> 3}

            let r1 = coop_await {p = p1}
            let r2 = coop_await {p = p2}
            let r3 = coop_await {p = p3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(FunkInt(6), result)
    }

    @Test
    fun `test coop with nested computation`() {
        val code = """
            $coopSetup

            let outer = coop_spawn {f = {} -> do
                let inner = coop_spawn {f = {} -> 21}
                let x = coop_await {p = inner}
                x * 2
            }

            coop_await {p = outer}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }
}
