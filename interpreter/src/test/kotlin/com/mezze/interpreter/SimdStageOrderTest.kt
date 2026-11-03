package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The VECTORISED pipeline must apply its stages in the order they were written.
 *
 * THE BUG. `FlpSimd` has two SIMD fast paths — one generating a range into lanes,
 * one loading a materialised `long[]` — and both take a `filterFirst` flag and
 * never read it. `filterFirst` appeared twice in the file, in the two signatures,
 * and nowhere in either body. Each loop computed its mask from the RAW lane
 * values and only then applied the map, which is right for `filter(p).map(f)`
 * and wrong for `map(f).filter(p)`: the predicate tested a value the user wrote
 * no predicate for. Both scalar tails had the same inversion.
 *
 * WHAT MADE IT HIDE. SIMD engages only above `SIMD_THRESHOLD` (`VL * 4`), so
 * the same program was CORRECT at small sizes and wrong at scale. Over 0..4,
 * `map(*3).filter(<5)` gave the right answer; over 0..999, `map(*3).filter(<1000)`
 * kept every element and summed 1498500 where the answer is 166833. A wrong
 * number, no crash, and invisible to any test that stayed small — which is why
 * these cases are sized past the threshold on purpose.
 *
 * It also corrupted a benchmark: `bench/cases/chain` reported a checksum that
 * only comes out if the filter kept everything, and the sweep that found this
 * showed the answers going wrong between n=5 and n=1000 while
 * `bench/cases/capchain` — the same shapes, small — stayed correct throughout.
 *
 * WHY THESE CASES. A map/filter pair usually agrees in either order, so each
 * case below is chosen so the two orders DISAGREE, and the expected value is
 * hand-checkable: for `0..n` mapped by `*3` and filtered `< n`, the survivors
 * are `x <= (n-1)/3` and the sum is `3 * (0 + … + (n-1)/3)`. Both SIMD paths are
 * covered, since `iter::range` generates lanes while `seq::range` materialises
 * an array, and they are separate loops with the same defect.
 */
class SimdStageOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    @Test
    fun `a predicate after a map sees the MAPPED lane, on the range path`() {
        // 0..999 -> *3 -> keep < 1000 -> x <= 333 -> 3 * (0+…+333) = 166833.
        // Masking on the raw lane keeps all 1000 and gives 1498500.
        assertEquals(
            166833L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::filter {
                    it = iter::map { it = iter::range { start = 0, end = 1000 }, f = { x } -> x * 3 },
                    p = { x } -> x < 1000,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a predicate after a map sees the MAPPED lane, on the array path`() {
        // `seq::range` materialises a long[], which is the other SIMD loop.
        assertEquals(
            166833L,
            run(
                """
                use std::collections::seq
                seq::fold {
                  xs = seq::filter {
                    xs = seq::map { xs = seq::range { start = 0, end = 1000 }, f = { x } -> x * 3 },
                    p = { x } -> x < 1000,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a predicate before a map still sees the RAW lane`() {
        // The order that always worked must keep working: keep x < 100 ->
        // 0..99 -> *3 -> 3 * (0+…+99) = 14850.
        assertEquals(
            14850L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::map {
                    it = iter::filter { it = iter::range { start = 0, end = 1000 }, p = { x } -> x < 100 },
                    f = { x } -> x * 3,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a count sink counts the survivors of the mapped predicate`() {
        // The mask feeds `trueCount()` rather than a masked add, so the count
        // sink is a separate use of the same mask: 3x < 1000 -> x <= 333 -> 334.
        assertEquals(
            334L,
            run(
                """
                use std::collections::seq
                seq::count {
                  xs = seq::map { xs = seq::range { start = 0, end = 1000 }, f = { x } -> x * 3 },
                  p = { x } -> x < 1000,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a size just past the SIMD threshold is already correct`() {
        // SIMD_THRESHOLD is VL*4 — at most 32 lanes for longs — so 40 elements
        // engage the vector loop while leaving a scalar tail to cover too:
        // 0..39 -> *3 -> keep < 40 -> x <= 13 -> 3 * (0+…+13) = 273.
        assertEquals(
            273L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::filter {
                    it = iter::map { it = iter::range { start = 0, end = 40 }, f = { x } -> x * 3 },
                    p = { x } -> x < 40,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a length that is not a whole number of vectors agrees with one that is`() {
        // 1000 is not a multiple of any plausible VL, so the scalar tail runs;
        // this pins that the tail applies the stages in the same order as the
        // vector body rather than its own.
        val tailed = run(
            """
            use std::collections::iter
            iter::fold {
              it = iter::filter {
                it = iter::map { it = iter::range { start = 0, end = 1001 }, f = { x } -> x * 3 },
                p = { x } -> x < 1000,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """.trimIndent(),
        )
        // Element 1000 maps to 3000, which the predicate rejects, so extending
        // the range by one must not change the sum.
        assertEquals(166833L, tailed)
    }

    @Test
    fun `the vectorised answer equals the small-size answer scaled up`() {
        // `capchain`-style control: the same kernel below the SIMD threshold,
        // where the scalar loop is the only path, must agree in shape.
        assertEquals(
            3L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::filter {
                    it = iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * 3 },
                    p = { x } -> x < 5,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }
}
