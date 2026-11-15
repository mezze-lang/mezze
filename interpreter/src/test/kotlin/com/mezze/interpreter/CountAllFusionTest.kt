package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `iter::count` — the no-predicate count — now fuses, and these are the shapes
 * the fused Count sink has to get right.
 *
 * WHAT IT COST. `NativeHofNames.OP_COUNT_ALL` was declared and had NO USERS:
 * the constant existed, nothing referenced it, and so the one iter sink that
 * asks only "how many" was the only one that never fused. `size` over a chain
 * fused, predicate `count` fused, `any`/`all`/`find` fused. Measured over a
 * range of 1e7, min across four interleaved processes:
 *
 * | row | base | fused | |
 * |---|---|---|---|
 * | `count` | 67 461 165 ns | **876 363** | **76.98x** |
 * | `sum` (reference) | 805 162 | 736 085 | 1.09x |
 * | `filter+fold` | 779 916 | 795 183 | 0.98x |
 * | `any` / `all` / `find` | | | 0.98-1.03x |
 * | `seq_sum` / `seq_fold` (controls) | | | 1.00x / 1.02x |
 *
 * 88x for doing LESS per element than `sum`, which walks the same source and
 * also unboxes and adds. The native walked the descriptor calling a
 * guest-visible lambda per element across a boundary; the fused sink is a
 * counting loop.
 *
 * WHY THESE TESTS AND NOT A LOWERING TEST. `BytecodeCompiler.FUSED_EMITS`
 * records nothing for an `iter` chain, and a typechecker lowering test lowers
 * the stdlib FROM SOURCE where the pipeline fuses either way — so neither can
 * tell the fused path from the native one. The evidence for the change is the
 * measurement above, on a real `mez build`, with the change's own switch.
 *
 * WHAT THESE DO CATCH, and it is the defect this sink is prone to: counting the
 * wrong POPULATION. The sink's stages run outer-to-inner, and getting that
 * order wrong makes a stage filter the SOURCE instead of the chain — the
 * predicate-`count` branch of the canonicalizer carries a comment about
 * exactly that, where `range{0,5}.map{*10}.count{<40}` answered 5 instead of 4.
 * Every chained case below has a stage that CHANGES the count, so an order
 * mistake shows up as a different number rather than as a slower loop.
 */
class CountAllFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    @Test
    fun `count over a bare range`() = assertEquals(
        10L,
        eval(
            """
            use std::collections::iter
            iter::count { it = iter::range { start = 0, end = 10 } }
            """,
        ),
    )

    @Test
    fun `count over an empty range is zero`() = assertEquals(
        0L,
        eval(
            """
            use std::collections::iter
            iter::count { it = iter::range { start = 5, end = 5 } }
            """,
        ),
    )

    /** A filter CHANGES the count, so this fails if the stage runs on the
     *  wrong population. 0..10 has five even numbers. */
    @Test
    fun `count after a filter counts what survived`() = assertEquals(
        5L,
        eval(
            """
            use std::collections::iter
            iter::count { it = iter::filter {
              it = iter::range { start = 0, end = 10 },
              p = {x} -> x % 2 == 0,
            } }
            """,
        ),
    )

    /** A map does NOT change the count, and a filter AFTER a map sees the
     *  mapped values — the exact shape whose stage order was wrong once:
     *  0..5 mapped by *10 is 0,10,20,30,40, of which four are under 40. */
    @Test
    fun `count after a map then a filter sees the mapped values`() = assertEquals(
        4L,
        eval(
            """
            use std::collections::iter
            iter::count { it = iter::filter {
              it = iter::map { it = iter::range { start = 0, end = 5 }, f = {x} -> x * 10 },
              p = {x} -> x < 40,
            } }
            """,
        ),
    )

    /** A filter that rejects everything, so a sink that counted the source
     *  instead of the chain reports 10 here. */
    @Test
    fun `count after a filter that rejects everything is zero`() = assertEquals(
        0L,
        eval(
            """
            use std::collections::iter
            iter::count { it = iter::filter {
              it = iter::range { start = 0, end = 10 },
              p = {x} -> x > 100,
            } }
            """,
        ),
    )

    /** A map alone leaves the count alone — the control for the two above. */
    @Test
    fun `count after a map is the source count`() = assertEquals(
        7L,
        eval(
            """
            use std::collections::iter
            iter::count { it = iter::map {
              it = iter::range { start = 0, end = 7 },
              f = {x} -> x * 2,
            } }
            """,
        ),
    )

    /**
     * A WARM LOOP, because the fused sink is a compiled path: a straight-line
     * call exercises only its first execution. Counting 0..100 two hundred
     * times is 20 000, and a count that drifted by one per iteration would
     * report 200 more or less.
     */
    @Test
    fun `count stays correct in a warm loop`() = assertEquals(
        20000L,
        eval(
            """
            use std::collections::iter
            let go = { i, acc } ->
              if i <= 0 then acc
              else go {
                i = i - 1,
                acc = acc + iter::count { it = iter::range { start = 0, end = 100 } },
              }
            go { i = 200, acc = 0 }
            """,
        ),
    )
}
