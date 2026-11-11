package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A fold over `iter::chain { a, b }` is rewritten into two folds — `b` folded
 * from where `a` finished — so both halves fuse by the ordinary machinery.
 *
 * WHY A REWRITE AND NOT A STAGE. `iter::chain` cannot have a `StageIR`: a stage
 * sees one element at a time and a chain is two SOURCES. So a chain anywhere in
 * a pipeline left it unfused, at 13 777 ps an element against 72 for `sum` —
 * 190x. A LEFT fold needs no stage, because folding `a ++ b` is exactly folding
 * `b` with the result of folding `a` as its seed. Measured over 1e7:
 * ~267 ms down to **5.30 ms**.
 *
 * WHAT THESE TESTS ARE FOR. The rewrite is a semantic transformation, so unlike
 * a fusion gate it can be WRONG, in three ways, and each has a test here that
 * fails when it is:
 *
 *  - **ORDER.** `a` must be folded before `b`. A commutative folder cannot tell
 *    — `sum` gives the same answer either way — so the order tests use
 *    `acc * 10 + x`, where the answer is the digits in sequence. Swapping the
 *    two halves in the rewrite fails them and nothing else.
 *  - **THE SEED.** `init` must be used once, by the inner fold. Threading it
 *    into both would add it twice.
 *  - **EMPTINESS.** Either half may be empty, and an empty half must contribute
 *    nothing rather than skip the other.
 */
class ChainFoldSequenceTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /** 1,2 then 3,4 — summed, which is order-blind and here as the baseline. */
    @Test
    fun `a chained fold sums both halves`() = assertEquals(
        10L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::range { start = 1, end = 3 },
                b = iter::range { start = 3, end = 5 },
              },
              init = 0,
              f = {acc, x} -> acc + x,
            }
            """,
        ),
    )

    /**
     * THE ORDER TEST. `acc * 10 + x` makes the result the digits in the order
     * they were visited: 1,2 then 3,4 gives 1234. Folding `b` first would give
     * 3412.
     */
    @Test
    fun `a chained fold visits the first half first`() = assertEquals(
        1234L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::range { start = 1, end = 3 },
                b = iter::range { start = 3, end = 5 },
              },
              init = 0,
              f = {acc, x} -> acc * 10 + x,
            }
            """,
        ),
    )

    /** The seed is used ONCE, by the inner fold: 7 then 1,2,3 is 7123. */
    @Test
    fun `the seed is used once and first`() = assertEquals(
        7123L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::range { start = 1, end = 3 },
                b = iter::range { start = 3, end = 4 },
              },
              init = 7,
              f = {acc, x} -> acc * 10 + x,
            }
            """,
        ),
    )

    /** An empty FIRST half contributes nothing and does not skip the second. */
    @Test
    fun `an empty first half`() = assertEquals(
        34L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::range { start = 5, end = 5 },
                b = iter::range { start = 3, end = 5 },
              },
              init = 0,
              f = {acc, x} -> acc * 10 + x,
            }
            """,
        ),
    )

    /** An empty SECOND half leaves the first half's result alone. */
    @Test
    fun `an empty second half`() = assertEquals(
        12L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::range { start = 1, end = 3 },
                b = iter::range { start = 9, end = 9 },
              },
              init = 0,
              f = {acc, x} -> acc * 10 + x,
            }
            """,
        ),
    )

    /**
     * STAGES INSIDE EACH HALF, which is where the halves must stay
     * independent: the evens of 1..6 are 2,4,6 and the second half is doubled
     * 1,2 giving 2,4. In order that is 2,4,6,2,4.
     */
    @Test
    fun `each half keeps its own stages`() = assertEquals(
        24624L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::filter { it = iter::range { start = 1, end = 7 }, p = {x} -> x % 2 == 0 },
                b = iter::map { it = iter::range { start = 1, end = 3 }, f = {x} -> x * 2 },
              },
              init = 0,
              f = {acc, x} -> acc * 10 + x,
            }
            """,
        ),
    )

    /** A CHAIN OF CHAINS, so the rewrite has to apply to its own output:
     *  1,2 then 3 then 4,5 in order is 12345. */
    @Test
    fun `a nested chain keeps its order`() = assertEquals(
        12345L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::chain {
                a = iter::chain {
                  a = iter::range { start = 1, end = 3 },
                  b = iter::range { start = 3, end = 4 },
                },
                b = iter::range { start = 4, end = 6 },
              },
              init = 0,
              f = {acc, x} -> acc * 10 + x,
            }
            """,
        ),
    )

    /** A WARM LOOP, because the rewritten chain is a compiled path: 1+2+3+4
     *  is 10 per pass, 200 passes is 2 000. */
    @Test
    fun `a chained fold stays correct in a warm loop`() = assertEquals(
        2000L,
        eval(
            """
            use std::collections::iter
            let once = {} -> iter::fold {
              it = iter::chain {
                a = iter::range { start = 1, end = 3 },
                b = iter::range { start = 3, end = 5 },
              },
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
