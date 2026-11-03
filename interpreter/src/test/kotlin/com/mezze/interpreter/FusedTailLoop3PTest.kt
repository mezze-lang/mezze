package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `FusedLongTailLoop3P` — a 3-parameter self-tail loop run as one Java loop.
 *
 * WHY IT EXISTS. The fused tiers stopped at two parameters, so a third sent a
 * loop down the general BDSL path: measured 25–34 ns/iter against 1 ns/iter
 * once fused, for arithmetic no more complex than the 2-param case.
 * `bench/cases/dispatch_probe` carries that comparison as `via_3p`.
 *
 * WHY THESE PARTICULAR CASES. The operation encodes its whole shape in a
 * hand-packed int: a comparison op, WHICH param the condition reads, three
 * update ops, which param the result comes from, and — new at this arity — a
 * two-bit NAME per update saying where its right-hand operand comes from. At
 * two params "the other one" is unambiguous and a single flag does; at three it
 * is not, and a mis-packed field does not crash, it silently computes a
 * different sum. So each test below pins one field by making a wrong value for
 * it produce a different answer:
 *
 *  - rhs source: `acc + k` vs `acc + n` differ only in that 2-bit field.
 *  - condOnIdx: the loop counts down on `n`, so reading the wrong param
 *    never terminates or terminates at once.
 *  - resultIdx: each param holds a distinguishable value at exit.
 *  - identity updates: a carried param must come out unchanged.
 *  - the OLD-value rule: all three updates read the values from before the
 *    iteration, so `n` and `acc` stepping together is observable.
 */
class FusedTailLoop3PTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `an update whose operand is another param reads that param, not a neighbour`() {
        // acc += k each iteration, 10 iterations, k = 7 → 70.
        // Reading `n` instead of `k` would give 10+9+…+1 = 55; reading the
        // literal slot would give 0. Both are distinguishable from 70.
        assertEquals(
            70L,
            MezzeTestHelper.run(
                """
                let loop3 = { n, acc, k } ->
                  if n <= 0 then acc
                  else loop3 { n = n - 1, acc = acc + k, k = k }
                loop3 { n = 10, acc = 0, k = 7 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a carried param comes out unchanged and can be the result`() {
        // k is never updated and is what the base case returns.
        assertEquals(
            99L,
            MezzeTestHelper.run(
                """
                let loop3 = { n, acc, k } ->
                  if n <= 0 then k
                  else loop3 { n = n - 1, acc = acc + 1, k = k }
                loop3 { n = 5, acc = 0, k = 99 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `every update reads the values from BEFORE the iteration`() {
        // acc += n with n also decrementing. Old-value semantics give
        // 10+9+…+1 = 55; using the already-decremented n would give
        // 9+8+…+0 = 45.
        assertEquals(
            55L,
            MezzeTestHelper.run(
                """
                let loop3 = { n, acc, k } ->
                  if n <= 0 then acc
                  else loop3 { n = n - 1, acc = acc + n, k = k }
                loop3 { n = 10, acc = 0, k = 0 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the condition can read a param other than the first`() {
        // Counts on the THIRD param. If condOnIdx were mis-packed to 0, the
        // loop would test `acc` (which only grows) and never terminate.
        assertEquals(
            12L,
            MezzeTestHelper.run(
                """
                let loop3 = { acc, step, i } ->
                  if i <= 0 then acc
                  else loop3 { acc = acc + step, step = step, i = i - 1 }
                loop3 { acc = 0, step = 4, i = 3 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a condition written with the literal on the left is handled`() {
        // `0 < n` must behave as `n > 0`; a missing swap inverts the loop.
        assertEquals(
            6L,
            MezzeTestHelper.run(
                """
                let loop3 = { n, acc, k } ->
                  if 0 < n then loop3 { n = n - 1, acc = acc + k, k = k }
                  else acc
                loop3 { n = 3, acc = 0, k = 2 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a loop that never enters returns its base value untouched`() {
        assertEquals(
            41L,
            MezzeTestHelper.run(
                """
                let loop3 = { n, acc, k } ->
                  if n <= 0 then acc
                  else loop3 { n = n - 1, acc = acc + k, k = k }
                loop3 { n = 0, acc = 41, k = 7 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a multiplying update at three params is still exact`() {
        // 2^10 = 1024, with a separate counter and a carried multiplier.
        assertEquals(
            1024L,
            MezzeTestHelper.run(
                """
                let loop3 = { n, acc, m } ->
                  if n <= 0 then acc
                  else loop3 { n = n - 1, acc = acc * m, m = m }
                loop3 { n = 10, acc = 1, m = 2 }
                """.trimIndent(),
            ),
        )
    }
}
