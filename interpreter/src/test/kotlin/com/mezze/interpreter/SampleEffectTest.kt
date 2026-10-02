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
        use std::effects::sample::{ Sample, with_seed, with_seed_sized, choose, size }
        use std::abilities::arbitrary::{ Arbitrary, arbitrary, int_max, int_min }
        use std::collections::seq
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$imports\n\n$body")

    /** One draw of an Int under a seeded sampler. */
    private fun draw(seed: Int): Long? = MezzeTestHelper.asLong(
        eval("with_seed { seed = $seed, call = {} -> choose { bound = 1000000 } }")
    )

    @Test
    fun `two draws under one seeded sampler differ`() {
        // ONE `run` around the whole session. Two separate `run` calls would install
        // SeededRandom twice, and its stream resets at scope entry — so each draw
        // would restart the same sequence and return the same number.
        val rec = MezzeTestHelper.asRecord(eval("""
            with_seed { seed = 42, call = {} -> do
              let x = choose { bound = 1000000 }
              let y = choose { bound = 1000000 }
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
        val n = eval("with_seed_sized { seed = 1, size = 9n, call = {} -> size {} }")
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
            with_seed { seed = 5, call = {} -> do
              let v: Int = Arbitrary.arbitrary {}
              v
            }
        """.trimIndent()))
        assertTrue(isValidIntDraw(v), "not something the Int impl can produce: $v")
    }

    /** Two draws of a DIFFERENT type through `arbitrary`, still one seeded scope. */
    @Test
    fun `arbitrary at two types in one scope`() {
        val rec = MezzeTestHelper.asRecord(eval("""
            with_seed { seed = 11, call = {} -> do
              let n: Int = Arbitrary.arbitrary {}
              let s: Str = Arbitrary.arbitrary {}
              { n = n, s = s }
            }
        """.trimIndent()))
        val n = rec.getField(rec.getFieldIndex("n"))
        val s = rec.getField(rec.getFieldIndex("s"))
        assertTrue(s.toString() in INTERESTING_STRS, "not from the Str impl: $s")
        assertTrue(isValidIntDraw(n.toString().toLong()), "not from the Int impl: $n")
    }

    /**
     * The plain `arbitrary {}` wrapper, which only exists because S-1 is fixed: the
     * annotation it carries declares `<Sample>`, and before the fix the body was
     * inferred pure, so the declaration was rejected as unused.
     */
    @Test
    fun `the arbitrary wrapper works through its ability constraint`() {
        val v = MezzeTestHelper.asLong(eval("""
            with_seed { seed = 3, call = {} -> do
              let v: Int = arbitrary {}
              v
            }
        """.trimIndent()))
        assertTrue(isValidIntDraw(v), "not something the Int impl can produce: $v")
    }


    /** What `impl Arbitrary for Int` can now produce: the interesting table, or the
     *  random tail. Asserting a narrow range would just re-encode the old uniform
     *  generator and fail the moment an interesting value came out. */
    /**
     * The specials actually come up, and the random half actually goes wide.
     *
     * This is where the distribution is asserted, because [isValidIntDraw] cannot:
     * once the random half spans every Long, a per-draw membership check accepts
     * everything and proves nothing. A generator that returned only `0`, or only
     * mid-range values, would pass every other test in this file.
     *
     * 200 draws from ONE seeded stream. Drawing 200 times with 200 different seeds
     * instead measured the FIRST value of 200 streams, which is a different and
     * badly-distributed quantity — see S-5 (#162): across seeds the first
     * `choose { bound = 100 }` clusters in roughly {19, 26, 63, 73}, and seeds 0 and
     * 1 return the same value. That version reported 0/200 specials and the generator
     * was fine.
     */
    @Test
    fun `the Int distribution covers the boundaries AND goes wide`() {
        // Counted in MEZZE and returned as three Ints, rather than shipping 200
        // values across and folding them here — the host side has no seq accessor
        // and does not need one for a question this shape.
        val rec = MezzeTestHelper.asRecord(eval("""
            with_seed { seed = 20260101, call = {} -> do
              let vs: [Int] = seq::map { xs = seq::range { start = 0, end = 200 }, f = { x } -> arbitrary {} }
              {
                specials = seq::count { xs = vs, p = { x } ->
                  x == 0 || x == 1 || x == (0 - 1) || x == int_max || x == int_min },
                wide = seq::count { xs = vs, p = { x } -> x > 1000000 || x < (0 - 1000000) },
                boundary = seq::count { xs = vs, p = { x } -> x == int_max || x == int_min }
              }
            }
        """.trimIndent()))
        fun count(f: String) = rec.getField(rec.getFieldIndex(f)).toString().toLong()

        assertTrue(
            count("specials") >= 20,
            "only ${count("specials")}/200 draws were one of the five specials — " +
                "the interesting table is not being used",
        )
        assertTrue(
            count("wide") >= 20,
            "only ${count("wide")}/200 draws exceeded ±1e6 — the random half is too " +
                "narrow to find an overflow, which is the whole reason it spans the full range",
        )
        assertTrue(
            count("boundary") >= 1,
            "neither Int boundary appeared in 200 draws",
        )
    }

    /**
     * Any Long, and that is the honest assertion rather than a weak one. The random
     * half of `Arbitrary for Int` spans ±(2^63 - 2) by design — a narrower bound
     * here would just re-encode the [-1000, 1000] tail that was replaced, and would
     * fail at random once a wide value came out. What is actually worth asserting
     * about the distribution lives in the boundary-coverage test below.
     */
    private fun isValidIntDraw(v: Long?): Boolean = v != null

    private companion object {
        /**
         * The five canonical specials, from ScalaCheck's `Gen.chooseNum`:
         * `List(minT, maxT, zero, one, -one)`. Everything else `Arbitrary for Int`
         * can produce comes from the random half, which spans ±(2^63 - 2) — so a
         * membership check is not available for it and [isValidIntDraw] accepts any
         * Long instead.
         */
        val INTERESTING_INTS = setOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)
        /**
         * Every string `impl Arbitrary for Str` can produce, across all three
         * weighted groups. Listed in full rather than sampled: a membership check
         * is the only assertion available (the value is drawn), so a set that
         * drifted from the impl would start failing at random — the worst kind of
         * flake to diagnose.
         */
        val INTERESTING_STRS = setOf(
            // readable, weight 65
            "alpha", "beta", "gamma", "mezze", "sample", "hello", "world",
            // boundaries, weight 20
            "", " ", "a",
            // hostile, weight 15
            "\"", "\n", "\t", "\u00fcn\u00efc\u00f6d\u00e9", "  padded  ", "a,b;c",
        )
    }
}
