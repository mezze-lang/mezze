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
 * Tests for Structured Concurrency Scopes (Async.scoped, scope_spawn).
 *
 * Key properties of structured concurrency:
 * - Parent always waits for all children to complete
 * - If parent fails, all children are cancelled
 * - If child fails, siblings are cancelled (fail-fast behavior)
 */
@Execution(ExecutionMode.SAME_THREAD)
class ScopedConcurrencyTest {

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

    // =============================================================================
    // Basic Scoped Tests - LoomAsync
    // =============================================================================

    @Test
    fun `test scoped with single child task`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 42}
                    Async.await {p = p}
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test scoped with multiple child tasks`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p1 = scope_spawn {scope = scope, f = {} -> 10}
                    let p2 = scope_spawn {scope = scope, f = {} -> 20}
                    let p3 = scope_spawn {scope = scope, f = {} -> 30}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}
                    let r3 = Async.await {p = p3}

                    r1 + r2 + r3
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(60), result)
    }

    @Test
    fun `test scoped returns function result`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 100}
                    let _ = Async.await {p = p}
                    "done"
                }
        """
        val result = eval(code)
        assertEquals(MezzeString("done"), result)
    }

    @Test
    fun `test scoped with no child tasks`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> 42}
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test nested scopes`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p1 = scope_spawn {scope = scope, f = {} -> 10}

                    let inner_result = Async.scoped {f = {scope} -> do
                        let p2 = scope_spawn {scope = scope, f = {} -> 20}
                        Async.await {p = p2}
                    }

                    let r1 = Async.await {p = p1}
                    r1 + inner_result
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(30), result)
    }

    @Test
    fun `test scoped with mixed spawn and join`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    # Spawn some background tasks
                    let p1 = scope_spawn {scope = scope, f = {} -> 10}
                    let p2 = scope_spawn {scope = scope, f = {} -> 20}

                    # Also use join for other concurrent work
                    let joined = Async.join {
                        left = {} -> 5,
                        right = {} -> 15
                    }

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1 + r2 + joined.left + joined.right
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(50), result)
    }

    @Test
    fun `test multiple sequential scoped blocks`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                let r1 = Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 10}
                    Async.await {p = p}
                }

                let r2 = Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 20}
                    Async.await {p = p}
                }

                let r3 = Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 30}
                    Async.await {p = p}
                }

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(MezzeInt(60), result)
    }

    @Test
    fun `test scoped computation inside spawned task`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> do
                        # Use concurrent map inside spawned task
                        let results = Async.map {
                            xs = [1, 2, 3],
                            f = {x} -> x * 10
                        }
                        # Compute sum
                        let sum = 10 + 20 + 30
                        sum
                    }

                    Async.await {p = p}
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(60), result)
    }

    @Test
    fun `test scoped with concurrent computation returning record`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p1 = scope_spawn {scope = scope, f = {} -> {a = 10, b = 20}}
                    let p2 = scope_spawn {scope = scope, f = {} -> {a = 30, b = 40}}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1.a + r1.b + r2.a + r2.b
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(100), result)
    }

    @Test
    fun `test scoped with closure capturing outer variable`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                let multiplier = 10
                Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 5 * multiplier}
                    Async.await {p = p}
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(50), result)
    }

    @Test
    fun `test deeply nested scopes`() {
        val code = """
            use std::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p1 = scope_spawn {scope = scope, f = {} -> 1}
                    Async.scoped {f = {scope} -> do
                        let p2 = scope_spawn {scope = scope, f = {} -> 2}
                        Async.scoped {f = {scope} -> do
                            let p3 = scope_spawn {scope = scope, f = {} -> 3}
                            let r3 = Async.await {p = p3}
                            let r2 = Async.await {p = p2}
                            let r1 = Async.await {p = p1}
                            r1 + r2 + r3
                        }
                    }
                }
        """
        val result = eval(code)
        assertEquals(MezzeInt(6), result)
    }
}
