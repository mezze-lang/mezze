package com.mezze.interpreter.collections

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `nonemptylist::sum` — a new stdlib function, so it gets its own value tests
 * rather than only the lowering test that motivated it.
 *
 * It was MISSING, and the absence was costing 8.9 ns per element.
 * `FoldToSumRewriter` turns a plain summing fold into the collection's own
 * `sum`, which walks without calling back into guest code, and every
 * element-based collection had one except this. `bench/cases/fuseall` kernel 3:
 * **8 858 ps/elem to 1 206, 7.3x**, with the seq control steady at 372-391 and
 * the deque and set rows unmoved.
 *
 * A `NonEmptyList` cannot be empty, so there is no empty case to pin — the
 * single-element one is the floor.
 */
class NonEmptyListSumTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(body: String): Any = MezzeTestHelper.run(
        "use std::collections::nonemptylist\nuse std::collections::seq\n\n${body.trimIndent()}",
    )

    @Test
    fun `sums the elements`() {
        val out = run(
            """
            let main = {} -> match nonemptylist::from_seq { xs = [1, 2, 3, 4] } is
              Some { val } -> nonemptylist::sum { xs = val }
              None -> 0 - 1
            main {}
            """,
        )
        assertEquals(10L, out)
    }

    /** The floor: one element. */
    @Test
    fun `a single element sums to itself`() {
        val out = run(
            """
            let main = {} -> match nonemptylist::from_seq { xs = [7] } is
              Some { val } -> nonemptylist::sum { xs = val }
              None -> 0 - 1
            main {}
            """,
        )
        assertEquals(7L, out)
    }

    /**
     * `sum` must agree with the `fold_left` it replaces — that equality is the
     * whole licence for the rewrite, and negatives are where a sloppy
     * accumulator would show.
     */
    @Test
    fun `sum agrees with fold_left over negatives`() {
        val out = run(
            """
            let main = {} -> match nonemptylist::from_seq { xs = [5, 0 - 3, 10, 0 - 12] } is
              Some { val } -> do
                let viaSum = nonemptylist::sum { xs = val }
                let viaFold = nonemptylist::fold_left {
                  xs = val, init = 0, f = { acc, x } -> acc + x
                }
                viaSum - viaFold
              None -> 0 - 1
            main {}
            """,
        )
        assertEquals(0L, out, "nonemptylist::sum disagreed with the fold_left it replaces")
    }
}
