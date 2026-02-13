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
 *
 * These tests use the stdlib's Async effect with CoopAsync handler, which
 * provides cooperative scheduling using delimited continuations.
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

    // Setup code for Coop operations using stdlib
    private val coopSetup = """
        use std::async::{Async, CoopAsync}
        use std::control::continuation
    """.trimIndent()

    // =============================================================================
    // Basic Coop Operations
    // =============================================================================

    @Test
    fun `test coop_spawn stores computation`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                # Promise is created but computation not executed yet
                Async.is_done {p = p}
        """
        val result = eval(code)
        // In Coop, promises are always "ready" since computation is stored
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test coop_await executes computation`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test coop_await with complex computation`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 10 + 20 + 12}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test coop_sleep is no-op`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                # In Coop, sleep is a no-op (single-threaded)
                let _ = Async.sleep {ms = 1000}
                True
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test coop_is_cancelled always false`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                # In single-threaded Coop, cancellation is not applicable
                Async.is_cancelled {}
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
    }

    @Test
    fun `test coop_check is no-op`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let _ = Async.check {}
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

            perform CoopAsync {cont = continuation::new {}} in do
                # In Coop, spawn stores computation, await executes it
                # This is deterministic and sequential
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(FunkInt(6), result)
    }

    @Test
    fun `test coop with nested computation`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let outer = Async.spawn {f = {} -> do
                    let inner = Async.spawn {f = {} -> 21}
                    let x = Async.await {p = inner}
                    x * 2
                }

                Async.await {p = outer}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }
}
