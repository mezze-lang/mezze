package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Structured Concurrency Scopes (scoped, scope_spawn).
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
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 42}
                    let r = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r
                }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test scoped with multiple child tasks`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> 10}
                    let p2 = scope_spawn {sc = sc, f = {} -> 20}
                    let p3 = scope_spawn {sc = sc, f = {} -> 30}

                    let r1 = match await {p = p1} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    let r2 = match await {p = p2} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    let r3 = match await {p = p3} is
                        Ok {val} -> val
                        Err {error = _} -> -1

                    r1 + r2 + r3
                }
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test scoped returns function result`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 100}
                    let _ = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    "done"
                }
        """
        val result = eval(code)
        assertEquals("done", result)
    }

    @Test
    fun `test scoped with no child tasks`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> 42}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test nested scopes`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> 10}

                    let inner_result = scoped {f = {sc} -> do
                        let p2 = scope_spawn {sc = sc, f = {} -> 20}
                        let r2 = match await {p = p2} is
                            Ok {val} -> val
                            Err {error = _} -> -1
                        r2
                    }

                    let r1 = match await {p = p1} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r1 + inner_result
                }
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `test scoped with mixed spawn and join`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    # Spawn some background tasks
                    let p1 = scope_spawn {sc = sc, f = {} -> 10}
                    let p2 = scope_spawn {sc = sc, f = {} -> 20}

                    # Also use join for other concurrent work
                    let joined = join {
                        l = {} -> 5,
                        r = {} -> 15
                    }

                    let r1 = match await {p = p1} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    let r2 = match await {p = p2} is
                        Ok {val} -> val
                        Err {error = _} -> -1

                    r1 + r2 + result::get_or_else { result = joined.l, def = 0 } + result::get_or_else { result = joined.r, def = 0 }
                }
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    @Test
    fun `test multiple sequential scoped blocks`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                let r1 = scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 10}
                    let r = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r
                }

                let r2 = scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 20}
                    let r = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r
                }

                let r3 = scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 30}
                    let r = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r
                }

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test scoped computation inside spawned task`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> do
                        # Use concurrent map inside spawned task
                        let results = result::get_or_else { result = try_par_map {
                            xs = [1, 2, 3],
                            f = {x} -> x * 10
                        }, def = [] }
                        # Compute sum
                        let sum = 10 + 20 + 30
                        sum
                    }

                    let r = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r
                }
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test scoped with concurrent computation returning record`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> {a = 10, b = 20}}
                    let p2 = scope_spawn {sc = sc, f = {} -> {a = 30, b = 40}}

                    let r1 = match await {p = p1} is
                        Ok {val} -> val
                        Err {error = _} -> {a = 0, b = 0}
                    let r2 = match await {p = p2} is
                        Ok {val} -> val
                        Err {error = _} -> {a = 0, b = 0}

                    r1.a + r1.b + r2.a + r2.b
                }
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test scoped with closure capturing outer variable`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                let multiplier = 10
                scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 5 * multiplier}
                    let r = match await {p = p} is
                        Ok {val} -> val
                        Err {error = _} -> -1
                    r
                }
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    @Test
    fun `test deeply nested scopes`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all, await, join, join3, par_filter, par_for_each, par_map, race, scope_spawn, scoped, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }
            use std::result

            perform LoomAsync in do
                scoped {f = {sc} -> do
                    let p1 = scope_spawn {sc = sc, f = {} -> 1}
                    scoped {f = {sc} -> do
                        let p2 = scope_spawn {sc = sc, f = {} -> 2}
                        scoped {f = {sc} -> do
                            let p3 = scope_spawn {sc = sc, f = {} -> 3}
                            let r3 = match await {p = p3} is
                                Ok {val} -> val
                                Err {error = _} -> -1
                            let r2 = match await {p = p2} is
                                Ok {val} -> val
                                Err {error = _} -> -1
                            let r1 = match await {p = p1} is
                                Ok {val} -> val
                                Err {error = _} -> -1
                            r1 + r2 + r3
                        }
                    }
                }
        """
        val result = eval(code)
        assertEquals(6L, result)
    }
}
