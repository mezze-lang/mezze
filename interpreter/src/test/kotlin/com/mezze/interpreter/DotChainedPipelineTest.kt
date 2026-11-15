package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A dot-chained `iter` pipeline must compute what the free-function form does.
 *
 * WHY IT IS WORTH PINNING. The two forms take different routes. A nested
 * `iter::map{...}` chain is recognised by `ChainCanonicalizer` and fused into a
 * single loop; the dot-chained `.map{...}` form is NOT, because the `Iter`
 * impl-op forwarders it goes through are never inlined —
 * `TrivialWrapperInliner.countParamReads` gives up on `MakeLambdaIR`, by a wide
 * margin the commonest shape it meets, and giving up disqualifies the whole
 * function from inlining. The canonicalizer then sees opaque impl-op calls
 * where it looks for the underlying natives and declines, so the chain runs
 * element-at-a-time through the lazy walker.
 *
 * That is a performance gap, not a correctness one — but closing it means
 * relocating function bodies that build closures, and a mis-relocated capture
 * does not crash, it reads the wrong value. So every case below pairs a
 * dot-chained pipeline with its answer, and several use stage closures that
 * CAPTURE, which is the shape a relocation must handle or refuse. A wrong
 * decision shows up here as a wrong number rather than as a slow program.
 */
class DotChainedPipelineTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `a three-stage dot-chained pipeline agrees with the free-function form`() {
        // map(*3) then filter(<30) over 0..9 keeps 0,3,…,27 → 135.
        val dotted = MezzeTestHelper.run(
            """
            use std::collections::iter
            iter::range { start = 0, end = 10 }
              .map { f = { x } -> x * 3 }
              .filter { p = { x } -> x < 30 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            """.trimIndent(),
        )
        val free = MezzeTestHelper.run(
            """
            use std::collections::iter
            iter::fold {
              it = iter::filter {
                it = iter::map { it = iter::range { start = 0, end = 10 }, f = { x } -> x * 3 },
                p = { x } -> x < 30,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """.trimIndent(),
        )
        assertEquals(135L, dotted)
        assertEquals(dotted, free)
    }

    @Test
    fun `a stage closure that CAPTURES still reads the right captured value`() {
        // `k` is captured by both lambdas. A relocated capture slot would read
        // some unrelated value rather than 10 and 2.
        assertEquals(
            // x*10 for x in 0..4 where x*10 < 40 → 0+10+20+30 = 60, then +2.
            62L,
            MezzeTestHelper.run(
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

    @Test
    fun `a filter that removes everything still folds to the initial value`() {
        assertEquals(
            7L,
            MezzeTestHelper.run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 10 }
                  .filter { p = { x } -> x > 100 }
                  .fold { init = 7, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a non-commutative fold keeps accumulator and element in order`() {
        // ((((0-0)-1)-2)-3)-4 = -10. Swapping the fold's operands gives a
        // different answer, so this pins argument order through the inlining.
        assertEquals(
            -10L,
            MezzeTestHelper.run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 5 }
                  .fold { init = 0, f = { acc, x } -> acc - x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `stages run in chain order, not reordered`() {
        // filter BEFORE map: keeps 0,1,2 then triples → 9.
        // map before filter would keep 0,3,6 → 9 as well, so use a predicate
        // that distinguishes: filter(x<3) then map(*3) → 0+3+6 = 9;
        // map(*3) then filter(x<3) → only 0 → 0.
        assertEquals(
            9L,
            MezzeTestHelper.run(
                """
                use std::collections::iter
                iter::range { start = 0, end = 10 }
                  .filter { p = { x } -> x < 3 }
                  .map { f = { x } -> x * 3 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a dot-chained seq pipeline is unaffected`() {
        // `seq` is eager and takes a different route; it must still be right.
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                seq::range { start = 0, end = 5 }
                  .map { f = { x } -> x * 3 }
                  .fold { init = 0, f = { acc, x } -> acc + x }
                """.trimIndent(),
            ),
        )
    }
}
