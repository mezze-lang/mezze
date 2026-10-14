package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A covering run keeps covering INSIDE a resized draw.
 *
 * Every container draws its elements through `resize` (half the size), and the
 * covering performer's `resize` handed over to the plain seeded one — so from the
 * first list on, a covering run stopped enumerating and drew like any seeded run.
 * The leading choices of run `r` are its digits, whatever depth they are made at.
 */
class CoveringResizeTest {

    private fun first(run: Int): Any? = MezzeTestHelper.run("""
        use std::effects::sample::{ CoveringSampleWith, choose, resize }
        let go = {} -> perform CoveringSampleWith { seed = 99, run = $run, config = { size = 5n, enumerate = 12n } } in
          resize { n = 2n, call = {} -> choose { bound = 2 } }
        go {}
    """.trimIndent())

    @Test
    fun `the first choice inside a resize is the run's first digit`() {
        assertEquals(listOf(0L, 1L, 0L, 1L), (0..3).map { first(it) })
    }
}
