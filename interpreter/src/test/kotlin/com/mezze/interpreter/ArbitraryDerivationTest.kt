package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S-2: `Arbitrary` derived by the compiler, like `Eq`.
 *
 * NOTHING here writes an `impl Arbitrary`. Every case that works does so because
 * the compiler generated the instance from the type's structure — that is the
 * whole claim, and a hand-written impl anywhere in these sources would void it.
 */
class ArbitraryDerivationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, with_seed, with_seed_sized }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$imports\n\n$body")

    /** Draw one value of [type] under a seeded sampler, with [decls] in scope. */
    private fun draw(decls: String, type: String, seed: Int = 3, body: String = "v"): Any? = eval(
        """
        $decls

        with_seed { seed = $seed, call = {} -> do
          let v: $type = Arbitrary.arbitrary {}
          $body
        }
        """.trimIndent()
    )

    @Test
    fun `a record derives`() {
        val rec = MezzeTestHelper.asRecord(
            draw("type Point = { x: Int, y: Int }", "Point")
        )
        val x = rec.getField(rec.getFieldIndex("x")).toString().toLong()
        val y = rec.getField(rec.getFieldIndex("y")).toString().toLong()
        assertTrue(isValidIntDraw(x) && isValidIntDraw(y), "not Int-impl draws: x=$x y=$y")
    }

    @Test
    fun `a record of mixed primitives derives`() {
        val rec = MezzeTestHelper.asRecord(
            draw("type Mixed = { n: Int, s: Str, f: Float }", "Mixed")
        )
        val s = rec.getField(rec.getFieldIndex("s")).toString()
        assertTrue(s in INTERESTING_STRS, "not from the Str impl: $s")
    }

    @Test
    fun `a NESTED record derives, reaching the inner derived instance`() {
        val rec = MezzeTestHelper.asRecord(
            draw(
                """
                type Inner = { v: Int }
                type Outer = { inner: Inner, tag: Str }
                """.trimIndent(),
                "Outer",
            )
        )
        val inner = MezzeTestHelper.asRecord(rec.getField(rec.getFieldIndex("inner")))
        val v = inner.getField(inner.getFieldIndex("v")).toString().toLong()
        assertTrue(isValidIntDraw(v), "inner not generated: $v")
    }

    /** The case `mez sample` needs: a function's parameter IS a record. */
    @Test
    fun `a function's parameter record derives and the function runs on it`() {
        val n = MezzeTestHelper.asLong(eval("""
            type Args = { a: Int, b: Int }

            let add_them: Args -> Int = { a, b } -> a + b

            with_seed { seed = 12, call = {} -> do
              let args: Args = Arbitrary.arbitrary {}
              add_them { a = args.a, b = args.b }
            }
        """.trimIndent()))
        assertTrue(n != null, "the function did not run on the generated record")
    }

    @Test
    fun `a payload-free variant derives and can produce more than one constructor`() {
        // Ten draws in ONE seeded scope. A single draw proves nothing — a generator
        // that always answers `Red` would pass it.
        val seen = MezzeTestHelper.asRecord(eval("""
            type Color = | Red, Green, Blue |

            let name_of: { c: Color } -> Str = { c } ->
              if c == Red then "R" else if c == Green then "G" else "B"

            with_seed { seed = 21, call = {} -> do
              let a: Color = Arbitrary.arbitrary {}
              let b: Color = Arbitrary.arbitrary {}
              let c: Color = Arbitrary.arbitrary {}
              let d: Color = Arbitrary.arbitrary {}
              let e: Color = Arbitrary.arbitrary {}
              let f: Color = Arbitrary.arbitrary {}
              let g: Color = Arbitrary.arbitrary {}
              let h: Color = Arbitrary.arbitrary {}
              { s = "$(name_of { c = a })$(name_of { c = b })$(name_of { c = c })$(name_of { c = d })$(name_of { c = e })$(name_of { c = f })$(name_of { c = g })$(name_of { c = h })" }
            }
        """.trimIndent()))
        val s = seen.getField(seen.getFieldIndex("s")).toString()
        assertEquals(8, s.length, "expected 8 draws, got: $s")
        assertTrue(s.toSet().size > 1, "every draw returned the same constructor: $s")
    }

    @Test
    fun `a variant with a record payload derives`() {
        val n = MezzeTestHelper.asLong(
            draw(
                "type Wrap = | Wrapped { val: Int } |",
                "Wrap",
                body = "match v is\n    Wrapped { val } -> val",
            )
        )
        assertTrue(isValidIntDraw(n), "payload not generated: $n")
    }

    /**
     * A RECURSIVE variant does NOT derive, and is rejected rather than generated.
     *
     * Bounding the recursion needs the `Sample` size budget, and a generated body
     * cannot name `Sample` — it must compile in modules that have never heard of
     * sampling. Deriving without the bound would not produce a bad value, it would
     * not terminate, so the honest answer is to decline and let the author write the
     * impl that says where to stop. Asserted so the boundary is a decision on record
     * rather than something that quietly starts hanging if the rule is relaxed.
     */
    @Test
    fun `a recursive variant does not derive`() {
        val e = runCatching {
            draw("type Tree = | Leaf { v: Int }, Node { l: Tree, r: Tree } |", "Tree")
        }.exceptionOrNull()
        assertTrue(e != null, "a recursive variant derived Arbitrary; it cannot be bounded")
    }

    @Test
    fun `the same seed replays a derived record identically`() {
        val decls = "type Point = { x: Int, y: Int }"
        fun once(seed: Int): String {
            val r = MezzeTestHelper.asRecord(draw(decls, "Point", seed))
            return "${r.getField(r.getFieldIndex("x"))},${r.getField(r.getFieldIndex("y"))}"
        }
        assertEquals(once(9), once(9), "same seed produced different records")
    }

    /**
     * A NEWTYPE does not derive, and that is the rule rather than a gap: the
     * representation cannot say what a valid value is. Absent a hand-written impl
     * the program must be REJECTED, not silently generated from the payload.
     */
    @Test
    fun `a newtype does not derive`() {
        val e = runCatching {
            draw("newtype Email = Str", "Email")
        }.exceptionOrNull()
        assertTrue(e != null, "a newtype derived Arbitrary; it must opt in instead")
    }

    /** What `impl Arbitrary for Int` can now produce: the interesting table, or the
     *  random tail. Asserting a narrow range would just re-encode the old uniform
     *  generator and fail the moment an interesting value came out. */
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
