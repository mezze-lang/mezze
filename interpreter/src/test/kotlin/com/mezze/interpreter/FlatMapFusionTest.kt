package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `iter::flat_map` fused — the one stage that is not one-element-in-one-out.
 *
 * It opens an INNER LOOP at its own position and rebinds the element local, so
 * every stage after it and the SINK run per inner element. That is why it was
 * never built beside `map`, `filter` and `zip`, and it is what these tests are
 * really about: the value is easy, the SHAPE is where a nested emit goes wrong.
 *
 * MEASURED as a self-A/B, two builds from identical source bar this change,
 * because a control had moved and an older baseline could not settle it:
 * 135 405 802 ns -> 4 940 896, **27.4x**, with `filter+fold` 0.96x and
 * `filter+map+fold` 1.04x — unchanged. It beats its own measured ceiling (836
 * ps/elem against 1 397 for the same computation written as nested folds)
 * because the hand-written version still pays an `iter::fold` CALL per outer
 * element and the fused loop pays none.
 *
 * WHAT EACH CASE IS FOR. A nested emit fails in specific ways, and a sum that
 * happens to be right proves little, so the inner bounds here DEPEND on the
 * outer element and the counts differ per element:
 *  - an inner range that is EMPTY for some elements, which a loop that assumes
 *    at least one iteration gets wrong;
 *  - stages AFTER the flat_map, which must see inner elements, not outer ones —
 *    the rebinding;
 *  - a filter BEFORE it and a filter AFTER it, which open and close on opposite
 *    sides of the inner loop. The scope unwind became an ordered stack for
 *    exactly this: closing every filter first, as the old count did, is correct
 *    for only one of the two orders.
 */
class FlatMapFusionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = "use std::collections::iter"

    @Test
    fun `flat_map sums the inner ranges`() = assertEquals(
        // x in 0..4, inner x..x+3 → (0+1+2)+(1+2+3)+(2+3+4)+(3+4+5) = 30
        30L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::flat_map {
                it = iter::range { start = 0, end = 4 },
                f = { x } -> iter::range { start = x, end = x + 3 },
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /** The inner range is EMPTY for every element — `start == end`. A loop that
     *  runs its body once before testing would produce 0 + something. */
    @Test
    fun `an empty inner range contributes nothing`() = assertEquals(
        0L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::flat_map {
                it = iter::range { start = 0, end = 100 },
                f = { x } -> iter::range { start = x, end = x },
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /** Empty for SOME elements and not others, which is the case a bound
     *  computed once outside the loop would get wrong. */
    @Test
    fun `an inner range empty for some elements only`() = assertEquals(
        // inner is x..3, so x=0:0+1+2=3, x=1:1+2=3, x=2:2, x=3..4: empty → 8
        8L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::flat_map {
                it = iter::range { start = 0, end = 5 },
                f = { x } -> iter::range { start = x, end = 3 },
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /** THE REBINDING. A `map` after the flat_map must see the INNER element.
     *  If it saw the outer one the answer would be 4 * (0+1+2+3) = 24. */
    @Test
    fun `a map after the flat_map sees inner elements`() = assertEquals(
        // inner x..x+2 doubled: x=0:(0+2)=2, x=1:(2+4)=6, x=2:(4+6)=10, x=3:(6+8)=14 → 32
        32L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::map {
                it = iter::flat_map {
                  it = iter::range { start = 0, end = 4 },
                  f = { x } -> iter::range { start = x, end = x + 2 },
                },
                f = { x } -> x * 2,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /** A filter AFTER the flat_map: its scope opens INSIDE the inner loop and
     *  must close before it. */
    @Test
    fun `a filter after the flat_map filters inner elements`() = assertEquals(
        // inner x..x+3 for x in 0..4, keeping only even values:
        // 0,1,2 / 1,2,3 / 2,3,4 / 3,4,5 → evens 0,2,2,2,4,4 = 14
        14L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::filter {
                it = iter::flat_map {
                  it = iter::range { start = 0, end = 4 },
                  f = { x } -> iter::range { start = x, end = x + 3 },
                },
                p = { x } -> x % 2 == 0,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /** A filter BEFORE the flat_map: the opposite nesting order, which a
     *  close-all-filters-first unwind gets wrong. */
    @Test
    fun `a filter before the flat_map filters outer elements`() = assertEquals(
        // only even x in 0..6 reach it: x=0:(0+1+2), x=2:(2+3+4), x=4:(4+5+6) → 27
        27L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::flat_map {
                it = iter::filter {
                  it = iter::range { start = 0, end = 6 },
                  p = { x } -> x % 2 == 0,
                },
                f = { x } -> iter::range { start = x, end = x + 3 },
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /** An empty OUTER source: the inner loop must never be entered. */
    @Test
    fun `an empty outer source gives the init`() = assertEquals(
        7L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::flat_map {
                it = iter::range { start = 0, end = 0 },
                f = { x } -> iter::range { start = x, end = x + 10 },
              },
              init = 7,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /**
     * THE SHAPE THE BENCH USES, at a size whose answer is worth stating: a
     * million inner elements in total. This is the row that went 27.4x, and if
     * the fused loop ever miscounts by one element at either boundary this is
     * where it shows.
     */
    @Test
    fun `the benchmark shape agrees with its arithmetic`() = assertEquals(
        // sum over x in 0..1000 of sum(x .. x+10) = sum of (10x + 45) = 10*499500 + 45000
        5040000L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::flat_map {
                it = iter::range { start = 0, end = 1000 },
                f = { x } -> iter::range { start = x, end = x + 10 },
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """,
        ),
    )

    /**
     * A lambda with a CAPTURE is refused by the peel and falls back, because
     * the inner bounds are emitted inline into the loop where a capture has no
     * frame. The answer must still be right — a refusal declines the chain to
     * the path it took before the stage existed.
     */
    @Test
    fun `a captured bound still computes correctly through the fallback`() = assertEquals(
        // k = 2, inner x..x+2 for x in 0..4 → (0+1)+(1+2)+(2+3)+(3+4) = 16
        16L,
        eval(
            """
            $prelude
            do
              let k = 2
              iter::fold {
                it = iter::flat_map {
                  it = iter::range { start = 0, end = 4 },
                  f = { x } -> iter::range { start = x, end = x + k },
                },
                init = 0,
                f = { acc, x } -> acc + x,
              }
            """,
        ),
    )
}
