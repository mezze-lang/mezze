package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Types and shapes `Arbitrary` could not reach, or reached badly.
 *
 *  - `Bytes` had no instance at all: "Bytes does not implement 'Arbitrary'".
 *  - `Str` came from a fixed table, so the longest string in 300 draws at size 100
 *    was ten characters — no long string, no random content, ever.
 *  - Nested containers multiplied out: one `[[[Int]]]` at size 100 held 127 746
 *    ints and took ~0.8 s, in every late run of a property check.
 *  - `Option` / `Result` work through the ability; held here so they stay working.
 */
class ArbitraryCoverageTest {

    private val head = """
        use std::effects::sample::{ Sampler, SeededSampleWith }
        use std::abilities::arbitrary::{ Arbitrary }
        use std::collections::seq
        use std::collections::bytes
        use std::str
    """.trimIndent()

    private fun run(body: String): Any? = MezzeTestHelper.run("$head\n\n${body.trimIndent()}")

    private fun at(size: Int, seed: Int = 1) = "perform SeededSampleWith { seed = $seed, config = { size = ${size}n, enumerate = 12n } } in"

    @Test
    fun `Bytes has an instance, sized by the budget`() {
        val longest = run("""
            let g: Sampler Bytes = {} -> Arbitrary.arbitrary {}
            let go = {} -> do
              let bs = ${at(5)} seq::map { xs = seq::range { start = 0, end = 200 }, f = { x } -> g {} }
              seq::fold { xs = bs, init = 0, f = { acc, x } -> if bytes::size { b = x } > acc then bytes::size { b = x } else acc }
            go {}
        """) as Long
        assertTrue(longest in 1..5, "longest Bytes at size 5 was $longest")
    }

    @Test
    fun `a Str at a stress size can be long`() {
        val longest = run("""
            let g: Sampler Str = {} -> Arbitrary.arbitrary {}
            let go = {} -> do
              let xs = ${at(100)} seq::map { xs = seq::range { start = 0, end = 300 }, f = { x } -> g {} }
              seq::fold { xs = xs, init = 0, f = { acc, x } -> if str::size { s = x } > acc then str::size { s = x } else acc }
            go {}
        """) as Long
        assertTrue(longest >= 50, "longest Str in 300 draws at size 100 was $longest")
    }

    @Test
    fun `a Str at the default size stays short and readable`() {
        val longest = run("""
            let g: Sampler Str = {} -> Arbitrary.arbitrary {}
            let go = {} -> do
              let xs = ${at(5)} seq::map { xs = seq::range { start = 0, end = 300 }, f = { x } -> g {} }
              seq::fold { xs = xs, init = 0, f = { acc, x } -> if str::size { s = x } > acc then str::size { s = x } else acc }
            go {}
        """) as Long
        assertTrue(longest <= 10, "longest Str at the default size was $longest")
    }

    /** Each nesting level halves the budget, as a recursive type's does. */
    @Test
    fun `nested containers stay bounded at a large size`() {
        val total = run("""
            let g: Sampler [[[Int]]] = {} -> Arbitrary.arbitrary {}
            let inner: { ys: [[Int]] } -> Int = { ys } -> seq::fold { xs = ys, init = 0, f = { acc, x } -> acc + seq::size { xs = x } }
            let go = {} -> do
              let v = ${at(100)} g {}
              seq::fold { xs = v, init = 0, f = { acc, x } -> acc + inner { ys = x } }
            go {}
        """) as Long
        assertTrue(total <= 30000, "one [[[Int]]] at size 100 held $total ints")
    }

    @Test
    fun `Option and Result generate through the ability`() {
        val out = run("""
            let o: Sampler (Option Int) = {} -> Arbitrary.arbitrary {}
            let r: Sampler (Result Int Str) = {} -> Arbitrary.arbitrary {}
            let go = {} -> do
              let os = ${at(5)} seq::map { xs = seq::range { start = 0, end = 60 }, f = { x } -> o {} }
              let rs = ${at(5, seed = 2)} seq::map { xs = seq::range { start = 0, end = 60 }, f = { x } -> r {} }
              let nones = seq::fold { xs = os, init = 0, f = { acc, x } -> match x is
                None -> acc + 1
                Some { val } -> acc }
              let errs = seq::fold { xs = rs, init = 0, f = { acc, x } -> match x is
                Err { error } -> acc + 1
                Ok { val } -> acc }
              if nones > 0 then (if nones < 60 then (if errs > 0 then errs < 60 else False) else False) else False
            go {}
        """)
        assertEquals(true, out, "both constructors of Option and Result must appear")
    }
}
