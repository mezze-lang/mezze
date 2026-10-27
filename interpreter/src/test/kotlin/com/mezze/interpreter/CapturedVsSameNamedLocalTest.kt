package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A captured function wins over a same-named LOCAL binding (BUG 27).
 *
 * `compileCall` resolves a call it found no frame slot for by consulting four
 * name-keyed maps in turn. One of them was wrong and is fixed —
 * `declIndex.find { it.name == fn.name }`, the last fall-through, which answered
 * for whatever declaration shared the name and made a user's top-level
 * `let handler` run in place of a route's handler (see
 * CapturedParamNotShadowedTest).
 *
 * These cover the other two, which do NOT reproduce: `localFnAliases` and
 * `recursiveLetLambdas`. They sit EARLIER in the chain, each behind its own
 * condition — an alias only answers when its target carries dictionary methods,
 * and a recursive let takes the direct path only for a tail call — and a capture
 * satisfies neither. Kept as coverage rather than deleted, because the name-keyed
 * lookups are still there: if one of these starts answering with the local's
 * value, the same class of bug is back.
 *
 * `the alias answers when it is the one called` is the control that makes the
 * rest meaningful: it proves the alias path is live and not vacuously skipped.
 */
class CapturedVsSameNamedLocalTest {
    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    private val ord = "use std::abilities::compare::{ Ord }"

    /** A constrained alias named like the capture. 222 = the capture ran. */
    @Test fun `a constrained local alias does not answer for a captured name`() {
        assertEquals(
            222L,
            run(
                """
                $ord
                let pick_impl: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
                let mk: { pick: { a: Int, b: Int } -> Int } -> { } -> Int =
                  { pick } -> { } -> pick { a = 1, b = 2 }
                let go: {} -> Int = {} -> do
                  let pick = pick_impl
                  let g = mk { pick = { a, b } -> 222 }
                  g { }
                go {}
                """
            ),
        )
    }

    /** The control: that alias really is live. 2 = max of 1 and 2. */
    @Test fun `the alias answers when it is the one called`() {
        assertEquals(
            2L,
            run(
                """
                $ord
                let pick_impl: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
                let go: {} -> Int = {} -> do
                  let pick = pick_impl
                  pick { a = 1, b = 2 }
                go {}
                """
            ),
        )
    }

    /** A recursive local let named like the capture. */
    @Test fun `a recursive local let does not answer for a captured name`() {
        assertEquals(
            222L,
            run(
                """
                let mk: { down: { n: Int } -> Int } -> { } -> Int = { down } -> { } -> down { n = 3 }
                let go: {} -> Int = {} -> do
                  let down = { n: Int } -> if n <= 0 then 111 else down { n = n - 1 }
                  let g = mk { down = { n } -> 222 }
                  g { }
                go {}
                """
            ),
        )
    }

    /** And that recursive let still recurses — what the by-name lookup is FOR. */
    @Test fun `the recursive local let still recurses`() {
        assertEquals(
            7L,
            run(
                """
                let go: {} -> Int = {} -> do
                  let down = { n: Int } -> if n <= 0 then 7 else down { n = n - 1 }
                  down { n = 100 }
                go {}
                """
            ),
        )
    }
}
