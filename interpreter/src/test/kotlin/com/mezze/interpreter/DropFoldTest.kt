package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A `drop` applied straight to a counted range folds into the range's start.
 *
 * `range{start, end}` yields `start + i`, so skipping the first k elements is
 * exactly `range{start + k, end}`. `FusedLongPipeline` has no drop stage at
 * all, so one drop sent the whole pipeline to the general scalar loop —
 * measured 658 ps/elem for `drop+map` against 88 for the same map alone, a 7x
 * cliff bought by a stage that only moves the start. Folding it keeps the
 * vectorised path: `drop+map` is now 84 ps.
 *
 * ONLY THE FIRST-APPLIED STAGE QUALIFIES, and the last case below is why. A
 * drop with a filter below it counts SURVIVORS, not range positions: over
 * 0..9, keeping odds and then dropping two leaves 5, 7, 9 — while moving the
 * range start by two and then filtering leaves 3, 5, 7, 9. Those differ, so a
 * fold that ignored the stage order would be a wrong answer, not a slow one.
 *
 * An over-drop needs no clamp: the element count `end - start` goes negative
 * and the loops, which run `i < n`, already treat that as empty.
 */
class DropFoldTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(body: String): Any =
        MezzeTestHelper.run("use std::collections::iter\n\n${body.trimIndent()}")

    @Test
    fun `a drop on a range skips the first elements`() {
        // 3+4+5+6+7+8+9 = 42.
        assertEquals(
            42L,
            run(
                """
                let main = {} -> iter::range { start = 0, end = 10 }
                  .drop { n = 3 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    @Test
    fun `a drop past the end yields nothing, not a negative count`() {
        assertEquals(
            7L,
            run(
                """
                let main = {} -> iter::range { start = 0, end = 5 }
                  .drop { n = 10 }
                  .fold { init = 7, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    @Test
    fun `a drop before a map keeps both`() {
        // 8,9 -> 16,18 -> 34.
        assertEquals(
            34L,
            run(
                """
                let main = {} -> iter::range { start = 0, end = 10 }
                  .drop { n = 8 }
                  .map { f = { x } -> x * 2 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    @Test
    fun `two drops compose`() {
        // Dropping 2 then 3 starts at 5: 5+6+7+8+9 = 35.
        assertEquals(
            35L,
            run(
                """
                let main = {} -> iter::range { start = 0, end = 10 }
                  .drop { n = 2 }
                  .drop { n = 3 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    @Test
    fun `a drop count from a parameter folds too`() {
        // The amount need not be a literal — it becomes `start + k`.
        // k=7 leaves 7+8+9 = 24.
        assertEquals(
            24L,
            run(
                """
                let go = { k } -> iter::range { start = 0, end = 10 }
                  .drop { n = k }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                let main = {} -> go { k = 7 }
                main {}
                """,
            ),
        )
    }

    @Test
    fun `a drop AFTER a filter counts survivors, and must not fold`() {
        // THE CASE THAT PINS THE ORDER. Keep odds from 0..9 -> 1,3,5,7,9, then
        // drop two -> 5+7+9 = 21. Moving the range start by two first and then
        // filtering gives 3,5,7,9 -> 24.
        assertEquals(
            21L,
            run(
                """
                let main = {} -> iter::range { start = 0, end = 10 }
                  .filter { p = { x } -> x % 2 != 0 }
                  .drop { n = 2 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    @Test
    fun `a drop on a non-range source is unaffected`() {
        // `seq` here is a materialised array, not a counted range, so the fold
        // does not apply and the stage stays. 3+4 = 7.
        assertEquals(
            7L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                let main = {} -> seq::fold {
                  xs = seq::drop { xs = [1, 2, 3, 4], n = 2 },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                main {}
                """.trimIndent(),
            ),
        )
    }
}
