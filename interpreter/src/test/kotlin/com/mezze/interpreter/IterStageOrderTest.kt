package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A lazy `iter` pipeline must apply its stages in the order they were written.
 *
 * THE BUG. `IterNatives.foldRange` peels a map and a filter sitting above a
 * range into a single loop, which is what keeps a lazy pipeline from
 * materialising intermediates. The peel recorded WHICH stages were present but
 * not which the user wrote LAST, and the loop then always ran the predicate on
 * the RAW element. Its own comment said so — "filter first (raw element), then
 * map" — which is right for `filter(p).map(f)` and silently wrong for
 * `map(f).filter(p)`, where the predicate must see mapped values.
 *
 * Over 0..4, `map(*10).filter(<40)` therefore kept EVERYTHING and summed 100
 * where the answer is 60. No crash, no diagnostic: a wrong number.
 *
 * `xs.map(f).filter(p)` builds `Filter(Map(range))`, so an inward peel meets
 * the predicate first — that is now recorded as `filterAfterMap` and the loop
 * runs the predicate after the mapper.
 *
 * WHY THESE CASES. Many map/filter pairs give the same answer in either order,
 * which is how this survived. Each case below is chosen so the two orders
 * DISAGREE, and `seq` — the eager path, which was always correct — is the
 * control: an `iter` row that disagrees with its `seq` twin localises a
 * regression to the lazy walker.
 */
class IterStageOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    @Test
    fun `a predicate after a map sees the MAPPED element`() {
        // 0..4 -> *10 -> 0,10,20,30,40 -> keep <40 -> 0,10,20,30 -> 60.
        // Testing the raw element instead keeps all five and gives 100.
        assertEquals(
            60L,
            run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 5 }
                  .map { f = { x } -> x * 10 }
                  .filter { p = { x } -> x < 40 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a predicate that rejects everything after mapping yields the initial value`() {
        // *10 then <4 keeps only 0, so the sum is 0. Against the raw element
        // the predicate would keep 0,1,2,3 and give 60 — maximally different.
        assertEquals(
            0L,
            run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 5 }
                  .map { f = { x } -> x * 10 }
                  .filter { p = { x } -> x < 4 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a predicate before a map still sees the RAW element`() {
        // The order that always worked must keep working: keep <4 -> 0,1,2,3
        // -> *10 -> 60.
        assertEquals(
            60L,
            run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 5 }
                  .filter { p = { x } -> x < 4 }
                  .map { f = { x } -> x * 10 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `lazy iter agrees with eager seq on both orders`() {
        val prog = { coll: String, first: String, second: String ->
            """
            use std::collections::$coll
            $coll::range { start = 0, end = 5 }
              $first
              $second
              .fold { init = 0, f = { acc, x } -> acc + x }
            """.trimIndent()
        }
        val mapThenFilter = ".map { f = { x } -> x * 10 }" to ".filter { p = { x } -> x < 40 }"
        val filterThenMap = ".filter { p = { x } -> x < 4 }" to ".map { f = { x } -> x * 10 }"

        assertEquals(
            run(prog("seq", mapThenFilter.first, mapThenFilter.second)),
            run(prog("iter", mapThenFilter.first, mapThenFilter.second)),
        )
        assertEquals(
            run(prog("seq", filterThenMap.first, filterThenMap.second)),
            run(prog("iter", filterThenMap.first, filterThenMap.second)),
        )
    }

    @Test
    fun `a filter with no map is unaffected`() {
        assertEquals(
            3L,
            run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 5 }
                  .filter { p = { x } -> x < 3 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a map with no filter is unaffected`() {
        assertEquals(
            100L,
            run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 5 }
                  .map { f = { x } -> x * 10 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `stage order holds when the stage closures CAPTURE`() {
        // Same shape with the bounds arriving as parameters, so each stage
        // closure captures. 62 = 2 + (0+10+20+30).
        assertEquals(
            62L,
            run(
                """
                use std::collections::iter
                let go = { k, lim, bump } ->
                  iter::range { start = 0, end = 5 }
                    .map { f = { x } -> x * k }
                    .filter { p = { x } -> x < lim }
                    .fold { init = bump, f = { acc, x } -> acc + x }
                go { k = 10, lim = 40, bump = 2 }
                """.trimIndent(),
            ),
        )
    }
}
