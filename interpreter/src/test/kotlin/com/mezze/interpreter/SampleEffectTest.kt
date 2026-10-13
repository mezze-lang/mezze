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
        use std::effects::sample::{ Sample, SeededSample, SeededSampleWith, choose, size }
        use std::abilities::arbitrary::{ Arbitrary, arbitrary, int_max, int_min }
        use std::collections::seq
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$imports\n\n$body")

    /** One draw of an Int under a seeded sampler. */
    private fun draw(seed: Int): Long? = MezzeTestHelper.asLong(
        eval("perform SeededSample { seed = $seed } in choose { bound = 1000000 } ")
    )

    @Test
    fun `two draws under one seeded sampler differ`() {
        // ONE `run` around the whole session. Two separate `run` calls would install
        // SeededRandom twice, and its stream resets at scope entry — so each draw
        // would restart the same sequence and return the same number.
        val rec = MezzeTestHelper.asRecord(eval("""
            perform SeededSample { seed = 42 } in do
              let x = choose { bound = 1000000 }
              let y = choose { bound = 1000000 }
              { a = x, b = y }
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
        val n = eval("perform SeededSampleWith { seed = 1, config = { size = 9n, enumerate = 12n } } in size {} ")
        assertEquals(9L, MezzeTestHelper.asLong(n))
    }

    /**
     * A seeded scope NESTS with a `Log` handler, in both orders.
     *
     * This is the reproduction of the bug that made `with_seed` a performer, written
     * with the effect and the handler that actually exhibited it:
     *
     *     perform Console in with_seed { call = {} -> … println … }
     *
     * typechecks and prints NOTHING. The `Log` performed inside the `call` thunk has
     * already left the `perform` body by the time `Console` is installed, so the
     * handler never fires — silently, which is why the shape was worth removing
     * rather than documenting.
     *
     * The draw and the print are both in the body, so `Sample` and `Log` are live in
     * the one scope and each handler has to discharge its own. Note what the nesting
     * is NOT: `Console` and the sampler are SIBLING handlers around a shared body,
     * not one effect inside the other.
     */
    @Test
    fun `a seeded scope nests with a Log handler, either way round`() {
        fun printsUnder(wrap: (String) -> String): String {
            val body = """
                do
                  let n = choose { bound = 1000000 }
                  println { msg = "drew $(n.to_dbg {})" }
            """.trimIndent().prependIndent("  ").trimStart()
            return MezzeTestHelper.runWithOutput(
                "$imports\nuse std::effects::log::{ println, Console }\n\n${wrap(body)}"
            ).output
        }

        val outerLog = printsUnder { "perform Console in perform SeededSample { seed = 5 } in $it" }
        assertTrue(outerLog.contains("drew "), "the OUTER Console never fired; printed: <$outerLog>")

        val outerSeeded = printsUnder { "perform SeededSample { seed = 5 } in perform Console in $it" }
        assertTrue(outerSeeded.contains("drew "), "the INNER Console never fired; printed: <$outerSeeded>")

        // Same seed both ways: the draw is the sampler's, not the nesting order's.
        assertEquals(outerLog.trim(), outerSeeded.trim(), "nesting order changed the draw")
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
            perform SeededSample { seed = 5 } in do
              let v: Int = Arbitrary.arbitrary {}
              v
        """.trimIndent()))
        assertTrue(isValidIntDraw(v), "not something the Int impl can produce: $v")
    }

    /** Two draws of a DIFFERENT type through `arbitrary`, still one seeded scope. */
    @Test
    fun `arbitrary at two types in one scope`() {
        val rec = MezzeTestHelper.asRecord(eval("""
            perform SeededSample { seed = 11 } in do
              let n: Int = Arbitrary.arbitrary {}
              let s: Str = Arbitrary.arbitrary {}
              { n = n, s = s }
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
            perform SeededSample { seed = 3 } in do
              let v: Int = arbitrary {}
              v
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
        // At a STRESS size: below `stress_size` an Int is human-sized on purpose —
        // no extremes, nothing past 15 bits — and ArbitrarySizeTest holds that side.
        // This is the property-testing distribution, which a check reaches as its
        // runs grow.
        val rec = MezzeTestHelper.asRecord(eval("""
            perform SeededSampleWith { seed = 20260101, config = { size = 100n, enumerate = 12n } } in do
              let vs: [Int] = seq::map { xs = seq::range { start = 0, end = 200 }, f = { x } -> arbitrary {} }
              {
                specials = seq::count { xs = vs, p = { x } ->
                  x == 0 || x == 1 || x == (0 - 1) || x == int_max || x == int_min },
                wide = seq::count { xs = vs, p = { x } -> x > 1000000 || x < (0 - 1000000) },
                boundary = seq::count { xs = vs, p = { x } -> x == int_max || x == int_min }
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
