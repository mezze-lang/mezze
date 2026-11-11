package com.mezze.interpreter.effects

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Installing a handler got two fast paths, and BOTH of them can only fail by
 * silently keeping a stale handler — never by being slow. These tests are the
 * only thing standing between that and a wrong answer.
 *
 * WHAT THE FAST PATHS ARE. `HandlerChainRegistry.invalidateAssumptions` ran on
 * every push AND every pop, and did two expensive things each time:
 *
 *  1. `EFFECT_ASSUMPTIONS.get(effectHash)` — a `ConcurrentHashMap` lookup keyed
 *     on a 64-byte `Hash`. At one push site the effect's `Hash` is the same
 *     OBJECT every time, so a one-element identity memo on the per-thread
 *     `EffectState` removes it. Worth 1.06x on `install/iter`.
 *  2. `StabilityAssumption.invalidate()`, which was `synchronized` — four
 *     monitor-enters per iteration, on two process-global objects, and past
 *     `CHURN_LIMIT` every one of them a guaranteed no-op. Worth the other
 *     1.14x.
 *
 * THE HAZARD IN THE MEMO, which is why it only caches a NON-NULL. An effect has
 * no entry in the map until a perform site for it specializes, because the
 * assumptions are created lazily by `@Cached("getEffectStableAssumption(…)")`.
 * A push that happens BEFORE that legitimately looks up null. Caching the null
 * would make every later push for the same effect take the memo and skip
 * invalidating the assumption created in between — so a perform node would keep
 * cached dispatch across a handler change and answer with the WRONG HANDLER.
 * `a push before the assumption exists must not memoise its absence` is that
 * case — driven at the registry, because the guest language cannot build the
 * Mut or Async dispatch that holds this assumption; see that test.
 *
 * THE HAZARD IN THE LOCK-FREE READ is the reverse: `gaveUpFast` is only sound
 * because it is MONOTONIC — once an effect has given up, `get()` returns
 * `NEVER_VALID` forever, so nobody holds a valid assumption and invalidating is
 * genuinely nothing. `reset()` is the one thing that unwinds it, and if it
 * forgets to clear the mirror, that effect never invalidates again for the life
 * of the process. `EffectChurnRecoveryTest` already covers the recovery path
 * from the other side; `a reset effect still sees a handler change` pins it
 * here, next to the field it is about.
 */
class HandlerAssumptionFastPathTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        // Process-global, so an earlier test in this JVM can have churned.
        HandlerChainRegistry.resetStabilityForTests()
    }

    private val twoHandlers = """
        effect Pick where { pick: {} -> Int <Pick> }
        impl Pick for Ten where { pick = {} -> 10 }
        impl Pick for Twenty where { pick = {} -> 20 }
    """.trimIndent()

    /**
     * THE MEMO'S NULL CASE, DRIVEN AT THE REGISTRY, because the guest language
     * cannot reach it. `effectStable` guards cached Mut and Async dispatch
     * specializations — `getEffectStableAssumption`'s own javadoc says so — and
     * an ordinary `perform … in` loop never builds one, so the alternating
     * programs below answer correctly whether or not the null is cached. I
     * checked: with the guard removed every one of them still passes. A test
     * that cannot fail is not coverage, so this one asserts the invariant
     * itself, on the assumption object, where it is observable.
     *
     * THE SEQUENCE IS THE WHOLE POINT and it is the real lifecycle: a push
     * happens for an effect that has no assumption yet (the entry is created
     * lazily, at perform-site specialization), THEN the entry is created, THEN
     * another push must invalidate it. With the null memoised, the second push
     * takes the memo, finds null and invalidates nothing — the assumption stays
     * valid, and a perform node holding it keeps dispatch it should have lost.
     */
    @Test
    fun `a push before the assumption exists must not memoise its absence`() {
        val hash = com.mezze.language.typedast.Hash("fa57" + "0".repeat(60))

        // A push for an effect nothing has specialized against yet: the map
        // lookup returns null, and only a guarded memo declines to store it.
        HandlerChainRegistry.pushSimple(null, hash)
        HandlerChainRegistry.popSimple()

        // Now the entry exists, exactly as a specializing perform site makes it.
        val assumption = HandlerChainRegistry.getEffectStableAssumption(hash)
        assertTrue(assumption.isValid, "a freshly handed-out assumption is valid")

        HandlerChainRegistry.pushSimple(null, hash)
        try {
            assertFalse(assumption.isValid,
                "pushing a handler for this effect must invalidate its assumption")
        } finally {
            HandlerChainRegistry.popSimple()
        }
    }

    /**
     * The same alternation run long enough to be COMPILED, because both fast
     * paths are things partial evaluation sees: a straight-line run can be
     * right while the compiled version keeps a constant-folded handler.
     *
     * 2 000 iterations, 1 000 of each = 10 000 + 20 000 = 30 000.
     */
    @Test
    fun `alternating handlers stay right once the loop is compiled`() {
        val answer = MezzeTestHelper.run("""
            $twoHandlers
            let alternate = { i, acc } ->
              if i <= 0 then acc
              else do
                let one = if i % 2 == 0 then perform Ten in Pick.pick {}
                          else perform Twenty in Pick.pick {}
                alternate { i = i - 1, acc = acc + one }
            alternate { i = 2000, acc = 0 }
        """.trimIndent())
        assertEquals(30000L, answer)
    }

    /**
     * PAST THE CHURN LIMIT, which is where the lock-free early return actually
     * fires: 2 000 installs is 250x `CHURN_LIMIT`, so by the end every
     * `invalidate()` is taking the `gaveUpFast` path. The alternation after it
     * must still be right.
     *
     * 2 000 churning iterations of 10 = 20 000, then 200 alternating = 3 000.
     */
    @Test
    fun `an effect that has given up still sees a handler change`() {
        val answer = MezzeTestHelper.run("""
            $twoHandlers
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            let alternate = { i, acc } ->
              if i <= 0 then acc
              else do
                let x = if i % 2 == 0 then perform Ten in Pick.pick {}
                        else perform Twenty in Pick.pick {}
                alternate { i = i - 1, acc = acc + x }
            do
              let warm = churn { i = 2000, acc = 0 }
              warm + alternate { i = 200, acc = 0 }
        """.trimIndent())
        assertEquals(23000L, answer)
    }

    /**
     * RESET MUST UNWIND THE MIRROR, and like the memo's null this is only
     * observable on the assumption object: the guest-level test below passes
     * either way — I checked, by deleting the line — because an ordinary
     * `perform … in` loop never builds the Mut or Async dispatch that
     * `effectStable` guards. So this drives the lifecycle directly.
     *
     * Churn past the limit to set the mirror, reset as a recovering effect
     * does, take a fresh assumption, and push: if `reset()` clears `unstable`
     * but leaves `gaveUpFast` set, `invalidate()` returns early FOREVER and
     * that effect never notices another handler change for the life of the
     * process — the recovered tier would cache a handler and keep it.
     *
     * The loop asks for the assumption each time round on purpose:
     * `invalidate()` only counts a churn when the assumption was actually
     * handed out, so sixteen pushes without sixteen `get()`s churn exactly
     * once. Sixteen rather than `CHURN_LIMIT` because the constant is
     * package-private to the registry — the premise is asserted instead of
     * assumed, so a changed limit makes this test say so rather than pass
     * vacuously.
     */
    @Test
    fun `a reset clears the lock-free mirror so invalidation resumes`() {
        val hash = com.mezze.language.typedast.Hash("c0ffee" + "0".repeat(58))

        repeat(16) {
            HandlerChainRegistry.getEffectStableAssumption(hash)
            HandlerChainRegistry.pushSimple(null, hash)
            HandlerChainRegistry.popSimple()
        }
        assertTrue(HandlerChainRegistry.hasGivenUpForTests(hash),
            "churning past the limit is the premise of this test")

        HandlerChainRegistry.resetStabilityForTests()

        val assumption = HandlerChainRegistry.getEffectStableAssumption(hash)
        assertTrue(assumption.isValid, "a reset effect hands out a valid assumption again")

        HandlerChainRegistry.pushSimple(null, hash)
        try {
            assertFalse(assumption.isValid,
                "after a reset, a handler push must invalidate again")
        } finally {
            HandlerChainRegistry.popSimple()
        }
    }

    /**
     * RESET MUST UNWIND THE MIRROR. Churn the effect past its limit, reset as a
     * recovering effect does, and then change handlers: if `reset()` clears
     * `unstable` but leaves `gaveUpFast` set, `invalidate()` returns early
     * forever and the handler change is never seen.
     *
     * The reset happens between two separate runs in the same process, which is
     * what makes it reachable from a test at all — recovery is otherwise driven
     * by a run of `RECOVERY_HITS` stable lookups.
     */
    @Test
    fun `a reset effect still sees a handler change`() {
        val churn = """
            $twoHandlers
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            churn { i = 2000, acc = 0 }
        """.trimIndent()
        assertEquals(20000L, MezzeTestHelper.run(churn))

        HandlerChainRegistry.resetStabilityForTests()
        com.mezze.language.typechecker.ModuleResolver.clearCache()

        val answer = MezzeTestHelper.run("""
            $twoHandlers
            let alternate = { i, acc } ->
              if i <= 0 then acc
              else do
                let x = if i % 2 == 0 then perform Ten in Pick.pick {}
                        else perform Twenty in Pick.pick {}
                alternate { i = i - 1, acc = acc + x }
            alternate { i = 2000, acc = 0 }
        """.trimIndent())
        assertEquals(30000L, answer)
    }

    /**
     * TWO EFFECTS IN ONE LOOP, so the identity memo MISSES on every push — the
     * hash alternates, which is the case the memo has to handle by falling back
     * to the map and re-arming rather than by being wrong. Correct but not
     * memoised is the intended outcome here.
     *
     * 300 iterations: 150 x (10 from Pick) + 150 x (7 from Ask) = 1 500 + 1 050.
     */
    @Test
    fun `two effects alternating in one loop both invalidate`() {
        val answer = MezzeTestHelper.run("""
            $twoHandlers
            effect Ask where { ask: {} -> Int <Ask> }
            impl Ask for Seven where { ask = {} -> 7 }
            let alternate = { i, acc } ->
              if i <= 0 then acc
              else do
                let x = if i % 2 == 0 then perform Ten in Pick.pick {}
                        else perform Seven in Ask.ask {}
                alternate { i = i - 1, acc = acc + x }
            alternate { i = 300, acc = 0 }
        """.trimIndent())
        assertEquals(2550L, answer)
    }
}
