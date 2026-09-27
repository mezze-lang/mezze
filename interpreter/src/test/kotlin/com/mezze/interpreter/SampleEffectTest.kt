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
        use std::abilities::arbitrary::{ Arbitrary, arbitrary }
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

    /**
     * `Arbitrary.arbitrary` alone in the thunk — no dummy `<Sample>` call alongside.
     *
     * That dummy was needed until S-1: a receiver-less ability op dropped its
     * declared `<Sample>`, so the thunk inferred pure and `run`'s open
     * `<Sample, ...>` row had nothing to unify its tail against. See
     * `AbilityOpEffectRowTest` for the reduced case.
     */
    @Test
    fun `arbitrary generates with no other Sample call in the thunk`() {
        val v = MezzeTestHelper.asLong(eval("""
            perform RandomSampler { seed = 5 } in run { call = {} -> do
              let v: Int = Arbitrary.arbitrary {}
              v
            }
        """.trimIndent()))
        // The Int impl spreads over [-100, 100], so the only thing worth asserting
        // is the range — a specific value would pin this test to the RNG.
        assertTrue(v != null && v >= -100L && v <= 100L, "out of the Int impl's range: $v")
    }

    /** Two draws of a DIFFERENT type through `arbitrary`, still one seeded scope. */
    @Test
    fun `arbitrary at two types in one scope`() {
        val rec = MezzeTestHelper.asRecord(eval("""
            perform RandomSampler { seed = 11 } in run { call = {} -> do
              let n: Int = Arbitrary.arbitrary {}
              let s: Str = Arbitrary.arbitrary {}
              { n = n, s = s }
            }
        """.trimIndent()))
        val n = rec.getField(rec.getFieldIndex("n"))
        val s = rec.getField(rec.getFieldIndex("s"))
        assertTrue(s.toString() in setOf("", "a", "sample", "mezze"), "not from the Str impl: $s")
        assertTrue(n.toString().toLong() in -100L..100L, "not from the Int impl: $n")
    }

    /**
     * The plain `arbitrary {}` wrapper, which only exists because S-1 is fixed: the
     * annotation it carries declares `<Sample>`, and before the fix the body was
     * inferred pure, so the declaration was rejected as unused.
     */
    @Test
    fun `the arbitrary wrapper works through its ability constraint`() {
        val v = MezzeTestHelper.asLong(eval("""
            perform RandomSampler { seed = 3 } in run { call = {} -> do
              let v: Int = arbitrary {}
              v
            }
        """.trimIndent()))
        assertTrue(v != null && v >= -100L && v <= 100L, "out of the Int impl's range: $v")
    }

    // A record still has no impl until structural derivation lands (S-2), which is
    // the case that matters, since a function's parameter IS a record.
}
