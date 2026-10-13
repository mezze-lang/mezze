package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The SIZE budget decides how wild a primitive draw is.
 *
 * Every primitive generator was written for property testing alone: `int_max` one
 * draw in ten, and the rest uniform over 63 bits, so a `mez sample` row read
 * `n = 4776357352858102767` and any function that multiplies crashed. Below
 * `stress_size` a draw is now human-sized; from it up, every type's extremes come
 * back — and `std::property.check` grows the size across its runs, so a property
 * test still reaches them.
 */
class ArbitrarySizeTest {

    private val head = """
        use std::effects::sample::{ Sampler, SeededSampleWith }
        use std::abilities::arbitrary::{ Arbitrary, int_max, int_min }
        use std::collections::seq
        use std::nat::{ Nat }
        use std::nat
        use std::property::{ check }

        let one_int: Sampler Int = {} -> Arbitrary.arbitrary {}
        let one_nat: Sampler Nat = {} -> Arbitrary.arbitrary {}
        let one_float: Sampler Float = {} -> Arbitrary.arbitrary {}
        let one_str: Sampler Str = {} -> Arbitrary.arbitrary {}

        let ints: { size: Nat } -> [Int] <*> = { size } ->
          perform SeededSampleWith { seed = 11, config = { size = size, enumerate = 12n } } in
            seq::map { xs = seq::range { start = 0, end = 400 }, f = { x } -> one_int {} }
        let nats: { size: Nat } -> [Nat] <*> = { size } ->
          perform SeededSampleWith { seed = 12, config = { size = size, enumerate = 12n } } in
            seq::map { xs = seq::range { start = 0, end = 400 }, f = { x } -> one_nat {} }
        let floats: { size: Nat } -> [Float] <*> = { size } ->
          perform SeededSampleWith { seed = 13, config = { size = size, enumerate = 12n } } in
            seq::map { xs = seq::range { start = 0, end = 400 }, f = { x } -> one_float {} }
        let strs: { size: Nat } -> [Str] <*> = { size } ->
          perform SeededSampleWith { seed = 14, config = { size = size, enumerate = 12n } } in
            seq::map { xs = seq::range { start = 0, end = 400 }, f = { x } -> one_str {} }

        let count: { xs: ['a], p: { x: 'a } -> Bool } -> Int = { xs, p } ->
          seq::fold { xs = xs, init = 0, f = { acc, x } -> if p { x = x } then acc + 1 else acc }
    """.trimIndent()

    private fun run(body: String): Any? = MezzeTestHelper.run("$head\n\n${body.trimIndent()}")

    private val hostile = """["\"", "\n", "\t", "ünïcödé", "  padded  ", "a,b;c", " "]"""

    // ── the default size reads like a person wrote it ─────────────────────────

    @Test
    fun `an Int at the default size is human-sized`() {
        assertEquals(0L, run("count { xs = ints { size = 5n }, p = { x } -> if x > 32768 then True else x < 0 - 32768 }"))
    }

    @Test
    fun `an Int at the default size is not just the specials`() {
        val varied = run("count { xs = ints { size = 5n }, p = { x } -> if x > 1 then True else x < 0 - 1 }") as Long
        assertTrue(varied > 150, "too few ordinary values: $varied of 400")
    }

    @Test
    fun `a Nat at the default size is human-sized`() {
        assertEquals(0L, run("count { xs = nats { size = 5n }, p = { x } -> std::nat::to_int { n = x } > 32768 }"))
    }

    @Test
    fun `a Float at the default size is human-sized`() {
        assertEquals(0L, run("count { xs = floats { size = 5n }, p = { x } -> if x > 40000.0 then True else x < 0.0 - 40000.0 }"))
    }

    @Test
    fun `a Str at the default size is never hostile`() {
        assertEquals(0L, run("count { xs = strs { size = 5n }, p = { x } -> seq::contains { xs = $hostile, x = x } }"))
    }

    // ── a stress size still reaches every extreme ─────────────────────────────

    @Test
    fun `an Int at a stress size reaches the extremes`() {
        assertTrue((run("count { xs = ints { size = 100n }, p = { x } -> x == int_max }") as Long) > 0, "no int_max")
        assertTrue((run("count { xs = ints { size = 100n }, p = { x } -> x == int_min }") as Long) > 0, "no int_min")
        assertTrue((run("count { xs = ints { size = 100n }, p = { x } -> x > 1099511627776 }") as Long) > 0, "nothing wide")
    }

    @Test
    fun `a Float at a stress size spans exponents`() {
        assertTrue((run("count { xs = floats { size = 100n }, p = { x } -> x > 1000000000.0 }") as Long) > 0)
    }

    @Test
    fun `a Str at a stress size includes the hostile set`() {
        assertTrue((run("count { xs = strs { size = 100n }, p = { x } -> seq::contains { xs = $hostile, x = x } }") as Long) > 0)
    }

    // ── property checks still find what needs a large value ───────────────────

    /** Fails only for values no default-size draw produces: the check must GROW. */
    @Test
    fun `a property check reaches large values in its later runs`() {
        val out = run("""
            match check { gen = one_int, holds = { x } -> x < 1000000000, runs = 100, seed = 3 } is
              Passed { runs } -> "passed"
              Failed { input, original, shrinks } -> "failed"
        """)
        assertEquals("failed", out)
    }
}
