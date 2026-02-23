package com.mezze.language.interpreter.stdlib

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
 * Tests for std/async/coop module.
 *
 * Cooperative async provides single-threaded concurrency:
 * - coop_spawn stores computation for later
 * - coop_await executes synchronously
 * - Deterministic execution order
 * - Useful for testing and single-threaded environments
 */
@Execution(ExecutionMode.SAME_THREAD)
class CoopAsyncModuleTest {

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

    // Note: Direct coop module functions (coop_spawn, coop_await, etc.) have type import issues
    // Use the Async effect with CoopAsync handler instead, which is tested below

    // =============================================================================
    // Via Async Effect with CoopAsync Handler
    // =============================================================================

    @Test
    fun `test CoopAsync handler with Async effect`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync { cont = continuation::new {} } in do
                let p = Async.spawn { f = {} -> 42 }
                Async.await { p = p }
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test CoopAsync handler multiple spawns`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync { cont = continuation::new {} } in do
                let p1 = Async.spawn { f = {} -> 1 }
                let p2 = Async.spawn { f = {} -> 2 }
                let p3 = Async.spawn { f = {} -> 3 }

                let r1 = Async.await { p = p1 }
                let r2 = Async.await { p = p2 }
                let r3 = Async.await { p = p3 }

                r1 + r2 + r3
        """)
        assertEquals(MezzeInt(6), result)
    }
}
