package com.mezze.interpreter.typecheck

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Regression tests around `seq::fold` + `match` patterns. Originally
 * filed as #174 (alleged typechecker bug) but turned out to be two
 * separate runtime issues, both now fixed:
 *
 * 1. **Lambda param captured as itself.** `walkForCaptures` in
 *    TypedAstBuilder collected the lambda's own params (e.g. `acc` in
 *    `{acc, x} -> match x is Ok... acc + val`) as captures because the
 *    Local-binding branch was missing the `paramNames` guard that the
 *    ByHash branch already had. One-line fix in walkForCaptures.
 *
 * 2. **`SeqFoldCallNode` re-entrancy hazard.** The same node is
 *    reused across all `seq::fold` call sites in compiled stdlib
 *    code. When an outer fold's `f` lambda transitively triggered an
 *    INNER fold (e.g. `sum_arr` inside `f`), the inner call mutated
 *    the node's cached @Child DirectCallNode to point at the inner
 *    folder. When the outer fold's loop resumed, it was calling the
 *    inner lambda with outer-folder args — ABI mismatch, runtime
 *    "No matching record pattern". Fix: snapshot the resolved
 *    DirectCallNode into a local before entering the loop, and
 *    thread it through the helper chain as a parameter.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NestedResultAliasInferenceTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Try: drop the inner sum_arr, just count outer Oks. Does it reach the inner Result?
    @Test
    fun `outer-only — count Oks at top level`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all }
            use std::collections::seq

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let inner = all {ps = [p1, p2]}

                let pa = Async.spawn {f = {} -> inner}
                let groups = all {ps = [pa]}

                seq::fold {
                    xs = groups,
                    init = 0,
                    f = { acc, x } -> match x is
                        Ok { val = _ } -> acc + 1
                        Err { error = _ } -> acc
                }
        """.trimIndent()
        val result = eval(code)
        assertEquals(1L, result)
    }

    // Even simpler: single `all` over Promises returning Ints.
    @Test
    fun `simple all over Int promises`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all }
            use std::collections::seq

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let results = all {ps = [p1, p2]}

                seq::fold {
                    xs = results,
                    init = 0,
                    f = { acc, x } -> match x is
                        Ok { val } -> acc + val
                        Err { error = _ } -> acc
                }
        """.trimIndent()
        val result = eval(code)
        assertEquals(30L, result)
    }

    // Even simpler: fold over plain Ints, no match.
    @Test
    fun `fold over Ints no match`() {
        val code = """
            use std::collections::seq
            seq::fold {
                xs = [1, 2, 3],
                init = 0,
                f = { acc, x } -> acc + x
            }
        """.trimIndent()
        val result = eval(code)
        assertEquals(6L, result)
    }

    // Fold over Result without match — just identity on acc.
    @Test
    fun `fold over Result no match in body`() {
        val code = """
            use std::collections::seq
            use std::result

            let xs = [Ok { val = 10 }, Err { error = "bad" }]
            seq::fold {
                xs = xs,
                init = 0,
                f = { acc, x } -> acc
            }
        """.trimIndent()
        val result = eval(code)
        assertEquals(0L, result)
    }

    // NO async at all — fold over a static Seq of Result.
    @Test
    fun `fold over plain Seq of Result with match in body`() {
        val code = """
            use std::collections::seq
            use std::result

            let xs : Seq (Result Int Str) = [Ok { val = 10 }, Err { error = "bad" }, Ok { val = 20 }]

            seq::fold {
                xs = xs,
                init = 0,
                f = { acc, x } -> match x is
                    Ok { val } -> acc + val
                    Err { error = _ } -> acc
            }
        """.trimIndent()
        val result = eval(code)
        assertEquals(30L, result)
    }

    // Without the let — inline the seq.
    @Test
    fun `fold inline Seq of Result with match`() {
        val code = """
            use std::collections::seq
            use std::result

            seq::fold {
                xs = [Ok { val = 10 }, Err { error = "bad" }, Ok { val = 20 }],
                init = 0,
                f = { acc, x } -> match x is
                    Ok { val } -> acc + val
                    Err { error = _ } -> acc
            }
        """.trimIndent()
        val result = eval(code)
        assertEquals(30L, result)
    }

    // No `use std::result` — does Result need explicit import?
    @Test
    fun `fold over Result without result import`() {
        val code = """
            use std::collections::seq

            seq::fold {
                xs = [Ok { val = 10 }, Err { error = "bad" }],
                init = 0,
                f = { acc, x } -> match x is
                    Ok { val } -> acc + val
                    Err { error = _ } -> acc
            }
        """.trimIndent()
        val result = eval(code)
        assertEquals(10L, result)
    }

    // `all` + then iterate manually without fold.
    @Test
    fun `all then first match`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all }
            use std::collections::seq

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let results = all {ps = [p1, p2]}

                # Try just looking at the first result
                match seq::first { xs = results } is
                    Some { val } -> match val is
                        Ok { val = v } -> v
                        Err { error = _ } -> -1
                    None -> -2
        """.trimIndent()
        val result = eval(code)
        assertEquals(10L, result)
    }

    // Without `all` — just Async.await directly to compare.
    @Test
    fun `spawn await without all`() {
        val code = """
            use std::effects::async::{ Async, LoomAsync, all }

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> 42}
                match Async.await { p = p } is
                    Ok { val } -> val
                    Err { error = _ } -> -1
        """.trimIndent()
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `nested all() composed through fold infers without annotation`() {
        // Replicates the doubly-nested all-of-all pattern from
        // ParallelErrorPropagationTest > test concurrent group operations.
        // Note: fold's signature names the lambda's x-param literally `x`
        // (`f: { acc: 'b, x: 'a } -> 'b`) — Mezze lambda destructure must
        // match those field names exactly. Use `x` at both call sites.
        val code = """
            use std::effects::async::{ Async, LoomAsync, all }
            use std::collections::seq

            perform LoomAsync in do
                let create_group = {i: Int} -> do
                    let p1 = Async.spawn {f = {} -> i * 10}
                    let p2 = Async.spawn {f = {} -> i * 20}
                    all {ps = [p1, p2]}

                let g0 = Async.spawn {f = {} -> create_group {i = 0}}
                let g1 = Async.spawn {f = {} -> create_group {i = 1}}
                let groups = all {ps = [g0, g1]}

                let sum_arr = { arr } -> seq::fold {
                    xs = arr,
                    init = 0,
                    f = { acc, x } -> match x is
                        Ok { val } -> acc + val
                        Err { error = _ } -> acc
                }

                seq::fold {
                    xs = groups,
                    init = 0,
                    f = { acc, x } -> match x is
                        Ok { val } -> acc + sum_arr { arr = val }
                        Err { error = _ } -> acc
                }
        """.trimIndent()
        val result = eval(code)
        // i=0: 0+0=0; i=1: 10+20=30. Total 30.
        assertEquals(30L, result)
    }
}
