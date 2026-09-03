package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Regression coverage: a USER newtype used in both type-system faces —
 *   - `NewtypeType`     from a VALUE      (`UserId.(5)`)
 *   - `TypeApplication` from an ANNOTATION (`{ id: UserId }`)
 * — flowing across the spawn/await lazy-call path and through `seq::map`.
 *
 * Context: the Nat→newtype migration crashed `TruffleAndLoomIntegrationTest`
 * with `no MezzeFunction registered for hash …`, and `MezzeTypeHasher` does
 * hash those two faces to different bytes. The hypothesis was that this is a
 * general newtype defect. These tests REFUTE that: an ordinary user newtype
 * behaves correctly through the same spawn/seq path (all pass). So the Nat
 * crash is narrower than the generic two-faces hashing — it is tied to Nat
 * being a STDLIB newtype crossing the stdlib→user registration boundary and
 * used implicitly via stdlib signatures, which these single-module inline
 * newtypes do not exercise. Kept as a guard that the common case stays sound;
 * the actual Nat-boundary crash is still to be diagnosed.
 */
class NewtypeHashRegressionTest {

    private val prelude = """
use std::collections::seq
use std::effects::async::{ Async, LoomAsync, await as await_result }

let unwrap_async = {r} -> match r is
    Ok { val } -> val
    Err { error = _ } -> crash { msg = "await failed" }

let spawn = {f} -> Async.spawn {f = f}
let await = {p} -> unwrap_async {r = await_result {p = p}}
"""

    private fun eval(header: String, body: String): Any =
        MezzeTestHelper.run(
            """
$prelude

$header

perform LoomAsync in do
$body
            """.trimIndent()
        )

    /** A newtype VALUE (`UserId.(7)`, NewtypeType) flows into a function whose
     *  param is ANNOTATED `: UserId` (TypeApplication), called across spawn. */
    @Test
    fun `newtype value into annotated param through spawn`() {
        val result = eval(
            header = """
                newtype UserId = Int
                let idval = { u: UserId } -> match u is
                    UserId.(v) -> v
            """.trimIndent(),
            body = """
                let u = UserId.(7)
                let p = spawn {f = {} -> idval {u = u}}
                await {p = p}
            """.trimIndent()
        )
        assertEquals(7L, result)
    }

    /** Mirrors the Nat failure exactly: a seq of newtype VALUES mapped through
     *  spawn to an annotated-param unwrap, then folded. */
    @Test
    fun `seq of newtype values mapped through spawn`() {
        val result = eval(
            header = """
                newtype Tag = Int
                let untag = { t: Tag } -> match t is
                    Tag.(v) -> v
            """.trimIndent(),
            body = """
                let tags = [Tag.(1), Tag.(2), Tag.(3), Tag.(4), Tag.(5)]
                let promises = tags |> seq::map {xs = @, f = {x} ->
                    spawn {f = {} -> untag {t = x}}}
                let results = promises |> seq::map {xs = @, f = {x} -> await {p = x}}
                results |> seq::fold {xs = @, init = 0, f = {acc, x} -> acc + x}
            """.trimIndent()
        )
        assertEquals(15L, result)
    }

    /** A spawned function RETURNS a newtype (NewtypeType), awaited, then unwrapped
     *  against the `: Money` annotation (TypeApplication). */
    @Test
    fun `spawn returns a newtype value awaited and unwrapped`() {
        val result = eval(
            header = """
                newtype Money = Int
                let cents = { m: Money } -> match m is
                    Money.(v) -> v
                let make = {} -> Money.(250)
            """.trimIndent(),
            body = """
                let p = spawn {f = {} -> make {}}
                let m = await {p = p}
                cents {m = m}
            """.trimIndent()
        )
        assertEquals(250L, result)
    }
}
