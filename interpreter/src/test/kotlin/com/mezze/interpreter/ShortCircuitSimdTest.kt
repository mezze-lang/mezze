package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `any` / `all` / `find` / `none` through the VECTORISED pipeline.
 *
 * The SIMD gate admitted only SUM, COUNT and REDUCE, so a chain under `any`
 * ran the scalar loop while the identical walk under a `fold` vectorised —
 * 477 ps/elem against 81 for a `filter+fold` doing MORE work, since the fold
 * accumulates over every match where `any` stops at the first.
 *
 * A short-circuit sink vectorises cleanly: compare a vector of lanes against
 * the threshold and test the mask. `any` is `anyTrue`, `all` is "no surviving
 * lane fails", `find` takes `firstTrue` — and lanes ascend, so the lowest set
 * lane is the first match in SOURCE order.
 *
 * WHY THESE CASES. SIMD only engages past `SIMD_THRESHOLD`, which is the
 * failure mode this family already has on record: a stage-order bug in this
 * same loop was CORRECT at small sizes and wrong at scale, because the
 * vectorised path never ran for the small ones. So every case here is sized to
 * cross the threshold, and each pins a boundary the lane logic can get wrong:
 *
 *  - a match in the FIRST lane of the FIRST vector, and in the last;
 *  - a match in the SCALAR TAIL, past the vector bound, which is a second
 *    implementation of the same predicate and can drift from the first;
 *  - `all` with a FILTER stage, where a dropped element must NOT count as a
 *    counterexample — the stage mask is ANDed in for exactly this;
 *  - `find` returning the FIRST match when several lanes match at once.
 */
class ShortCircuitSimdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = "use std::collections::iter"

    /** Well past SIMD_THRESHOLD so the vector path is the one under test. */
    private val n = 10000

    @Test
    fun `any is true when the only match is the very last element`() = assertEquals(
        true,
        eval(
            """
            $prelude
            iter::any { it = iter::range { start = 0, end = $n }, p = { x } -> x > ${n - 2} }
            """,
        ),
    )

    @Test
    fun `any is false when nothing matches`() = assertEquals(
        false,
        eval("$prelude\niter::any { it = iter::range { start = 0, end = $n }, p = { x } -> x > $n }"),
    )

    /** The first lane of the first vector — a mask read that skipped lane 0
     *  would miss it and walk the whole range to the wrong answer. */
    @Test
    fun `any matches in the first lane`() = assertEquals(
        true,
        eval("$prelude\niter::any { it = iter::range { start = 0, end = $n }, p = { x } -> x < 1 }"),
    )

    /** `all` is the counterexample form: one failure anywhere ends it. */
    @Test
    fun `all is false when exactly one element fails, at the end`() = assertEquals(
        false,
        eval("$prelude\niter::all { it = iter::range { start = 0, end = $n }, p = { x } -> x < ${n - 1} }"),
    )

    @Test
    fun `all is true when every element satisfies`() = assertEquals(
        true,
        eval("$prelude\niter::all { it = iter::range { start = 0, end = $n }, p = { x } -> x < $n }"),
    )

    /**
     * `all` WITH A FILTER. The elements the filter drops are not part of the
     * question, so a dropped element that fails the sink predicate must not
     * count as a counterexample. Here every ODD element fails `x % 2 == 0`,
     * and every odd element is also filtered out — so the answer is True, and
     * a mask that ignored the stage would say False.
     */
    @Test
    fun `a filtered-out element is not a counterexample for all`() = assertEquals(
        true,
        eval(
            """
            $prelude
            iter::all {
              it = iter::filter { it = iter::range { start = 0, end = $n }, p = { x } -> x % 2 == 0 },
              p = { x } -> x % 2 == 0,
            }
            """,
        ),
    )

    /** `find` returns the FIRST match, not merely some match: several lanes of
     *  one vector satisfy this, and only the lowest is correct. */
    @Test
    fun `find returns the first match when many match`() = assertEquals(
        5000L,
        eval(
            """
            $prelude
            match iter::find { it = iter::range { start = 0, end = $n }, p = { x } -> x > 4999 } is
              Some { val } -> val
              None -> 0 - 1
            """,
        ),
    )

    @Test
    fun `find gives None when nothing matches`() = assertEquals(
        -1L,
        eval(
            """
            $prelude
            match iter::find { it = iter::range { start = 0, end = $n }, p = { x } -> x > $n } is
              Some { val } -> val
              None -> 0 - 1
            """,
        ),
    )

    /**
     * THE SCALAR TAIL. A range whose length is deliberately NOT a multiple of
     * the vector width, with the only match in the leftover elements. The tail
     * is a second implementation of the same predicate and is where the two
     * can drift apart.
     */
    @Test
    fun `a match only in the scalar tail is still found`() {
        val odd = 10003
        assertEquals(
            true,
            eval("$prelude\niter::any { it = iter::range { start = 0, end = $odd }, p = { x } -> x > ${odd - 2} }"),
            "the last elements fall past the vector bound",
        )
        assertEquals(
            (odd - 1).toLong(),
            eval(
                """
                $prelude
                match iter::find { it = iter::range { start = 0, end = $odd }, p = { x } -> x > ${odd - 2} } is
                  Some { val } -> val
                  None -> 0 - 1
                """,
            ),
        )
    }

    /** `find` after a MAP returns the mapped value, which is what the scalar
     *  loop returns — the predicate and the result are both post-map. */
    @Test
    fun `find after a map returns the mapped element`() = assertEquals(
        10000L,
        eval(
            """
            $prelude
            match iter::find {
              it = iter::map { it = iter::range { start = 0, end = $n }, f = { x } -> x * 10 },
              p = { x } -> x > 9999,
            } is
              Some { val } -> val
              None -> 0 - 1
            """,
        ),
    )

    /** An empty source: no lane, no tail, and each sink's identity answer. */
    @Test
    fun `an empty range gives each sink its identity`() {
        assertEquals(false, eval("$prelude\niter::any { it = iter::range { start = 0, end = 0 }, p = { x } -> x > 0 }"))
        assertEquals(true, eval("$prelude\niter::all { it = iter::range { start = 0, end = 0 }, p = { x } -> x > 0 }"))
    }

    /**
     * BELOW the SIMD threshold, where the scalar loop runs. The two paths must
     * agree, and this is the size at which the stage-order bug in this loop
     * once looked correct.
     */
    @Test
    fun `small inputs agree with the vectorised answers`() {
        assertEquals(true, eval("$prelude\niter::any { it = iter::range { start = 0, end = 8 }, p = { x } -> x > 6 }"))
        assertEquals(false, eval("$prelude\niter::all { it = iter::range { start = 0, end = 8 }, p = { x } -> x < 7 }"))
        assertEquals(
            7L,
            eval(
                """
                $prelude
                match iter::find { it = iter::range { start = 0, end = 8 }, p = { x } -> x > 6 } is
                  Some { val } -> val
                  None -> 0 - 1
                """,
            ),
        )
    }
}
