package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * After an effect's handler has churned past `StabilityAssumption.CHURN_LIMIT`,
 * dispatch must still reach the handler that is actually installed.
 *
 * WHY THIS EXISTS. `PerformD.doCachedShared` used to carry
 * `assumptions = "effectStable"` as well as its guard
 * `resolveHandler(effectHash) == cachedHandler`. The guard re-resolves and
 * compares on every call, which is strictly stronger than the assumption, so
 * the assumption was redundant for correctness — but it made that tier vanish
 * at the same moment as the no-check tier. Past the churn limit (8, permanent,
 * process-global) every perform of that effect fell to `doGeneric`:
 * 25 118 ps/iter against 826 in a pristine process, 31x, for the life of the
 * process. Dropping the assumption lets the tiers DEGRADE rather than collapse
 * and measures 2.6x better.
 *
 * THE RISK THAT CREATES is a STALE CACHED HANDLER. The tier now survives churn,
 * so it keeps a `cachedHandler` across handler changes and relies entirely on
 * its guard to notice when the installed handler is a different one. These
 * tests churn past the limit and then alternate handlers, which is exactly the
 * shape that would read the stale cache if the guard were not load-bearing.
 *
 * THE LOOPS ARE LONG ON PURPOSE, and how that was established matters.
 * `excludeForUncached = true` means the uncached path skips these
 * specializations entirely, so a program that runs its handler a handful of
 * times never reaches the tier under test — the same trap that let nine of ten
 * `ResumeFallThroughSemanticsTest` cases pass because the code under test never
 * ran. At 32 iterations none of these reached it either.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL, not by argument: making
 * `doCachedShared` return a fixed value fails
 * `alternating handlers after churn each answer for themselves` and
 * `a single handler still answers after churning past the limit`, so those two
 * provably execute the changed tier.
 *
 * NOT by removing the guard, which was tried and does NOT fail them — without
 * `resolveHandler(effectHash) == cachedHandler` the DSL has nothing to select a
 * cached instance on, so the initializer stops being cached at all and the
 * result is correct-but-pointless rather than stale. The guard's job is to pick
 * the right cached instance; the DSL degrades safely without it.
 */
class EffectHandlerChurnCorrectnessTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** Two handlers for one effect, answering differently. */
    private val twoHandlers = """
        effect Pick where { pick: {} -> Int <Pick> }
        impl Pick for Ten where { pick = {} -> 10 }
        impl Pick for Twenty where { pick = {} -> 20 }
    """.trimIndent()

    @Test
    fun `a single handler still answers after churning past the limit`() {
        assertEquals(10L, MezzeTestHelper.run("""
            $twoHandlers
            let once = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + once {} }
            churn { i = 2000, acc = 0 } - 19990
        """.trimIndent()))
    }

    /**
     * THE LOAD-BEARING ONE. 32 install/uninstall pairs take the effect past the
     * churn limit, and then the two handlers alternate. Each must answer for
     * itself: a stale `cachedHandler` surviving the churn would make the second
     * one return the first one's value.
     *
     * 2000 x 10 + 2000 x 20 = 60 000.
     */
    @Test
    fun `alternating handlers after churn each answer for themselves`() {
        assertEquals(60000L, MezzeTestHelper.run("""
            $twoHandlers
            let ten = {} -> perform Ten in Pick.pick {}
            let twenty = {} -> perform Twenty in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + ten {} }
            let alternate = { i, acc } ->
              if i <= 0 then acc
              else alternate { i = i - 1, acc = acc + ten {} + twenty {} }
            do
              let _c = churn { i = 2000, acc = 0 }
              alternate { i = 2000, acc = 0 }
        """.trimIndent()))
    }

    /**
     * BOTH handlers answer within one extent, after churn — the outer for the
     * first operation and the inner for the second. This is the shape where a
     * stale cache would reach past the handler the program meant: the cached
     * entry from the outer perform must not answer the inner one.
     *
     * (A directly nested `perform Ten in perform Twenty in ...` is rejected by
     * the typechecker — the outer could never fire — so the operations are
     * separated, which is the reachable form of the same test.)
     */
    @Test
    fun `an inner handler does not inherit the outer one's cached entry`() {
        assertEquals(30L, MezzeTestHelper.run("""
            $twoHandlers
            let ten = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + ten {} }
            let both = {} -> perform Ten in do
              let outer = Pick.pick {}
              let inner = perform Twenty in Pick.pick {}
              outer + inner
            do
              let _c = churn { i = 2000, acc = 0 }
              both {}
        """.trimIndent()))
    }

    /**
     * Churning one effect must not disturb another effect's dispatch — the
     * axis `bench/cases/effectsurface` kernel 10 measures.
     */
    @Test
    fun `churning one effect leaves another effect correct`() {
        assertEquals(7L, MezzeTestHelper.run("""
            $twoHandlers
            effect Other where { other: {} -> Int <Other> }
            impl Other for Seven where { other = {} -> 7 }
            let ten = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + ten {} }
            do
              let _c = churn { i = 2000, acc = 0 }
              perform Seven in Other.other {}
        """.trimIndent()))
    }

    /**
     * A handler whose op RESUMES explicitly, after churn — covers the
     * interaction with `CallIndirectScalar1.doFallThroughResume`, which is
     * guarded on singleton identity and carries no assumption of its own.
     */
    @Test
    fun `an explicitly resuming handler is still correct after churn`() {
        assertEquals(99L, MezzeTestHelper.run("""
            effect Pick where { pick: {} -> Int <Pick> }
            impl Pick for Ten where { pick = {} -> 10 }
            impl Pick for Resumer where { pick = {} -> resume { val = 99 } }
            let ten = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + ten {} }
            do
              let _c = churn { i = 2000, acc = 0 }
              perform Resumer in Pick.pick {}
        """.trimIndent()))
    }
}
