package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An `iter::enumerate` whose `{i, val}` record is consumed by the FOLD rather
 * than by a map stage now fuses.
 *
 * WHAT IT COST. The emitter declined any chain where the enumerate record was
 * still live at the sink — "enumerate record reaches the sink" — and that is
 * the ordinary way to use an enumerate: `iter::fold { it = enumerate(src), f =
 * {acc, x} -> acc + x.val }`. Over 1e7 against a range:
 *
 * | row | base | fused | |
 * |---|---|---|---|
 * | `enumerate+fold` reading `.val` | 700 802 348 ns | **6 797 169** | ~103x |
 * | `enumerate+fold` reading `.i` | 684 517 176 | **6 564 552** | ~104x |
 *
 * The record is never built: `x.val` is the element, which the loop already has
 * in its element local, and `x.i` is the index, which it already has in the
 * local an Enumerate stage maintains. A `MapInline` with `leftIsIndex` has
 * resolved them exactly that way all along — the fold simply was not allowed to.
 *
 * WHY THESE TESTS LOOK LIKE THIS. Resolving a field NAME to a loop local is the
 * kind of change that is silently wrong, so every case below is built so that
 * the index and the value DIFFER — `start` is never 0. A test over `0..n` cannot
 * tell `x.i` from `x.val` at all, which is exactly the test that would have
 * passed while the two were swapped.
 *
 * The gate also has to keep refusing what it cannot resolve: a third field name
 * does not exist on the record, and a filter over the record still declines
 * (`a filter over the record still answers correctly` pins the answer, not the
 * speed). A refusal here has always been a slowdown and never a wrong answer,
 * and that is the property that makes the narrow gate safe.
 */
class EnumerateSinkFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /** 10..14 — the values sum to 60, and the indices to 10, so a swap shows. */
    @Test
    fun `a folder reading val gets the element`() = assertEquals(
        60L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
              init = 0,
              f = {acc, x} -> acc + x.val,
            }
            """,
        ),
    )

    /** The same chain, reading the index: 0+1+2+3+4 = 10, not 60. */
    @Test
    fun `a folder reading i gets the index`() = assertEquals(
        10L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
              init = 0,
              f = {acc, x} -> acc + x.i,
            }
            """,
        ),
    )

    /** Both fields in one folder: 60 + 10 = 70. */
    @Test
    fun `a folder reading both fields`() = assertEquals(
        70L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
              init = 0,
              f = {acc, x} -> acc + x.val + x.i,
            }
            """,
        ),
    )

    /**
     * THE INDEX IS NOT THE VALUE MINUS THE START, which a chain with a stage
     * between the range and the enumerate makes visible: the enumerate numbers
     * what REACHES it. Filtering 10..19 to evens gives 10,12,14,16,18 — indices
     * 0..4 summing to 10, values summing to 70.
     */
    @Test
    fun `the index counts what reaches the enumerate, not the source`() = assertEquals(
        10L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::enumerate {
                it = iter::filter { it = iter::range { start = 10, end = 20 }, p = {x} -> x % 2 == 0 },
              },
              init = 0,
              f = {acc, x} -> acc + x.i,
            }
            """,
        ),
    )

    /** The same chain's values, as the control for the row above. */
    @Test
    fun `the values after a filter then an enumerate`() = assertEquals(
        70L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::enumerate {
                it = iter::filter { it = iter::range { start = 10, end = 20 }, p = {x} -> x % 2 == 0 },
              },
              init = 0,
              f = {acc, x} -> acc + x.val,
            }
            """,
        ),
    )

    /**
     * A FILTER ABOVE THE ENUMERATE, so the record is live at a stage that
     * cannot resolve it — the emitter declines the chain. Still the right
     * answer: the evens of 10..19 by value sum to 70.
     */
    @Test
    fun `a filter over the record still answers correctly`() = assertEquals(
        70L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::filter {
                it = iter::enumerate { it = iter::range { start = 10, end = 20 } },
                p = {x} -> x.val % 2 == 0,
              },
              init = 0,
              f = {acc, x} -> acc + x.val,
            }
            """,
        ),
    )

    /**
     * A WARM LOOP, because the resolution is a compiled path. 10..14 by value
     * is 60 and by index 10, so 70 per pass; 200 passes is 14 000.
     */
    @Test
    fun `an enumerate fold stays correct in a warm loop`() = assertEquals(
        14000L,
        eval(
            """
            use std::collections::iter
            let once = {} -> iter::fold {
              it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
              init = 0,
              f = {acc, x} -> acc + x.val + x.i,
            }
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + once {} }
            go { i = 200, acc = 0 }
            """,
        ),
    )
}
