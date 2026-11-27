package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What each pattern shape MATCHES when run — the type checker accepting a pattern
 * says nothing about whether the matcher reads the right elements. Lengths are
 * checked exactly (a list pattern without `...` is exact; `...` is zero or more),
 * suffix elements after a middle `...` are read from the end, `_`/`...` bind nothing.
 */
class PatternMatchingRuntimeTest {

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    // Encodes which arm fired and what it bound, as one number per input length.
    private val shape = """
        let shape = { xs: [Int] } -> match xs is
          [first, _] -> 2000 + first
          [first, _, third] -> 3000 + first * 10 + third
          [first, ..., last] -> 9000 + first * 10 + last
          [only] -> 1000 + only
          [] -> 0
    """

    private fun shapeOf(list: String): Long =
        run("$shape\nshape { xs = $list }") as Long

    @Test fun `an exact pattern matches only its length`() {
        assertEquals(1001L, shapeOf("[1]"))
        assertEquals(2001L, shapeOf("[1, 2]"))
        assertEquals(3013L, shapeOf("[1, 2, 3]"))
    }

    @Test fun `a middle rest matches the rest and reads the last element from the end`() {
        assertEquals(9014L, shapeOf("[1, 2, 3, 4]"))
        assertEquals(9015L, shapeOf("[1, 2, 3, 4, 5]"))
    }

    @Test fun `the empty list`() = assertEquals(0L, shapeOf("[]"))

    @Test fun `a rest is zero or more - it also matches the bare minimum`() {
        assertEquals(12L, run("""
            let f = { xs: [Int] } -> match xs is
              [first, ..., last] -> first * 10 + last
              _ -> 0
            f { xs = [1, 2] }
        """))
    }

    @Test fun `a suffix-only pattern reads from the end`() {
        assertEquals(45L, run("""
            let f = { xs: [Int] } -> match xs is
              [..., a, b] -> a * 10 + b
              _ -> 0
            f { xs = [1, 2, 3, 4, 5] }
        """))
    }

    @Test fun `a named tail is a list of the remaining elements`() {
        assertEquals(15L, run("""
            let sum: { xs: [Int] } -> Int = { xs } -> match xs is
              [first, ...rest] -> first + sum { xs = rest }
              [] -> 0
            sum { xs = [1, 2, 3, 4, 5] }
        """))
    }

    @Test fun `a nested list with a middle rest binds from the end`() {
        assertEquals(19L, run("""
            let f = { xss: [[Int]] } -> match xss is
              [[a, ..., z], ...] -> a * 10 + z
              _ -> 0
            f { xss = [[1, 5, 7, 9], [2]] }
        """))
    }

    @Test fun `a payload ignored with dots`() {
        assertEquals(7L, run("""
            let f = { r: Result Int Str } -> match r is
              Ok { val } -> val
              Err { ... } -> 7
            f { r = Err { error = "x" } }
        """))
    }

    @Test fun `a let takes some record fields and ignores the rest`() {
        assertEquals(4L, run("""
            let f = { r: { a: Int, b: Int, c: Int } } -> do
              let { a, ... } = r
              a
            f { r = { a = 4, b = 5, c = 6 } }
        """))
    }

    @Test fun `a string hole matches any substring including the empty one`() {
        assertEquals(11L, run("""
            let f = { s: Str } -> match s is
              "api/$(_)" -> 1
              _ -> 0
            f { s = "api/" } * 10 + f { s = "api/users" }
        """))
    }

    @Test fun `an open callback gets exactly what the callee passes`() {
        assertEquals(1L, run("""
            let apply1: { f: { a: Int, ... } -> Int } -> Int = { f } -> f { a = 1 }
            apply1 { f = { a, ... } -> a }
        """))
    }
}
