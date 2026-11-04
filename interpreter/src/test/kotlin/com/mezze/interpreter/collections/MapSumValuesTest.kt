package com.mezze.interpreter.collections

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `map::sum_values` — a new stdlib function, so it gets its own value tests
 * beyond the lowering test that motivated it.
 *
 * Named `sum_values` and not `sum` because a map has TWO things to sum and the
 * ambiguity would be the whole bug: a `sum` that silently meant values would
 * answer a key sum with the wrong column. The rewriter enforces the same
 * distinction structurally — it only redirects a folder that reads the value and
 * never the key.
 *
 * It exists because the map fold pays three costs where every other collection
 * pays one: `MezzeMap`'s iterator allocates `new Entry<>(keys[i], values[i])`
 * per element, the callback takes three arguments, and the keys are read and
 * boxed even though a value sum never looks at them. Keys and values are
 * parallel arrays, so `MapStorage.forEach` walks them with no intermediate
 * array and no Entry.
 */
class MapSumValuesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(body: String): Any = MezzeTestHelper.run(
        "use std::collections::map\nuse std::collections::seq\nuse std::collections::list\n\n${body.trimIndent()}",
    )

    /** Values 10+20+30, deliberately different from the keys so a column mix-up shows. */
    @Test
    fun `sums the values and not the keys`() {
        val out = run(
            """
            let main = {} -> do
              let m = map::from_list {
                xs = list::from_seq { xs = [{ k = 1, v = 10 }, { k = 2, v = 20 }, { k = 3, v = 30 }] },
              }
              map::sum_values { m = m }
            main {}
            """,
        )
        // Keys sum to 6, values to 60. Reading 6 here would mean the wrong column.
        assertEquals(60L, out)
    }

    @Test
    fun `an empty map sums to zero`() {
        val out = run(
            """
            let main = {} -> map::sum_values { m = map::empty {} }
            main {}
            """,
        )
        assertEquals(0L, out)
    }

    /**
     * `sum_values` must agree with the `fold` it replaces — that equality is the
     * whole licence for the rewrite, and negatives are where a sloppy
     * accumulator would show.
     */
    @Test
    fun `sum_values agrees with the fold it replaces`() {
        val out = run(
            """
            let main = {} -> do
              let m = map::from_list {
                xs = list::from_seq {
                  xs = [{ k = 1, v = 5 }, { k = 2, v = 0 - 3 }, { k = 3, v = 12 }, { k = 4, v = 0 - 20 }],
                },
              }
              let viaSum = map::sum_values { m = m }
              let viaFold = map::fold { m = m, init = 0, f = { acc, k, v } -> acc + v }
              viaSum - viaFold
            main {}
            """,
        )
        assertEquals(0L, out, "map::sum_values disagreed with the fold it replaces")
    }

    /**
     * And the rewrite must leave a KEY fold alone — checked by VALUE here, not
     * only by the lowered shape, because getting this wrong returns a plausible
     * number rather than failing.
     */
    @Test
    fun `a key fold still sums keys`() {
        val out = run(
            """
            let main = {} -> do
              let m = map::from_list {
                xs = list::from_seq { xs = [{ k = 1, v = 10 }, { k = 2, v = 20 }, { k = 3, v = 30 }] },
              }
              map::fold { m = m, init = 0, f = { acc, k, v } -> acc + k }
            main {}
            """,
        )
        assertEquals(6L, out, "a key fold returned the value sum — the rewrite took a fold it must not")
    }
}
