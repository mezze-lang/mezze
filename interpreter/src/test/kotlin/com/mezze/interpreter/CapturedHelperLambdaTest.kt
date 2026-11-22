package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A lambda that calls a helper it captures runs THAT helper.
 *
 * A captured helper that captures nothing itself is called directly, by hash,
 * and the lambda's own hash did not say which helper that was. Two functions
 * that each defined a local `g` and passed `{ x } -> g { y = x }` on therefore
 * shared one lifted lambda, and the second function ran the first one's `g`:
 * `b { xs = [5] }` below answered 6. (The two helpers have the SAME type on
 * purpose: a lambda's hash covers its captures' types, which would tell them apart.)
 */
class CapturedHelperLambdaTest {
    private val head = """
        use std::collections::seq

        let a = { xs: [Int] } -> do
          let g = { y } -> y + 1
          seq::map { xs = xs, f = { x } -> g { y = x } }

        let b = { xs: [Int] } -> do
          let g = { y } -> y + 2
          seq::map { xs = xs, f = { x } -> g { y = x } }

        let first = { xs: [Int] } -> match seq::first { xs = xs } is
          Some { val } -> val
          None -> 0
    """.trimIndent()

    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")

    @Test fun `the first function's lambda calls its own helper`() {
        assertEquals(6L, run("let main = {} -> first { xs = a { xs = [5] } }\nmain {}"))
    }

    @Test fun `the second function's lambda calls ITS helper, not the first one's`() {
        assertEquals(7L, run("let main = {} -> first { xs = b { xs = [5] } }\nmain {}"))
    }

    @Test fun `both in one run`() {
        assertEquals(607L, run("let main = {} -> first { xs = a { xs = [5] } } * 100 + first { xs = b { xs = [5] } }\nmain {}"))
    }

    @Test fun `a lambda that calls an enclosing recursive helper calls that one`() {
        val src = """
            use std::collections::seq

            let up = { xs: [Int] } -> do
              let go = { n, acc } -> if n <= 0 then acc else go { n = n - 1, acc = acc + 1 }
              seq::map { xs = xs, f = { x } -> go { n = x, acc = 0 } }

            let down = { xs: [Int] } -> do
              let go = { n, acc } -> if n <= 0 then acc else go { n = n - 1, acc = acc + 2 }
              seq::map { xs = xs, f = { x } -> go { n = x, acc = 0 } }

            let first = { xs: [Int] } -> match seq::first { xs = xs } is
              Some { val } -> val
              None -> 0

            let main = {} -> first { xs = up { xs = [3] } } * 100 + first { xs = down { xs = [3] } }
            main {}
        """.trimIndent()
        assertEquals(306L, MezzeTestHelper.run(src))
    }
}
