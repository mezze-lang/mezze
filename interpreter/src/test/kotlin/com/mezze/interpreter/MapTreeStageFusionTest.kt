package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A map stage whose lambda is a pure arithmetic EXPRESSION — more than the one
 * operation `classifyUnaryLiteralLambda` reads — is emitted inline into the
 * fused loop instead of becoming a per-element call.
 *
 * WHAT IT UNLOCKS, and it is one row rather than the obvious one. An
 * `enumerate` followed by a mapper that reads the record (`{x} -> x.val + x.i`)
 * could not fuse AT ALL: a call form must never be handed the `{i, val}`
 * record, which is never built, so the chain declined. Over 1e7:
 * **490 767 951 ns to 8 773 102 — 56x.**
 *
 * WHAT IT DOES NOT BUY, measured and worth keeping written down: an ordinary
 * two-operation mapper over a range. `{x} -> x * 2` costs 1 322 610 ns and
 * `{x} -> x * 2 + 1` costs 5 794 098 both before and after, so that 4.4x is NOT
 * the per-element call — a tree emit measures the same as the call. It is
 * VECTORISATION: a one-operation `MapInline` reaches a loop the compiler
 * vectorises and anything else runs scalar. Making the tree the PREFERRED path
 * proved it from the other side: `{x} -> x * 2` emitted as a tree measured
 * 4 327 913 against 1 316 990, 3.3x worse, and took `filter+map+fold` with it.
 * So the tree is tried only where the alternative is a call.
 *
 * These tests pin the values the inline emit produces, with the enumerate cases
 * built so the index and the value DIFFER — `start` is never 0 — because that
 * is the one way this can be silently wrong.
 */
class MapTreeStageFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /** A two-operation mapper over a range: (x * 2 + 1) for 1..5 is 2+1,4+1,… = 35. */
    @Test
    fun `a two operation mapper`() = assertEquals(
        35L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 6 }, f = {x} -> x * 2 + 1 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /** Operator order inside the tree: (x + 1) * 2 for 1..4 is 4+6+8 = 18. */
    @Test
    fun `the mapper's own operator order is kept`() = assertEquals(
        18L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 4 }, f = {x} -> (x + 1) * 2 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /**
     * TWO OPERATIONS ARE EMITTED AS TWO MAP STAGES, not as one tree, because
     * `FLP_MAX_EXTRA_MAPS` is 2 and a pair of one-operation stages reaches the
     * VECTORISED loop while a tree does not: `{x} -> x * 2 + 1` went from
     * 5 565 888 ns to 1 813 544, **3.07x**, landing within 30% of the
     * one-operation row.
     *
     * A DECOMPOSITION CAN INVERT, and only a non-commutative operator shows it.
     * `x * 2 + 1` reads the same whichever way the two stages run for many
     * inputs, so these cases use SUB and DIV, where applying them in the wrong
     * order gives a different number: 10 - 3 then * 2 is 14, while * 2 then
     * - 3 is 17.
     */
    @Test
    fun `a subtract then a multiply keep their order`() = assertEquals(
        14L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 10, end = 11 }, f = {x} -> (x - 3) * 2 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /** The same two operations the other way round: 10 * 2 - 3 is 17. */
    @Test
    fun `a multiply then a subtract keep their order`() = assertEquals(
        17L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 10, end = 11 }, f = {x} -> x * 2 - 3 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /** Integer DIVISION is the least forgiving order: (20 / 3) + 1 is 7,
     *  while 20 / (3 + 1) would be 5 and (20 + 1) / 3 is 7 by accident — so
     *  the subtract pair above is the one that pins it, and this pins the
     *  divide's own placement. */
    @Test
    fun `a divide then an add keep their order`() = assertEquals(
        7L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 20, end = 21 }, f = {x} -> x / 3 + 1 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /** Over a RANGE OF ELEMENTS, so the decomposed pair runs in the fused loop
     *  rather than once: (x - 1) * 3 for 1..4 is 0 + 3 + 6 = 9. */
    @Test
    fun `a decomposed mapper over many elements`() = assertEquals(
        9L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 4 }, f = {x} -> (x - 1) * 3 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /**
     * THE ROW THIS EXISTS FOR: a mapper over an enumerate's record, reading
     * both of its fields. Values 10..14 with indices 0..4, so `val + i` is
     * 10,12,14,16,18 summing to 70. If the two resolutions were swapped the
     * answer would be the same here — so the two tests below separate them.
     */
    @Test
    fun `a mapper over an enumerate record reads both fields`() = assertEquals(
        70L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map {
                it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
                f = {x} -> x.val + x.i,
              },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /** `val * 2` over 10..14 is 20..28, summing to 120 — not 20 as indices. */
    @Test
    fun `a mapper over the record's value`() = assertEquals(
        120L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map {
                it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
                f = {x} -> x.val * 2,
              },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /** `i * 2` over the same chain is 0,2,4,6,8 summing to 20 — not 120. */
    @Test
    fun `a mapper over the record's index`() = assertEquals(
        20L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map {
                it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
                f = {x} -> x.i * 2,
              },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /**
     * A FILTER AFTER the mapped enumerate, so the record has been consumed by
     * the map and the filter sees plain values: `val + i` gives 10,12,14,16,18
     * and the ones above 13 are 14,16,18, summing to 48.
     */
    @Test
    fun `a filter after a mapped enumerate sees plain values`() = assertEquals(
        48L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::filter {
                it = iter::map {
                  it = iter::enumerate { it = iter::range { start = 10, end = 15 } },
                  f = {x} -> x.val + x.i,
                },
                p = {x} -> x > 13,
              },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /**
     * A CAPTURING MAPPER, which the tree refuses — a capture addresses the
     * lambda's capture array and the tree has no machinery to lift it. It keeps
     * the path it had, and the answer must be right: (x * 3) over 1..4 is
     * 3+6+9 = 18.
     */
    @Test
    fun `a capturing mapper still answers correctly`() = assertEquals(
        18L,
        eval(
            """
            use std::collections::iter
            let main = {} -> do
              let factor = 3
              iter::fold {
                it = iter::map { it = iter::range { start = 1, end = 4 }, f = {x} -> x * factor },
                init = 0,
                f = {acc, x} -> acc + x,
              }
            main {}
            """,
        ),
    )

    /** A WARM LOOP: 35 per pass over 200 passes is 7 000. */
    @Test
    fun `a tree mapper stays correct in a warm loop`() = assertEquals(
        7000L,
        eval(
            """
            use std::collections::iter
            let once = {} -> iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 6 }, f = {x} -> x * 2 + 1 },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + once {} }
            go { i = 200, acc = 0 }
            """,
        ),
    )
}
