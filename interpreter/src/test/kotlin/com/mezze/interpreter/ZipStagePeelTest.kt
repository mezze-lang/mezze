package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `iter::zip` is peeled into a `StageIR.Zip` carrying the second source, and
 * the chain must still compute what the program wrote.
 *
 * WHAT THIS GUARDS. The emitter has no classifier for the Zip stage yet, so it
 * DECLINES and the chain falls back to the real pipeline. That refusal is the
 * safety property, and it is not free: the stage-classification chain in
 * `BytecodeCompiler` used to end without a final `else`, so an unrecognised
 * stage was never added to `classifiedStages` and VANISHED — the loop would
 * have folded over the first source alone and reported success. PERF-LOG
 * records that shape as BUG 42, "a stage nobody wrote". Zip would have been its
 * first victim.
 *
 * So the cases below are chosen to tell "declined and fell back" apart from
 * "silently dropped", which a sum over equal-length ranges CANNOT do:
 * `zip(0..5, 0..5)` folded on `.fst` gives 10 either way. The shorter-source
 * cases give 3 when the zip is honoured and 45 when it is ignored.
 */
class ZipStagePeelTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = """
        use std::collections::iter
        use std::collections::seq
    """

    @Test
    fun `folding the first field of a zip`() = assertEquals(
        10L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 5 },
                b = iter::range { start = 100, end = 105 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            """,
        ),
    )

    /** The second field, which only exists because the second source is
     *  carried — a stage that lost it could not answer this at all. */
    @Test
    fun `folding the second field of a zip`() = assertEquals(
        510L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 5 },
                b = iter::range { start = 100, end = 105 },
              },
              init = 0,
              f = { acc, x } -> acc + x.snd,
            }
            """,
        ),
    )

    @Test
    fun `folding both fields`() = assertEquals(
        520L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 5 },
                b = iter::range { start = 100, end = 105 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst + x.snd,
            }
            """,
        ),
    )

    /**
     * THE CASE THAT DISCRIMINATES. `zip` stops at the shorter source, so this
     * is 0+1+2 = 3. A dropped zip folds `a` alone and gives 0+1+..+9 = 45.
     */
    @Test
    fun `zip stops at the shorter second source`() = assertEquals(
        3L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 10 },
                b = iter::range { start = 0, end = 3 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            """,
        ),
        "45 means the zip stage was dropped and the fold ran over `a` alone",
    )

    /** The same with the shorter source FIRST, because the bound is a min and
     *  a one-sided implementation passes only one of these two. */
    @Test
    fun `zip stops at the shorter first source`() = assertEquals(
        3L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 3 },
                b = iter::range { start = 0, end = 10 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            """,
        ),
    )

    /** A materialised seq as the second source — a different `SourceIR`
     *  through the same peel, read by index rather than counted. */
    @Test
    fun `a seq as the second source`() = assertEquals(
        60L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 3 },
                b = iter::from_seq { xs = [10, 20, 30] },
              },
              init = 0,
              f = { acc, x } -> acc + x.snd,
            }
            """,
        ),
    )

    /**
     * A zip with a stage ON the second source. The peel declines this — b's own
     * pipeline would have to be driven through the same loop — so the whole
     * chain falls back. The answer must still be right, which is the point of
     * declining rather than half-handling it.
     *
     * `map (x*2)` over 0,1,2 gives 0,2,4; summed with `.snd` that is 6.
     */
    @Test
    fun `a stage on the second source declines and still answers`() = assertEquals(
        6L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 3 },
                b = iter::map { it = iter::range { start = 0, end = 3 }, f = { x } -> x * 2 },
              },
              init = 0,
              f = { acc, x } -> acc + x.snd,
            }
            """,
        ),
    )

    /**
     * THE CASE THAT A PRECOMPUTED BOUND GETS WRONG, and the reason `ZipStep`
     * carries a counter instead.
     *
     * `zip(filter(a), b)` runs the filter BEFORE the zip, so the second source
     * must advance only for elements that SURVIVE it. `a` is 0..10 filtered to
     * evens — 0, 2, 4, 6, 8 — and `b` has three elements, so the pairs are
     * (0,100), (2,101), (4,102) and `.fst` sums to 6.
     *
     * Setting the loop bound to `min(lenA, lenB)` = 3 up front instead would
     * run the loop over a's first THREE elements, 0, 1, 2, of which only 0 and
     * 2 survive the filter — giving 2. That implementation passes every other
     * test here and the whole benchmark, because none of them put a stage
     * between the source and the zip.
     */
    @Test
    fun `a filter before the zip does not desynchronise the second source`() = assertEquals(
        6L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::filter {
                  it = iter::range { start = 0, end = 10 },
                  p = { x } -> x % 2 == 0,
                },
                b = iter::range { start = 100, end = 103 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            """,
        ),
        "2 means the bound was computed as min(lenA, lenB) before the loop, so the " +
            "filter and the second source disagree about which elements count",
    )

    /** The same shape read through `.snd`, so the second source's own elements
     *  are checked and not only how many of them were taken. */
    @Test
    fun `a filter before the zip still pairs the right second elements`() = assertEquals(
        303L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::filter {
                  it = iter::range { start = 0, end = 10 },
                  p = { x } -> x % 2 == 0,
                },
                b = iter::range { start = 100, end = 103 },
              },
              init = 0,
              f = { acc, x } -> acc + x.snd,
            }
            """,
        ),
    )

    /**
     * TWO ZIPS IN ONE CHAIN DECLINE. `SynthRec` names ONE live record and
     * cannot express a zip whose `.fst` is itself a zip, so the second one
     * refuses and the chain falls back — correct, merely slower.
     */
    @Test
    fun `a nested zip declines and still answers`() = assertEquals(
        3L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::zip {
                  a = iter::range { start = 0, end = 3 },
                  b = iter::range { start = 10, end = 13 },
                },
                b = iter::range { start = 20, end = 23 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst.fst,
            }
            """,
        ),
    )

    /** An empty second source contributes nothing and must not fold `a`. */
    @Test
    fun `an empty second source yields the init`() = assertEquals(
        0L,
        eval(
            """
            $prelude
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 0, end = 10 },
                b = iter::range { start = 0, end = 0 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            """,
        ),
        "45 means the zip was dropped; anything else means the bound is wrong",
    )
}
