package com.mezze.interpreter.v2.concurrency

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
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
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ScopedConcurrencyTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Basic Scoped Tests - LoomAsync
    // =============================================================================

    @Test
    fun `test scoped with single child task`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 42}
                    Async.await {p = p}
                }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test scoped with multiple child tasks`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> 10}
                    let p2 = scope_spawn {sc = sc, f = {} -> 20}
                    let p3 = scope_spawn {sc = sc, f = {} -> 30}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}
                    let r3 = Async.await {p = p3}

                    r1 + r2 + r3
                }
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test scoped returns function result`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 100}
                    let _ = Async.await {p = p}
                    "done"
                }
        """
        val result = eval(code)
        assertEquals("done", result)
    }

    @Test
    fun `test scoped with no child tasks`() {
        val code = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> 42}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test nested scopes`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> 10}

                    let inner_result = Async.scoped {f = {sc} -> do
                        let p2 = scope_spawn {sc = sc, f = {} -> 20}
                        Async.await {p = p2}
                    }

                    let r1 = Async.await {p = p1}
                    r1 + inner_result
                }
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `test scoped with mixed spawn and join`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    # Spawn some background tasks
                    let p1 = scope_spawn {sc = sc, f = {} -> 10}
                    let p2 = scope_spawn {sc = sc, f = {} -> 20}

                    # Also use join for other concurrent work
                    let joined = Async.join {
                        l = {} -> 5,
                        r = {} -> 15
                    }

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1 + r2 + joined.l + joined.r
                }
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    @Test
    fun `test multiple sequential scoped blocks`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                let r1 = Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 10}
                    Async.await {p = p}
                }

                let r2 = Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 20}
                    Async.await {p = p}
                }

                let r3 = Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 30}
                    Async.await {p = p}
                }

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test scoped computation inside spawned task`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> do
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
        assertEquals(60L, result)
    }

    @Test
    fun `test scoped with concurrent computation returning record`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> {a = 10, b = 20}}
                    let p2 = scope_spawn {sc = sc, f = {} -> {a = 30, b = 40}}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1.a + r1.b + r2.a + r2.b
                }
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test scoped with closure capturing outer variable`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                let multiplier = 10
                Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 5 * multiplier}
                    Async.await {p = p}
                }
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    @Test
    fun `test deeply nested scopes`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> 1}
                    Async.scoped {f = {sc} -> do
                        let p2 = scope_spawn {sc = sc, f = {} -> 2}
                        Async.scoped {f = {sc} -> do
                            let p3 = scope_spawn {sc = sc, f = {} -> 3}
                            let r3 = Async.await {p = p3}
                            let r2 = Async.await {p = p2}
                            let r1 = Async.await {p = p1}
                            r1 + r2 + r3
                        }
                    }
                }
        """
        val result = eval(code)
        assertEquals(6L, result)
    }
}
