package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The `Sample` effect and the `Arbitrary` ability, end to end.
 *
 * The assertion that matters most is that two draws under ONE seeded sampler
 * DIFFER. `SeededRandom` resets its stream at scope entry, so a sampler that
 * installed it per-operation rather than once around the whole generation would
 * return the same number every time — a generator that looks like it works and
 * produces constants. Only comparing two draws catches that.
 *
 * Sources are bare top-level expressions, not `let` bindings: a top-level `let`
 * whose value comes from an effectful call runs at module-load time and is
 * rejected (V0502), even when the effect is handled.
 */
class SampleEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sampler, RandomSampler, RandomSamplerWith, int, run, size }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$imports\n\n$body")

    /** One draw of an Int under a seeded sampler. */
    private fun draw(seed: Int): Long? = MezzeTestHelper.asLong(
        eval("perform RandomSampler { seed = $seed } in run { call = {} -> int { min = 1, max = 1000000 } }")
    )

    @Test
    fun `two draws under one seeded sampler differ`() {
        // ONE `run` around the whole session. Two separate `run` calls would install
        // SeededRandom twice, and its stream resets at scope entry — so each draw
        // would restart the same sequence and return the same number.
        val rec = MezzeTestHelper.asRecord(eval("""
            perform RandomSampler { seed = 42 } in run { call = {} -> do
              let x = int { min = 1, max = 1000000 }
              let y = int { min = 1, max = 1000000 }
              { a = x, b = y }
            }
        """.trimIndent()))
        val x = rec.getField(rec.getFieldIndex("a"))
        val y = rec.getField(rec.getFieldIndex("b"))
        assertNotEquals(x, y, "both draws returned $x — the seed is resetting per draw")
    }

    @Test
    fun `the same seed replays identically`() {
        assertEquals(draw(7), draw(7), "the same seed produced different values")
    }

    @Test
    fun `a different seed gives a different value`() {
        assertNotEquals(draw(1), draw(999), "two seeds produced the same value")
    }

    @Test
    fun `the size budget is what the sampler was given`() {
        val n = eval("perform RandomSamplerWith { seed = 1, size = 9n } in run { call = {} -> size {} }")
        assertEquals(9L, MezzeTestHelper.asLong(n))
    }

    // NO test of `Arbitrary.arbitrary` here yet, and not because it does not work.
    // It generates correctly — but only if the enclosing thunk already carries
    // `<Sample>` from some other call, because an ability operation's declared effect
    // row is not propagated to its call site (S-1). The passing version needs a dummy
    // same-effect call alongside, which is a workaround that must not ship.
    //
    // A record has no impl at all until structural derivation lands (S-2), which is
    // the case that matters, since a function's parameter IS a record.
}
