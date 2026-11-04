package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `size` over a CHAIN counts what reaches the sink instead of materialising.
 *
 * MEASURED on `bench/cases/ds_matrix` at N=1e6, A/B on one CLI with
 * `MEZZE_SIZE_SINK=0`, min of 10:
 *
 *     seq::map    + size   9 247 376 → 6 151 ns   1503x
 *     seq::filter + size   7 567 659 → 208 724     36x
 *     list::map   + size  11 103 978 → 1 461 606    7.6x
 *     list::filter+ size   9 194 919 → 1 722 776    5.3x
 *     seq::fold (control)    382 453 → 351 125   unchanged
 *
 * `size` was not a sink, so a chain ending in it could not fuse and the stage
 * MATERIALISED a whole new collection — a box per element read, a box per
 * element written into an `Object[]`, then `SeqStorageFactory.fromArray`
 * scanning all of it with three `instanceof` checks each before unboxing into a
 * typed array. Two allocations and two extra traversals per element, to answer
 * how MANY. With a Count sink the mapped element is unused, so partial
 * evaluation reduces the map chain to the length outright, which is where the
 * three orders of magnitude come from.
 *
 * THESE TESTS EXIST BECAUSE THE BENCH CANNOT CATCH A WRONG ANSWER: `ds_matrix`
 * prints times and not values, so a 1503x "win" there is equally consistent
 * with returning nonsense. Every shape the sink has to get right is here — a
 * size-preserving stage, a filtering one, a truncating one, several together,
 * an empty source, and the bare call that must stay O(1).
 */
class SizeChainSinkTest {

    private fun run(src: String): Any? = MezzeTestHelper.run(src.trimIndent())

    private val prelude = """
        use std::collections::seq
        use std::collections::list
    """.trimIndent()

    private fun sizeOf(expr: String): Any? = run(
        """
        $prelude
        let main = {} -> do
          let xs = seq::range { start = 0, end = 10 }
          $expr
        main {}
        """,
    )

    @Test
    fun `a size-preserving stage keeps the length`() =
        assertEquals(10L, sizeOf("xs.map { f = { x } -> x * 2 }.size {}"))

    @Test
    fun `a filtering stage counts the survivors`() =
        assertEquals(5L, sizeOf("xs.filter { p = { x } -> x % 2 == 0 }.size {}"))

    @Test
    fun `a truncating stage counts what is left`() =
        assertEquals(3L, sizeOf("xs.take { n = 3 }.size {}"))

    @Test
    fun `drop counts the remainder`() =
        assertEquals(6L, sizeOf("xs.drop { n = 4 }.size {}"))

    @Test
    fun `several stages compose`() {
        // Doubling makes every element even, so the filter keeps all ten.
        assertEquals(
            10L,
            sizeOf("xs.map { f = { x } -> x * 2 }.filter { p = { x } -> x % 2 == 0 }.size {}"),
        )
        // The other order: five survive the filter, and the map preserves that.
        assertEquals(
            5L,
            sizeOf("xs.filter { p = { x } -> x % 2 == 0 }.map { f = { x } -> x + 1 }.size {}"),
        )
    }

    @Test
    fun `a filter that keeps nothing is zero`() =
        assertEquals(0L, sizeOf("xs.filter { p = { x } -> x > 100 }.size {}"))

    @Test
    fun `an empty source is zero`() = assertEquals(
        0L,
        run(
            """
            $prelude
            let main = {} -> do
              let e = seq::range { start = 0, end = 0 }
              e.map { f = { x } -> x * 2 }.size {}
            main {}
            """,
        ),
    )

    @Test
    fun `the bare call is unchanged`() {
        // No stage, so the pass refuses and this stays the O(1) native it was.
        // Routing it through a counting sink would turn a constant-time call
        // into a traversal — the one regression this change could have caused.
        assertEquals(10L, sizeOf("seq::size { xs = xs }"))
    }

    @Test
    fun `List is covered too`() {
        assertEquals(
            10L,
            run(
                """
                $prelude
                let main = {} -> do
                  let l = list::from_seq { xs = seq::range { start = 0, end = 10 } }
                  list::size { xs = list::map { xs = l, f = { x } -> x * 2 } }
                main {}
                """,
            ),
        )
        assertEquals(
            5L,
            run(
                """
                $prelude
                let main = {} -> do
                  let l = list::from_seq { xs = seq::range { start = 0, end = 10 } }
                  list::size { xs = list::filter { xs = l, p = { x } -> x % 2 == 0 } }
                main {}
                """,
            ),
        )
    }
}
