package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * WHICH SELF-TAIL TIER A SHAPE GETS, asserted instead of measured.
 *
 * The three tiers are worth two orders of magnitude between them and only one
 * of them was reportable, so losing a tier was silent — all three compute the
 * same answer, which is why no value test can tell them apart. These assert the
 * TIER, off the same `FUSED_EMITS` seam the fused chains use.
 *
 * WHAT THE TIERS COST, from `bench/cases/allocsurface` at n=1e7 and 2e7, one
 * kernel per process, min of 2, young collections beside each:
 *
 * | kernel | body | ps/iter | young |
 * |---|---|---|---|
 * | k1  | `acc + i`, 2 params        |    223 |  2 |
 * | k12 | `acc + str::size { s }`     |  4 178 |  3 |
 * | k22 | `acc + (str::size { s } + i)`|  3 915 |  3 |
 * | k21 | `acc + i + k`               | 41 065 | 86 |
 * | k23 | the same, types written out |    668 |  3 |
 *
 * k21 against k22 is the same operations with the tree nested the other way,
 * and k21 against k23 is the same expression with the parameter types spelled
 * out. 61x for the annotation, and `MEZZE_TRACE_COMPILATION` says why: the
 * inferred arm compiles `TrampolineRootNode[add]` — the `Num.add` ability
 * method, called as a real function per iteration — 36 times, and the
 * annotated arm never once.
 */
class TailLoopTierProbeTest {

    private val emitted = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        BytecodeCompiler.FUSED_EMITS.set(emitted)
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        BytecodeCompiler.FUSED_EMITS.remove()
    }

    /** The tiers recorded while compiling this snippet, in order.
     *  A top-level `let f = { … }` has NO IR name, so the tier is read off the
     *  part before the colon rather than matched to a function by name. */
    private fun tiers(): List<String> =
        emitted.filter { it.contains(':') }
            .map { it.substringBefore(':') }
            .filter { it == "fused-tail" || it == "direct-while" || it == "flag-fallback" }

    @Test
    fun `a counted loop with a non-scalar parameter takes direct-while`() {
        assertEquals(10L, MezzeTestHelper.run(
            """
            use std::str
            let go_a = { i, acc, s } ->
              if i <= 0 then acc
              else go_a { i = i - 1, acc = acc + str::size { s = s }, s = s }
            go_a { i = 10, acc = 0, s = "x" }
            """.trimIndent()
        ))
        assertEquals(listOf("direct-while"), tiers())
    }

    /**
     * ONE MORE `+` IN THE ACCUMULATOR UPDATE, and the tier does NOT change —
     * which is the point of asserting it. Both shapes are direct-while, so the
     * 10x between them in the bench is not the tier, and chasing the tier was a
     * blind alley this pins shut.
     */
    @Test
    fun `one more add does not change the tier`() {
        assertEquals(65L, MezzeTestHelper.run(
            """
            use std::str
            let go_b = { i, acc, s } ->
              if i <= 0 then acc
              else go_b { i = i - 1, acc = acc + str::size { s = s } + i, s = s }
            go_b { i = 10, acc = 0, s = "x" }
            """.trimIndent()
        ))
        assertEquals(listOf("direct-while"), tiers())
    }

    /** The same two adds with no call anywhere, which is the bench's k21. */
    @Test
    fun `two adds with no call at all is still direct-while`() {
        assertEquals(65L, MezzeTestHelper.run(
            """
            let go_d = { i, acc, k } ->
              if i <= 0 then acc
              else go_d { i = i - 1, acc = acc + i + k, k = k }
            go_d { i = 10, acc = 0, k = 1 }
            """.trimIndent()
        ))
        assertEquals(listOf("direct-while"), tiers())
    }

    /**
     * AND WRITING THE TYPES OUT DOES NOT CHANGE THE TIER EITHER, while it is
     * worth 61x in the bench — so the cost is in what the arithmetic lowers to,
     * not in which loop carries it.
     */
    @Test
    fun `annotating the parameters does not change the tier`() {
        assertEquals(65L, MezzeTestHelper.run(
            """
            let go_e: { i: Int, acc: Int, k: Int } -> Int = { i, acc, k } ->
              if i <= 0 then acc
              else go_e { i = i - 1, acc = acc + i + k, k = k }
            go_e { i = 10, acc = 0, k = 1 }
            """.trimIndent()
        ))
        assertEquals(listOf("direct-while"), tiers())
    }
}
