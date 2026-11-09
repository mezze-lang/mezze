package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `sorted_map::sum_values`, and the fold rewrite that is the reason it exists.
 *
 * `FoldToSumRewriter` turns `fold { init = 0, f = { acc, k, v } -> acc + v }`
 * into the collection's own value sum, which takes the per-element GUEST CALL
 * out of the loop. A SortedMap had no such sum to be rewritten to — the same
 * gap `nel` had before `nel::sum` — so its fold kept the call.
 *
 * `bench/cases/collfold`: `sorted_map::fold` measured 9 711 ps/elem against
 * `sorted_set::fold`'s 571, and those are the same sorted array walked the same
 * way; the sorted SET merely had a `sum` for the pass to find. Summing the
 * values with no callback is 932 — **10.4x**, with young collections 107 → 8,
 * because the callback also boxed an accumulator per element.
 *
 * WHAT THE REWRITE MUST NOT DO, and so what is tested: a folder that reads the
 * KEY is not a value sum and `sum_values` would answer it wrongly; a non-zero
 * `init` cannot be carried by a sum that starts at zero. Both must be left
 * alone, and the way to check that is the ANSWER, since a wrongly-rewritten
 * fold still returns a number.
 */
class SortedMapSumValuesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    private val build = """
        use std::collections::iter
        use std::collections::seq
        use std::collections::sorted_map
        let m = sorted_map::from_iter {
          it = iter::from_seq { xs = [
            { k = 3, v = 30 }, { k = 1, v = 10 }, { k = 2, v = 20 },
          ] }
        }
    """.trimIndent()

    @Test
    fun `sum_values adds the values`() {
        assertEquals(60L, run("$build\nsorted_map::sum_values { m = m }"))
    }

    /** The fold spelling the rewriter turns into that sum must agree with it. */
    @Test
    fun `the fold that rewrites to it gives the same answer`() {
        assertEquals(60L, run(
            "$build\nsorted_map::fold { m = m, init = 0, f = { acc, k, v } -> acc + v }"
        ))
    }

    /**
     * READS THE KEY, so it is NOT a value sum: 1+2+3 = 6, where `sum_values`
     * would answer 60. A wrongly-rewritten fold still returns a number, so the
     * value is the only thing that catches it.
     */
    @Test
    fun `a folder that sums the keys is not rewritten to a value sum`() {
        assertEquals(6L, run(
            "$build\nsorted_map::fold { m = m, init = 0, f = { acc, k, v } -> acc + k }"
        ))
    }

    /** Reads BOTH, which is neither sum: 6 + 60 = 66. */
    @Test
    fun `a folder that sums keys and values is not rewritten`() {
        assertEquals(66L, run(
            "$build\nsorted_map::fold { m = m, init = 0, f = { acc, k, v } -> acc + k + v }"
        ))
    }

    /** A non-zero init cannot ride a sum that starts at zero: 100 + 60. */
    @Test
    fun `a non-zero init is not rewritten away`() {
        assertEquals(160L, run(
            "$build\nsorted_map::fold { m = m, init = 100, f = { acc, k, v } -> acc + v }"
        ))
    }

    @Test
    fun `an empty map sums to zero`() {
        assertEquals(0L, run(
            """
            use std::collections::sorted_map
            sorted_map::sum_values { m = sorted_map::empty {} }
            """
        ))
    }

    /** Negative values, so the sum is not an accidental count or maximum. */
    @Test
    fun `negative values are summed, not counted`() {
        assertEquals(0L - 5L, run(
            """
            use std::collections::iter
            use std::collections::seq
            use std::collections::sorted_map
            let m = sorted_map::from_iter {
              it = iter::from_seq { xs = [ { k = 1, v = 0 - 10 }, { k = 2, v = 5 } ] }
            }
            sorted_map::sum_values { m = m }
            """
        ))
    }
}
