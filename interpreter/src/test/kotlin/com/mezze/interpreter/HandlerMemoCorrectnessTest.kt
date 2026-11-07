package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `HandlerChainRegistry.peekHandlerForEffect` answers from a one-entry memo,
 * and must still name the handler the program actually installed.
 *
 * WHY THE MEMO EXISTS. It is the hot path of `PerformD`'s verify dispatch tier
 * — the tier every effect op takes once that effect's per-effect assumption has
 * churned past `StabilityAssumption.CHURN_LIMIT` (8, permanent, process-global),
 * which is for the life of the process. `bench/cases/effectsurface`:
 *
 *     kernel 9, op after churn   9 719 -> 3 841   2.53x
 *     kernel 3, pristine (ctrl)      820    849   flat
 *     kernel 7, depth 4   (ctrl)     945    877   flat
 *
 * HOW THE COST WAS ATTRIBUTED, because the first guess was wrong. The lookup
 * sits behind a `@TruffleBoundary`, so the boundary crossing looked like the
 * cost. Removing it measured **5.8x WORSE** (56 862 against 9 769): keeping the
 * body out of the compiled graph is what keeps the perform site compact. A
 * measurement-only probe that replaced the BODY with a static one-entry memo
 * then read 1 723 — so ~8 of the ~9 ns is the body (`ThreadLocal` probe, stack
 * scan, `Hash.equals` falling through to a 128-char `String.equals` on a match)
 * and under 1 ns is the crossing. The memo is the sound form of that probe.
 *
 * WHY THE KEY IS SOUND. `(effect hash IDENTITY, depth, generation)`. Every site
 * that changes chain contents also changes depth, generation or both — each slot
 * write is paired with a depth change, and the two depth changes that skip the
 * generation bump (`clearHandlerChain`, `popHandlerChain`) both move depth. That
 * is the same invariant `snapshotHandlerChainEntries` already relies on.
 * Identity rather than equality on the hash is deliberate: a different-but-equal
 * `Hash` object misses and rescans, which is slower and never wrong.
 *
 * WHAT THESE GUARD. A STALE HANDLER. The shape that would break is shadowing:
 * an inner handler for the same effect pushes, is memoised, pops, and the outer
 * perform must re-resolve rather than read the inner answer back. The tests
 * below put an outer operation, an inner one and a second outer one in a single
 * extent so the memo is written between them.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL: dropping `depth` and `generation` from
 * the key — a memo that always hits once warm — fails FOUR of the seven below.
 * The three it does not fail install exactly ONE handler for the effect they
 * query, so a stale entry holds the right answer anyway and there is nothing to
 * be confused with; they are here for the alternating and deep-chain paths, not
 * for staleness. Stated as the sentinel supports it rather than as the count
 * first guessed, which was five.
 *
 * Unlike `EffectHandlerChurnCorrectnessTest` these need no long loops. The memo
 * is read by `YieldDispatch` on the generic path as well as by the cached
 * tiers' guard, so a short program reaches it; one test is warm anyway, because
 * the compiled path is where the memo is supposed to pay.
 */
class HandlerMemoCorrectnessTest {

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

    /**
     * THE LOAD-BEARING ONE. Outer, inner, outer again, in one extent. The inner
     * `perform Twenty` pushes a second handler for the SAME effect, so the memo
     * is written with Twenty's answer; when it pops, the third operation must
     * re-resolve to Ten.
     *
     * 10 + 20 + 10 = 40. A memo that ignored depth/generation would answer the
     * third operation with Twenty and return 50.
     */
    @Test
    fun `the outer handler answers again after an inner one shadowed it`() {
        assertEquals(40L, MezzeTestHelper.run("""
            $twoHandlers
            let probe = {} -> perform Ten in do
              let a = Pick.pick {}
              let b = perform Twenty in Pick.pick {}
              let c = Pick.pick {}
              a + b + c
            probe {}
        """.trimIndent()))
    }

    /**
     * The same shape WARM, so the memo is also exercised on the compiled path,
     * where it is the thing the 2.53x comes from. 2000 x 40 = 80 000.
     */
    @Test
    fun `shadowing stays correct in a warm loop`() {
        assertEquals(80000L, MezzeTestHelper.run("""
            $twoHandlers
            let probe = {} -> perform Ten in do
              let a = Pick.pick {}
              let b = perform Twenty in Pick.pick {}
              let c = Pick.pick {}
              a + b + c
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + probe {} }
            go { i = 2000, acc = 0 }
        """.trimIndent()))
    }

    /**
     * Shadowing twice over, so a stale entry has two chances to leak: the memo
     * is written by the first inner operation and must not answer the second
     * inner one either, which runs under a different handler again.
     *
     * 10 + 20 + 10 + 10 = 50, with the second inner extent being Ten nested
     * inside Twenty.
     */
    @Test
    fun `nesting the other way round also re-resolves`() {
        assertEquals(50L, MezzeTestHelper.run("""
            $twoHandlers
            let probe = {} -> perform Twenty in do
              let a = perform Ten in Pick.pick {}
              let b = Pick.pick {}
              let c = perform Ten in Pick.pick {}
              let d = perform Ten in Pick.pick {}
              a + b + c + d
            probe {}
        """.trimIndent()))
    }

    /**
     * Install, pop, install the OTHER handler, in sequence rather than nested.
     * Each extent writes the memo; the next must not read it back.
     *
     * 10 + 20 + 10 + 20 = 60.
     */
    @Test
    fun `sequential extents with different handlers each answer for themselves`() {
        assertEquals(60L, MezzeTestHelper.run("""
            $twoHandlers
            let ten = {} -> perform Ten in Pick.pick {}
            let twenty = {} -> perform Twenty in Pick.pick {}
            ten {} + twenty {} + ten {} + twenty {}
        """.trimIndent()))
    }

    /**
     * TWO EFFECTS ALTERNATING, which is the case a ONE-entry memo keyed on hash
     * identity thrashes: every lookup misses and rescans. Correctness must not
     * depend on the memo hitting.
     *
     * 10 + 7 + 10 + 7 = 34.
     */
    @Test
    fun `alternating two effects resolves each to its own handler`() {
        assertEquals(34L, MezzeTestHelper.run("""
            $twoHandlers
            effect Other where { other: {} -> Int <Other> }
            impl Other for Seven where { other = {} -> 7 }
            let probe = {} -> perform Ten in perform Seven in do
              let a = Pick.pick {}
              let b = Other.other {}
              let c = Pick.pick {}
              let d = Other.other {}
              a + b + c + d
            probe {}
        """.trimIndent()))
    }

    /**
     * A chain of four handlers for four different effects, each operation
     * resolving past the three that do not match it — the memo must not answer
     * a query for one effect with another's handler.
     *
     * 1 + 2 + 4 + 8 = 15, then each again = 30.
     */
    @Test
    fun `a chain of four effects resolves each one past the others`() {
        assertEquals(30L, MezzeTestHelper.run("""
            effect E1 where { o1: {} -> Int <E1> }
            effect E2 where { o2: {} -> Int <E2> }
            effect E3 where { o3: {} -> Int <E3> }
            effect E4 where { o4: {} -> Int <E4> }
            impl E1 for H1 where { o1 = {} -> 1 }
            impl E2 for H2 where { o2 = {} -> 2 }
            impl E3 for H3 where { o3 = {} -> 4 }
            impl E4 for H4 where { o4 = {} -> 8 }
            let probe = {} ->
              perform H1 in perform H2 in perform H3 in perform H4 in do
                let a = E1.o1 {} + E2.o2 {} + E3.o3 {} + E4.o4 {}
                let b = E1.o1 {} + E2.o2 {} + E3.o3 {} + E4.o4 {}
                a + b
            probe {}
        """.trimIndent()))
    }

    /**
     * The same handler re-entered. Each extent is a fresh push, so depth and
     * generation differ from the memoised ones even though the ANSWER is the
     * same — the memo must miss and still return the right handler rather than
     * returning null from a key comparison gone wrong.
     *
     * 2000 x 10 = 20 000.
     */
    @Test
    fun `re-entering the same handler repeatedly still resolves it`() {
        assertEquals(20000L, MezzeTestHelper.run("""
            $twoHandlers
            let ten = {} -> perform Ten in Pick.pick {}
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + ten {} }
            go { i = 2000, acc = 0 }
        """.trimIndent()))
    }
}
