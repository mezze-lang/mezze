package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S-2 feasibility probe, written BY HAND in exactly the shape the derived impl
 * would be generated in. If these do not typecheck, the generator cannot emit this
 * shape and the design has to change before a line of it is written.
 *
 * The question each case answers is whether `Arbitrary.arbitrary {}` gets its
 * return type pinned by its POSITION, since it takes no argument and so has
 * nothing else to dispatch on. A generated body cannot annotate every field.
 */
class ArbitraryHandWrittenShapeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sampler, RandomSampler, run }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$imports\n\n$body")

    /** A record field position: does the field's declared type pin the call? */
    @Test
    fun `record fields pin arbitrary with no annotation`() {
        val rec = MezzeTestHelper.asRecord(eval("""
            type Point = { x: Int, y: Int }

            impl Arbitrary for Point where {
              arbitrary = {} -> { x = Arbitrary.arbitrary {}, y = Arbitrary.arbitrary {} }
            }

            perform RandomSampler { seed = 4 } in run { call = {} -> do
              let p: Point = Arbitrary.arbitrary {}
              p
            }
        """.trimIndent()))
        val x = rec.getField(rec.getFieldIndex("x")).toString().toLong()
        val y = rec.getField(rec.getFieldIndex("y")).toString().toLong()
        assertTrue(x in -100L..100L && y in -100L..100L, "out of range: x=$x y=$y")
    }

    /** A record of MIXED field types — each field must pin independently. */
    @Test
    fun `mixed field types each pin independently`() {
        val rec = MezzeTestHelper.asRecord(eval("""
            type Mixed = { n: Int, s: Str, b: Bool }

            impl Arbitrary for Mixed where {
              arbitrary = {} -> {
                n = Arbitrary.arbitrary {},
                s = Arbitrary.arbitrary {},
                b = Arbitrary.arbitrary {},
              }
            }

            perform RandomSampler { seed = 8 } in run { call = {} -> do
              let m: Mixed = Arbitrary.arbitrary {}
              m
            }
        """.trimIndent()))
        val s = rec.getField(rec.getFieldIndex("s")).toString()
        assertTrue(s in setOf("", "a", "sample", "mezze"), "not from the Str impl: $s")
    }

    /** A NESTED record: the inner type's own derived impl must be reached. */
    @Test
    fun `a nested record reaches the inner impl`() {
        val rec = MezzeTestHelper.asRecord(eval("""
            type Inner = { v: Int }
            type Outer = { inner: Inner, tag: Str }

            impl Arbitrary for Inner where {
              arbitrary = {} -> { v = Arbitrary.arbitrary {} }
            }

            impl Arbitrary for Outer where {
              arbitrary = {} -> { inner = Arbitrary.arbitrary {}, tag = Arbitrary.arbitrary {} }
            }

            perform RandomSampler { seed = 2 } in run { call = {} -> do
              let o: Outer = Arbitrary.arbitrary {}
              o
            }
        """.trimIndent()))
        val inner = MezzeTestHelper.asRecord(rec.getField(rec.getFieldIndex("inner")))
        val v = inner.getField(inner.getFieldIndex("v")).toString().toLong()
        assertTrue(v in -100L..100L, "inner not generated: $v")
    }

    /** A CONSTRUCTOR-ARGUMENT position, which is how a variant payload is built. */
    @Test
    fun `a variant payload position pins arbitrary`() {
        val v = eval("""
            type Wrap = | Wrapped { val: Int } |

            impl Arbitrary for Wrap where {
              arbitrary = {} -> Wrapped { val = Arbitrary.arbitrary {} }
            }

            perform RandomSampler { seed = 6 } in run { call = {} -> do
              let w: Wrap = Arbitrary.arbitrary {}
              match w is
                Wrapped { val } -> val
            }
        """.trimIndent())
        val n = MezzeTestHelper.asLong(v)
        assertTrue(n != null && n >= -100L && n <= 100L, "payload not generated: $n")
    }
}
