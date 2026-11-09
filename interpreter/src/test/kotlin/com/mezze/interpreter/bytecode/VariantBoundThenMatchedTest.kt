package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A variant BOUND TO A NAME and then matched, which is how anyone writes it.
 *
 * `VariantMatchFolder` elides a variant constructed as the scrutinee of the
 * match that destructures it. It ran BEFORE `LetCopyPropagation`, by design —
 * its output is ordinary `let`s that the passes between them handle — and the
 * consequence was that binding the scrutinee to a name hid the construction
 * from it, because propagation is what makes the two adjacent. So the folder
 * now runs a SECOND time afterwards.
 *
 * Measured on `bench/cases/recfields` at n=2e7, one loop written both ways:
 *
 *     let s = Circle { r = i } ; match s is …   4 075 ps/iter  41 GC
 *     match Circle { r = i } is …                 632           1 GC
 *
 * and after: 690 against 641 — the cliff on a formatting choice is gone, and
 * a TWO-field variant went 6 940 → 667 ps with 82 GC → 1, which is 10.4x.
 *
 * WHAT THESE TESTS ARE FOR. Folding a construction into the match that consumes
 * it is a semantic rewrite, and the ways it goes wrong are quiet: the wrong
 * case taken, a payload field bound to the wrong slot, or a variant whose
 * construction had to survive because something else reads it. So every case
 * below distinguishes the arms by VALUE, the multi-field ones would answer
 * differently if the fields were swapped, and two cases deliberately keep the
 * construction alive.
 */
class VariantBoundThenMatchedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    private val shape = """
        type Shape = |
          Circle { r: Int },
          Tri { b: Int, h: Int },
          Dot,
        |
    """.trimIndent()

    @Test
    fun `a one-field variant bound then matched takes the right arm`() {
        assertEquals(7L, run(
            """
            $shape
            let s = Circle { r = 7 }
            match s is
              Circle { r } -> r
              Tri { b = _, h = _ } -> 0 - 1
              Dot -> 0 - 2
            """
        ))
    }

    /** Fields must not swap: `b * 10 + h` is 23 one way and 32 the other. */
    @Test
    fun `a two-field variant binds its fields in order`() {
        assertEquals(23L, run(
            """
            $shape
            let s = Tri { b = 2, h = 3 }
            match s is
              Tri { b, h } -> b * 10 + h
              Circle { r = _ } -> 0 - 1
              Dot -> 0 - 2
            """
        ))
    }

    /** A payload-less constructor has nothing to bind and must still fold. */
    @Test
    fun `a payload-less variant bound then matched works`() {
        assertEquals(42L, run(
            """
            $shape
            let s = Dot
            match s is
              Dot -> 42
              Circle { r = _ } -> 0 - 1
              Tri { b = _, h = _ } -> 0 - 2
            """
        ))
    }

    /**
     * THE ARM MUST BE CHOSEN BY THE TAG, not by position. Building a `Tri` and
     * listing `Circle` first would answer -1 if the fold took the first case.
     */
    @Test
    fun `the tag picks the arm, not the order they are written`() {
        assertEquals(5L, run(
            """
            $shape
            let s = Tri { b = 5, h = 9 }
            match s is
              Circle { r = _ } -> 0 - 1
              Dot -> 0 - 2
              Tri { b, h = _ } -> b
            """
        ))
    }

    /**
     * READ TWICE, so the construction cannot be moved to a single read and must
     * survive. Both reads have to see the same thing.
     */
    @Test
    fun `a variant read twice is not folded away`() {
        assertEquals(14L, run(
            """
            $shape
            let s = Circle { r = 7 }
            let a = match s is
              Circle { r } -> r
              Tri { b = _, h = _ } -> 0
              Dot -> 0
            let b = match s is
              Circle { r } -> r
              Tri { b = _, h = _ } -> 0
              Dot -> 0
            a + b
            """
        ))
    }

    /** In a LOOP, which is where the measurement lives and where propagation runs. */
    @Test
    fun `the fold holds inside a self tail loop`() {
        assertEquals(55L, run(
            """
            $shape
            let go = { i, acc } -> if i <= 0 then acc else do
              let s = Circle { r = i }
              let v = match s is
                Circle { r } -> r
                Tri { b = _, h = _ } -> 0
                Dot -> 0
              go { i = i - 1, acc = acc + v }
            go { i = 10, acc = 0 }
            """
        ))
    }

    /** A two-field variant in a loop: sum of b over 1..10 with h ignored. */
    @Test
    fun `a two-field variant folds inside a loop`() {
        assertEquals(55L, run(
            """
            $shape
            let go = { i, acc } -> if i <= 0 then acc else do
              let s = Tri { b = i, h = i * 100 }
              let v = match s is
                Tri { b, h = _ } -> b
                Circle { r = _ } -> 0
                Dot -> 0
              go { i = i - 1, acc = acc + v }
            go { i = 10, acc = 0 }
            """
        ))
    }

    /**
     * A GUARD on the arm. The guard reads a bound payload field, so the fold has
     * to bind before testing: keep r > 5 of 1..10, so 6+7+8+9+10 = 40.
     */
    @Test
    fun `a guarded arm still sees its payload`() {
        assertEquals(40L, run(
            """
            $shape
            let go = { i, acc } -> if i <= 0 then acc else do
              let s = Circle { r = i }
              let v = match s is
                Circle { r } if r > 5 -> r
                Circle { r = _ } -> 0
                Tri { b = _, h = _ } -> 0
                Dot -> 0
              go { i = i - 1, acc = acc + v }
            go { i = 10, acc = 0 }
            """
        ))
    }

    /**
     * The payload field expressions must run exactly once and in order even
     * when the construction moves. `i * 2` and `i * 2 + 1` are distinguishable
     * from each other and from their swap.
     */
    @Test
    fun `payload expressions keep their values when the construction moves`() {
        assertEquals("20|21", run(
            """
            $shape
            use std::int
            let i = 10
            let s = Tri { b = i * 2, h = i * 2 + 1 }
            match s is
              Tri { b, h } -> "$(int::to_str { n = b })|$(int::to_str { n = h })"
              Circle { r = _ } -> "no"
              Dot -> "no"
            """
        ))
    }
}
