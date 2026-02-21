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
 * Tests for std/async/osthread module.
 *
 * OS platform threads provide true parallelism:
 * - os_spawn creates platform thread
 * - os_await blocks until complete
 * - Useful for CPU-bound work
 */
@Execution(ExecutionMode.SAME_THREAD)
class OSThreadModuleTest {

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
        Thread.sleep(100)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // =============================================================================
    // Basic OS Thread Operations
    // =============================================================================

    @Test
    fun `test os_spawn and os_await`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_await}

            let p = os_spawn { f = {} -> 42 }
            os_await { p = p }
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test os_spawn with computation`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_await}

            let p = os_spawn { f = {} -> 10 + 20 + 12 }
            os_await { p = p }
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test os_spawn multiple threads`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_await}

            let p1 = os_spawn { f = {} -> 10 }
            let p2 = os_spawn { f = {} -> 20 }
            let p3 = os_spawn { f = {} -> 30 }

            let r1 = os_await { p = p1 }
            let r2 = os_await { p = p2 }
            let r3 = os_await { p = p3 }

            r1 + r2 + r3
        """)
        assertEquals(MezzeInt(60), result)
    }

    // =============================================================================
    // OS Thread Status
    // =============================================================================

    @Test
    fun `test os_is_done after await`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_await, os_is_done}

            let p = os_spawn { f = {} -> 42 }
            let _ = os_await { p = p }
            os_is_done { p = p }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test os_is_cancelled returns false`() {
        val result = eval("""
            use std::async::osthread::{os_is_cancelled}

            os_is_cancelled {}
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test os_check is no-op when not cancelled`() {
        val result = eval("""
            use std::async::osthread::{os_check}

            os_check {}
            42
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // os_all and os_race
    // =============================================================================

    @Test
    fun `test os_all executes all`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_all}

            let p1 = os_spawn { f = {} -> 10 }
            let p2 = os_spawn { f = {} -> 20 }
            let p3 = os_spawn { f = {} -> 30 }

            let results = os_all { ps = [p1, p2, p3] }
            # Sum the results
            let sum = { xs, acc } ->
                match xs is
                    [] -> acc
                    [x, ...rest] -> sum { xs = rest, acc = acc + x }
            sum { xs = results, acc = 0 }
        """)
        assertEquals(MezzeInt(60), result)
    }

    @Test
    fun `test os_race returns first completed`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_race}

            # All same computation, race should return one of them
            let p1 = os_spawn { f = {} -> 42 }
            let p2 = os_spawn { f = {} -> 42 }
            let p3 = os_spawn { f = {} -> 42 }

            os_race { ps = [p1, p2, p3] }
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // os_timeout
    // =============================================================================

    @Test
    fun `test os_timeout returns Some on completion`() {
        val result = eval("""
            use std::async::osthread::{os_spawn, os_timeout}

            let p = os_spawn { f = {} -> 42 }
            match os_timeout { p = p, ms = 5000 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // Via Async Effect with OSThreadAsync Handler
    // =============================================================================

    @Test
    fun `test OSThreadAsync handler with Async effect`() {
        val result = eval("""
            use std::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let p = Async.spawn { f = {} -> 42 }
                Async.await { p = p }
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test OSThreadAsync handler multiple spawns`() {
        val result = eval("""
            use std::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let p1 = Async.spawn { f = {} -> 10 }
                let p2 = Async.spawn { f = {} -> 20 }
                let p3 = Async.spawn { f = {} -> 30 }

                let r1 = Async.await { p = p1 }
                let r2 = Async.await { p = p2 }
                let r3 = Async.await { p = p3 }

                r1 + r2 + r3
        """)
        assertEquals(MezzeInt(60), result)
    }

    @Test
    fun `test OSThreadAsync with join`() {
        val result = eval("""
            use std::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let result = Async.join {
                    left = {} -> 10,
                    right = {} -> 20
                }
                result.left + result.right
        """)
        assertEquals(MezzeInt(30), result)
    }
}
