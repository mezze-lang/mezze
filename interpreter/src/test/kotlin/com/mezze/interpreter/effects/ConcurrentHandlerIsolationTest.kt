package com.mezze.interpreter.effects

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.nodes.continuation.HandlerChainRegistry
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * An effect op must resolve against the CURRENT THREAD's handler chain.
 *
 * The cached specializations in `PerformD` and `PerformMutMutateAct` live on a
 * BytecodeNode shared by every thread, while the handler chain they cache from
 * is thread-local and the stability `Assumption` is process-global. Nothing in
 * that arrangement notices that a cached handler belongs to one thread and is
 * readable by all of them: two tasks performing the same op under different
 * handlers silently read each other's handler.
 *
 * Failure mode is a wrong VALUE, never an exception, and only after the
 * uncached→cached transition — which is why a short-loop test can't see it.
 * The loop below is sized to cross that threshold comfortably.
 *
 * The fix has two halves and this file covers both: the single-threaded-effects
 * assumption must actually fall when a second thread appears (else the
 * check-free fast path keeps serving one thread's handler to another), and the
 * path taken once it has fallen must resolve per thread.
 */
class ConcurrentHandlerIsolationTest {

    /** The cached path is what this file tests; earlier tests in the JVM may have
     *  churned these effects past the point where it is still offered. */
    @BeforeEach
    fun resetStability() = HandlerChainRegistry.resetStabilityForTests()

    private val prelude = """
        use std::effects::async::{ Async, LoomAsync, await }
        use std::effects::reader::{ Reader, WithReader }
        use std::collections::seq

        let read_sum = { n } ->
          seq::range { start = 0, end = n }
            .fold { init = 0, f = { acc, x } -> acc + Reader.ask {} }

        let task = { v, n } ->
          perform WithReader { val = v } in read_sum { n = n }
    """.trimIndent()

    /** `await` yields `Ok { val = n }`; peel variant/record wrappers down to the Long. */
    private tailrec fun unwrap(v: Any?): Long = when (v) {
        is Long -> v
        is MezzeVariant -> unwrap(v.payloadValue)
        is MezzeRecord -> unwrap(v.getField(0))
        else -> error("unexpected value: $v (${v?.javaClass})")
    }

    private fun field(result: Any, name: String): Long {
        val rec = result as MezzeRecord
        return unwrap(rec.getField(rec.getFieldIndex(name)))
    }

    @Test
    @Timeout(120)
    fun `two threads performing the same op read their own handler, not each other's`() {
        val src = """
            $prelude
            perform LoomAsync in do
              let p1 = Async.spawn { f = {} -> task { v = 1, n = 2000 } }
              let p2 = Async.spawn { f = {} -> task { v = 1000, n = 2000 } }
              let a = await { p = p1 }
              let b = await { p = p2 }
              { a = a, b = b }
        """.trimIndent()

        // Repeat: the interleaving that steals the specialization is racy.
        repeat(5) { round ->
            val r = MezzeTestHelper.run(src)
            assertEquals(2_000L, field(r, "a"), "round $round: task a read another thread's handler")
            assertEquals(2_000_000L, field(r, "b"), "round $round: task b read another thread's handler")
        }
    }

    /**
     * The check-free fast path is sound only while one thread has ever touched
     * handler state, so spawning MUST drop the assumption that says so.
     *
     * Asserted in one direction only, and deliberately: the assumption is
     * monotonic — once down it never comes back — so "still valid" depends on
     * nothing else in the JVM having spawned yet, which no test in a shared
     * JVM can rely on. "Down after spawning" holds regardless of order.
     */
    @Test
    @Timeout(120)
    fun `spawning drops the single-threaded-effects assumption`() {
        val src = """
            $prelude
            perform LoomAsync in do
              let p = Async.spawn { f = {} -> task { v = 1, n = 10 } }
              await { p = p }
        """.trimIndent()
        MezzeTestHelper.run(src)
        assertFalse(
            HandlerChainRegistry.getSingleThreadedEffectsAssumption().isValid,
            "a second thread acquired handlers but the single-threaded assumption " +
                "survived — the check-free dispatch path is now serving one " +
                "thread's handler to another",
        )
    }

    /**
     * The other direction: a single-threaded program must KEEP the assumption,
     * so that dispatch stays on the path with no per-call handler check. If
     * something invalidates it spuriously, every `Mut` op loses the LocalMut
     * bypass and takes a `@TruffleBoundary` per operation instead — the 500×
     * regression the bypass exists to prevent, with every test still green.
     *
     * Self-skips rather than fails when an earlier test in the same JVM already
     * spawned: the assumption is monotonic, so this can only be checked from a
     * clean process. It does the work in an isolated run and never lies in a
     * shared one.
     */
    @Test
    @Timeout(120)
    fun `a single-threaded program keeps the single-threaded-effects assumption`() {
        assumeTrue(
            HandlerChainRegistry.getSingleThreadedEffectsAssumption().isValid,
            "another test already spawned in this JVM",
        )
        MezzeTestHelper.run(
            """
            $prelude
            task { v = 3, n = 2000 }
            """.trimIndent(),
        )
        assertTrue(
            HandlerChainRegistry.getSingleThreadedEffectsAssumption().isValid,
            "a single-threaded program dropped the single-threaded assumption — " +
                "effect dispatch has fallen back to checking the handler on " +
                "every op",
        )
    }

    @Test
    @Timeout(120)
    fun `sequential performs under different handlers stay isolated`() {
        val src = """
            $prelude
            do
              let a = task { v = 1, n = 2000 }
              let b = task { v = 1000, n = 2000 }
              { a = a, b = b }
        """.trimIndent()
        val r = MezzeTestHelper.run(src)
        assertEquals(2_000L, field(r, "a"))
        assertEquals(2_000_000L, field(r, "b"))
    }
}
