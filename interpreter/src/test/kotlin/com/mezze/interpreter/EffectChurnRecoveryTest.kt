package com.mezze.interpreter

import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * An effect that has churned past its limit and then gone quiet must get its
 * free dispatch tier back.
 *
 * WHAT WAS WRONG. `StabilityAssumption.CHURN_LIMIT` is 8 and the back-off was
 * PERMANENT, so eight install/uninstall pairs cost every later operation of
 * that effect — for the life of the process. Every real program installs more
 * than eight handlers over its lifetime, so this was the steady state rather
 * than an edge case: 9 719 ps an operation against 820 for a pristine one.
 *
 * WHY THE EARLIER RULING-OUT WAS INCOMPLETE. PERF-LOG ruled out the whole class
 * of "make the assumption recover" because `get()` is called at specialization
 * time only, so a node that has stopped consulting an assumption cannot notice
 * it coming back. True — but by then the cause had changed, and it was one the
 * previous fix on this row created. `reset()` already existed and already
 * invalidated the cyclic assumption first so that holders would be knocked out.
 * The trouble was that `doCachedShared` deliberately held NO assumption any
 * more — that is what let it survive churn and win 2.6x — so a reset had
 * nothing to dislodge. It kept matching, the DSL never re-entered its slow
 * path, and `get()` was never re-asked. **A working cheaper-but-not-cheapest
 * tier was itself the thing blocking recovery.**
 *
 * THE FIX, IN TWO PARTS.
 *
 *  1. `doCachedShared` carries `churnedTier`, a second assumption that churn
 *     never invalidates and `reset()` does. Dropping that tier is what forces
 *     the re-specialization that re-asks for a fresh `effectStable`.
 *  2. The trigger is the handler-lookup memo landed in the previous commit: a
 *     memo HIT proves depth and generation have not moved, so a RUN of
 *     `RECOVERY_HITS` hits is proof the chain has been stable for that many
 *     operations. It costs one increment on per-thread state already behind a
 *     boundary.
 *
 * Self-limiting by construction, which is the property that makes this safe:
 * a loop that installs a handler every iteration changes the generation every
 * iteration, so the memo never hits, the counter never arms, and there is no
 * tier thrash. `checkStillGivenUp` below is the test for exactly that.
 *
 * WHY THESE TESTS LOOK LIKE THIS. Recovery is invisible from inside the
 * language — it changes which dispatch tier runs, never an answer — so these
 * assert on `givenUpCountForTests()`, and a correctness test alongside asserts
 * the answers stay right across the transition.
 *
 * THE LOOPS ARE LONG FOR TWO SEPARATE REASONS, both learned the hard way.
 * `invalidate()` returns early unless the assumption was actually handed out,
 * and it is handed out at SPECIALIZATION time — so a program that never warms
 * up never increments the churn counter and never gives up at all. The churn
 * phase therefore has to be warm before it can churn. And the stable phase has
 * to exceed `RECOVERY_HITS` for the counter to arm.
 */
class EffectChurnRecoveryTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        // The assumptions are process-global, so an earlier test in this JVM
        // can have pushed effects past their limit already.
        HandlerChainRegistry.resetStabilityForTests()
    }

    private val effect = """
        effect Pick where { pick: {} -> Int <Pick> }
        impl Pick for Ten where { pick = {} -> 10 }
    """.trimIndent()

    /**
     * THE PREMISE, AND WHY IT HAS TO BE CHECKED RATHER THAN ASSUMED.
     *
     * An effect only gives up when `StabilityAssumption.invalidate()` actually
     * counts, and it returns early unless the assumption was HANDED OUT. For an
     * ordinary effect the only thing that asks for it is
     * `PerformD.doCachedSingleThreaded`'s `@Cached` initializer — and that
     * specialization also carries the `singleThreaded` assumption, which is a
     * plain `Assumption` invalidated FOREVER the moment a second thread touches
     * handler state. There is no reset for it.
     *
     * So in a JVM where any earlier test has used threads, `effectStable` is
     * never handed out, churn never counts, and NO effect can ever give up.
     * These tests then have nothing to observe — and the positive one would
     * pass VACUOUSLY, since "no effect is given up" is exactly what it asserts.
     * Run alone they all pass; in the full suite two of them failed, which is
     * how this was found.
     *
     * That is also a real fact about the system and not just about the tests:
     * **the churn cliff is a single-threaded-process phenomenon.** A process
     * that has ever handled effects on two threads is already on the verify
     * tier permanently, where the handler-lookup memo is what helps it and
     * recovery has nothing to recover from.
     *
     * This runs the churn phase ALONE and reports whether it registered, so
     * every test below can state its premise instead of assuming it.
     */
    private fun churnRegisters(): Boolean {
        MezzeTestHelper.run("""
            $effect
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            churn { i = 2000, acc = 0 }
        """.trimIndent())
        return HandlerChainRegistry.givenUpCountForTests() >= 1
    }

    /**
     * THE LOAD-BEARING ONE. Warm up, churn past the limit, then run quietly for
     * longer than `RECOVERY_HITS` — and the effect must no longer be given up.
     *
     * Without part 1 of the fix the reset has nothing to dislodge; without part
     * 2 nothing ever calls it. Either way this test sees a still-churned effect.
     */
    @Test
    fun `a quiet effect recovers its free tier after churning past the limit`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(churnRegisters(),
            "this JVM has lost single-threaded effects, so nothing can give up — see churnRegisters")
        val answer = MezzeTestHelper.run("""
            $effect
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            let stable = { i, acc } ->
              if i <= 0 then acc
              else stable { i = i - 1, acc = acc + Pick.pick {} }
            do
              churn { i = 2000, acc = 0 }
              perform Ten in stable { i = 100000, acc = 0 }
        """.trimIndent())
        assertEquals(1000000L, answer, "the answers must stay right across recovery")
        assertEquals(0, HandlerChainRegistry.givenUpCountForTests(),
            "a quiescent effect should have been forgiven its churn")
    }

    /**
     * THE SELF-LIMITING PROPERTY. The same churn, but the quiet phase is far
     * shorter than `RECOVERY_HITS`, so the counter must NOT arm. This is what
     * keeps a handler-installing loop from thrashing between tiers: if this
     * test ever reads 0, the threshold has stopped doing its job.
     */
    @Test
    fun `a short quiet phase does not arm recovery`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(churnRegisters(),
            "this JVM has lost single-threaded effects, so nothing can give up — see churnRegisters")
        val answer = MezzeTestHelper.run("""
            $effect
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            let stable = { i, acc } ->
              if i <= 0 then acc
              else stable { i = i - 1, acc = acc + Pick.pick {} }
            do
              churn { i = 2000, acc = 0 }
              perform Ten in stable { i = 100, acc = 0 }
        """.trimIndent())
        assertEquals(1000L, answer)
        assertTrue(HandlerChainRegistry.givenUpCountForTests() >= 1,
            "100 quiet operations is far below the threshold and must not recover")
    }

    /**
     * INSTALLING EVERY ITERATION MUST NEVER RECOVER, because the memo cannot
     * hit when the generation moves every operation. This is the shape that
     * would thrash — deoptimise, re-specialize, churn, repeat — if recovery
     * were triggered by anything less specific than a run of stable lookups.
     */
    @Test
    fun `a loop that installs every iteration never arms recovery`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(churnRegisters(),
            "this JVM has lost single-threaded effects, so nothing can give up — see churnRegisters")
        val answer = MezzeTestHelper.run("""
            $effect
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            churn { i = 100000, acc = 0 }
        """.trimIndent())
        assertEquals(1000000L, answer)
        assertTrue(HandlerChainRegistry.givenUpCountForTests() >= 1,
            "a handler installed every iteration is churn, not quiescence")
    }

    /**
     * Recovery must not change an ANSWER, including when the handler that comes
     * back is not the one the churn phase used. After recovery the free tier
     * caches a handler under a freshly valid assumption; installing the other
     * handler has to invalidate it again.
     *
     * 100 000 x 10 then 2 000 x 20 = 1 040 000.
     */
    @Test
    fun `a different handler still answers after recovery has happened`() {
        assertEquals(1040000L, MezzeTestHelper.run("""
            effect Pick where { pick: {} -> Int <Pick> }
            impl Pick for Ten where { pick = {} -> 10 }
            impl Pick for Twenty where { pick = {} -> 20 }
            let one = {} -> perform Ten in Pick.pick {}
            let churn = { i, acc } ->
              if i <= 0 then acc
              else churn { i = i - 1, acc = acc + one {} }
            let stable = { i, acc } ->
              if i <= 0 then acc
              else stable { i = i - 1, acc = acc + Pick.pick {} }
            let after = { i, acc } ->
              if i <= 0 then acc
              else after { i = i - 1, acc = acc + (perform Twenty in Pick.pick {}) }
            do
              churn { i = 2000, acc = 0 }
              let a = perform Ten in stable { i = 100000, acc = 0 }
              let b = after { i = 2000, acc = 0 }
              a + b
        """.trimIndent()))
    }
}
