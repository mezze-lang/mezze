package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A FUSED pipeline must apply its stages in the order they were written.
 *
 * HOW ORDER IS CARRIED. `ChainCanonicalizer.peelSource` peels
 * map/filter/take/drop layers off a pipeline so the whole chain becomes one
 * loop, and returns them OUTER-TO-INNER: `stages[0]` is the stage applied
 * LAST. `BytecodeCompiler.emitCanonicalIterChain` reverses the classified
 * list once, and everything after that — emission order, the `filterFirst`
 * flag, the take-after-filter refusal — reads application order.
 *
 * THE BUG. A stage list built by hand has to match that convention, and the
 * `count` sink's did not. `count` has no filter stage of its own: the
 * canonicalizer adds its predicate to the peeled stages, and that predicate is
 * applied LAST, so it belongs at the FRONT. It was APPENDED instead, becoming
 * the innermost stage — so a predicate written for mapped values was tested
 * against the source. `range{0,5}.map{*10}.count{<40}` answered 5 where the
 * answer is 4: every raw element is under 40, so every one passed. No crash,
 * no diagnostic, a wrong number — in the machinery that exists purely to make
 * pipelines fast.
 *
 * WHY THE FREE-FUNCTION FORM. These are written as nested `iter::map{...}`
 * calls rather than `.map{...}` chains on purpose: that is the form the
 * canonicalizer fuses today, so these cases actually reach the fused loop. A
 * dot-chained pipeline does not fuse yet and runs the lazy walker instead —
 * guarded separately by `IterStageOrderTest` and `DotChainedPipelineTest`.
 * Three routes to the same answer, so a divergence localises itself.
 *
 * WHY THESE CASES. Most map/filter pairs give the same answer in either order,
 * which is how the count bug survived. Every case below is chosen so the two
 * orders DISAGREE, and each expected value is hand-checked in a comment. The
 * cases beyond `count` were correct already; they are here because this is the
 * path where a reordering is both easy to introduce and invisible.
 */
class FusedChainStageOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    @Test
    fun `a fused predicate after a map sees the MAPPED element`() {
        // 0..4 -> *10 -> 0,10,20,30,40 -> keep <40 -> 60.
        // Against the raw element the predicate keeps all five -> 100.
        assertEquals(
            60L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::filter {
                    it = iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * 10 },
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
    fun `a fused predicate before a map sees the RAW element`() {
        // keep <4 -> 0,1,2,3 -> *10 -> 60. Applying the predicate after the
        // map instead keeps only x*10 < 4, i.e. just 0 -> 0.
        assertEquals(
            60L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::map {
                    it = iter::filter { it = iter::range { start = 0, end = 5 }, p = { x } -> x < 4 },
                    f = { x } -> x * 10,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a take before a filter clamps the SOURCE, not the survivors`() {
        // take 3 of 0..9 -> 0,1,2 -> keep odd -> 1. Filtering first would keep
        // 1,3,5,7,9 and take three of those -> 9.
        assertEquals(
            1L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::filter {
                    it = iter::take { it = iter::range { start = 0, end = 10 }, n = 3 },
                    p = { x } -> x % 2 != 0,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a take after a filter counts the SURVIVORS`() {
        // keep odd -> 1,3,5,7,9 -> take 2 -> 4. A source-count clamp would
        // take 0,1 and then filter -> 1. This is the shape the fused long
        // pipeline must DECLINE, since it can only clamp the source.
        assertEquals(
            4L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::take {
                    it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x % 2 != 0 },
                    n = 2,
                  },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a count predicate runs after the pipeline's own stages`() {
        // The count sink appends its predicate as a final stage: 0..4 -> *10
        // -> 0,10,20,30,40 -> count <40 -> 4. Counting raw elements gives 5.
        assertEquals(
            4L,
            run(
                """
                use std::collections::seq
                seq::count {
                  xs = seq::map { xs = seq::range { start = 0, end = 5 }, f = { x } -> x * 10 },
                  p = { x } -> x < 40,
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `eager seq fuses in the same order as lazy iter`() {
        // `seq` is eager and `iter` lazy, but the fused path is shared, so a
        // disagreement here means one of the two routes reordered stages.
        val seqSum = run(
            """
            use std::collections::seq
            seq::fold {
              xs = seq::filter {
                xs = seq::map { xs = seq::range { start = 0, end = 5 }, f = { x } -> x * 10 },
                p = { x } -> x < 40,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """.trimIndent(),
        )
        assertEquals(60L, seqSum)
    }

    @Test
    fun `a fused pipeline agrees with the unfused dot-chained form`() {
        // Same program two ways: nested calls fuse, the dot chain does not.
        // Both must produce 60.
        val fused = run(
            """
            use std::collections::iter
            iter::fold {
              it = iter::filter {
                it = iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * 10 },
                p = { x } -> x < 40,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """.trimIndent(),
        )
        val dotted = run(
            """
            use std::collections::iter
            iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 10 }
              .filter { p = { x } -> x < 40 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            """.trimIndent(),
        )
        assertEquals(60L, fused)
        assertEquals(fused, dotted)
    }

    @Test
    fun `a single stage is unaffected by the order convention`() {
        // One stage cannot be misordered; these pin that the reversal did not
        // break the common single-stage pipelines.
        assertEquals(
            100L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * 10 },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            3L,
            run(
                """
                use std::collections::iter
                iter::fold {
                  it = iter::filter { it = iter::range { start = 0, end = 5 }, p = { x } -> x < 3 },
                  init = 0,
                  f = { acc, x } -> acc + x,
                }
                """.trimIndent(),
            ),
        )
    }
}
