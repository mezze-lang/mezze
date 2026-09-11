package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression for the frame-slot crash when a HOF predicate compares a
 * VARIANT-typed record field, inside a closure RETURNED by an inherent method.
 *
 * Root cause: inside a closure returned from an inherent-method op (`{ it: 'it }
 * -> { key } -> ...`), the receiver of a variant `==` (`x.m` where `m: M`, M a
 * variant) is left as an unresolved VarType at emit-scan time (the field's
 * concrete type M isn't stamped onto the node through the generic `'it`). The
 * scanner saw a VarType receiver and requested an `eq` dict-capture — but the
 * comparison is CONCRETE (M's Eq impl), so no dict exists; the closure frame is
 * sized without it and the emitted dict/local read runs off the end of the frame
 * → ArrayIndexOutOfBoundsException at the CALL of the returned closure.
 *
 * The Int control (identical shape, `n: Int`, `x.n == 1`) never used a dict, so
 * it worked — that contrast is what pinned the variant compare as the trigger.
 */
class InherentMethodClosureVariantHofTest {

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    // §2 — the crashing case: variant field-compare in a HOF predicate inside an
    // inherent-method-returned closure. Must return the key (7), not crash.
    @Test
    fun `variant field-compare in HOF predicate inside inherent-method-returned closure`() {
        val r = run(
            """
            use std::collections::seq

            type M = | Get, Post |
            type Rt = { m: M }
            type Box = { rts: [Rt] }

            let box = { rts } -> { rts = rts }

            impl Box where {
              runner = { it: 'it } ->
                { key } -> match seq::find { xs = it.rts, p = { x: Rt } -> x.m == Get } is
                  Some { val = rt } -> key
                  None -> 0
            }

            let main = {} -> do
              let g = (box { rts = [ { m = Get } ] }).runner {}
              g { key = 7 }

            main {}
            """.trimIndent()
        )
        assertEquals(7L, r, "must find the matching route by variant compare and return key")
    }

    // R9 — the Int control (must still work; guards against a fix that over-reaches).
    @Test
    fun `Int field-compare in the same shape still works`() {
        val r = run(
            """
            use std::collections::seq

            type Rt = { n: Int }
            type Box = { rts: [Rt] }

            let box = { rts } -> { rts = rts }

            impl Box where {
              runner = { it: 'it } ->
                { key } -> match seq::find { xs = it.rts, p = { x: Rt } -> x.n == 1 } is
                  Some { val = rt } -> key
                  None -> 0
            }

            let main = {} -> do
              let g = (box { rts = [ { n = 1 } ] }).runner {}
              g { key = 7 }

            main {}
            """.trimIndent()
        )
        assertEquals(7L, r)
    }

    // P-empty — predicate never runs (empty list); must return the None arm (0).
    @Test
    fun `empty list — variant predicate never runs`() {
        val r = run(
            """
            use std::collections::seq

            type M = | Get, Post |
            type Rt = { m: M }
            type Box = { rts: [Rt] }

            let box = { rts } -> { rts = rts }

            impl Box where {
              runner = { it: 'it } ->
                { key } -> match seq::find { xs = it.rts, p = { x: Rt } -> x.m == Get } is
                  Some { val = rt } -> key
                  None -> 0
            }

            let main = {} -> do
              let g = (box { rts = [] }).runner {}
              g { key = 7 }

            main {}
            """.trimIndent()
        )
        assertEquals(0L, r, "empty list -> None arm -> 0")
    }

    // The real-world shape that surfaced it: two-clause predicate (method AND key).
    @Test
    fun `two-clause predicate variant-and-int in inherent-method-returned closure`() {
        val r = run(
            """
            use std::collections::seq

            type M = | Get, Post |
            type Rt = { m: M, k: Int }
            type Box = { rts: [Rt] }

            let box = { rts } -> { rts = rts }

            impl Box where {
              runner = { it: 'it } ->
                { key } -> match seq::find { xs = it.rts, p = { x: Rt } -> x.m == Get && x.k == key } is
                  Some { val = rt } -> key
                  None -> 0
            }

            let main = {} -> do
              let g = (box { rts = [ { m = Post, k = 1 }, { m = Get, k = 7 } ] }).runner {}
              g { key = 7 }

            main {}
            """.trimIndent()
        )
        assertEquals(7L, r, "router matching method+key must find the Get/7 route")
    }
}
