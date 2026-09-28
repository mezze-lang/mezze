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
        use std::effects::sample::{ Sampler, RandomSampler, RandomSamplerWith, run }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$imports\n\n$body")

    /** Draw one value of [type] under a seeded sampler, with [decls] in scope. */
    private fun draw(decls: String, type: String, seed: Int = 3, body: String = "v"): Any? = eval(
        """
        $decls

        perform RandomSampler { seed = $seed } in run { call = {} -> do
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
        assertTrue(x in -100L..100L && y in -100L..100L, "out of the Int impl's range: x=$x y=$y")
    }

    @Test
    fun `a record of mixed primitives derives`() {
        val rec = MezzeTestHelper.asRecord(
            draw("type Mixed = { n: Int, s: Str, f: Float }", "Mixed")
        )
        val s = rec.getField(rec.getFieldIndex("s")).toString()
        assertTrue(s in setOf("", "a", "sample", "mezze"), "not from the Str impl: $s")
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
        assertTrue(v in -100L..100L, "inner not generated: $v")
    }

    /** The case `mez sample` needs: a function's parameter IS a record. */
    @Test
    fun `a function's parameter record derives and the function runs on it`() {
        val n = MezzeTestHelper.asLong(eval("""
            type Args = { a: Int, b: Int }

            let add_them: Args -> Int = { a, b } -> a + b

            perform RandomSampler { seed = 12 } in run { call = {} -> do
              let args: Args = Arbitrary.arbitrary {}
              add_them { a = args.a, b = args.b }
            }
        """.trimIndent()))
        assertTrue(n != null && n >= -200L && n <= 200L, "outside the sum of two Int draws: $n")
    }

    @Test
    fun `a payload-free variant derives and can produce more than one constructor`() {
        // Ten draws in ONE seeded scope. A single draw proves nothing — a generator
        // that always answers `Red` would pass it.
        val seen = MezzeTestHelper.asRecord(eval("""
            type Color = | Red, Green, Blue |

            let name_of: { c: Color } -> Str = { c } ->
              if c == Red then "R" else if c == Green then "G" else "B"

            perform RandomSampler { seed = 21 } in run { call = {} -> do
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
        assertTrue(n != null && n >= -100L && n <= 100L, "payload not generated: $n")
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
}
