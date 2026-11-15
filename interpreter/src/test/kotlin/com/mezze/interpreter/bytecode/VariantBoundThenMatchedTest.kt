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

    // ───────────── a CONDITIONAL scrutinee ─────────────

    /**
     * `match (if c then A else B) is …` pushes the match into the branches and
     * folds each, so the match disappears instead of being duplicated. Only
     * when BOTH branches construct a variant — that is what makes it free,
     * since each distributed match then collapses to the one case its tag
     * selects.
     *
     * `bench/cases/recfields` at n=2e7: 2 975 ps/iter with 25 young collections
     * before, 1 068 and 1 after, against a 785 ps ceiling carrying no variant
     * at all. A function RETURNING the variant measured the SAME 2 985 before
     * and 1 116 after — so the call was never the obstacle, the `if` between
     * the construction and the match was.
     *
     * BOTH ARMS ARE EXERCISED, because folding to the wrong branch's case is
     * the way this breaks and either answer alone would hide it.
     */
    @Test
    fun `a conditional scrutinee folds in both directions`() {
        val prog = """
            $shape
            let pick = { c } -> match (if c then Circle { r = 7 } else Tri { b = 2, h = 3 }) is
              Circle { r } -> r
              Tri { b, h } -> b * 10 + h
              Dot -> 0 - 1
            pick { c = %s }
        """.trimIndent()
        assertEquals(7L, run(prog.format("True")))
        assertEquals(23L, run(prog.format("False")))
    }

    /** Three constructors reached through NESTED ifs, each by recursion. */
    @Test
    fun `nested conditional scrutinees fold`() {
        val prog = """
            $shape
            let pick = { i } -> match (if i == 0 then Dot
                                       else if i == 1 then Circle { r = 5 }
                                       else Tri { b = 1, h = 2 }) is
              Dot -> 100
              Circle { r } -> r
              Tri { b, h } -> b + h
            pick { i = %d }
        """.trimIndent()
        assertEquals(100L, run(prog.format(0)))
        assertEquals(5L, run(prog.format(1)))
        assertEquals(3L, run(prog.format(2)))
    }

    /**
     * BOTH BRANCHES THE SAME TAG. They select the same case, so its body is
     * duplicated — the one place this rewrite grows the program, bounded by the
     * branch count. The two payloads must still reach their own branch.
     */
    @Test
    fun `both branches carrying one tag keep their own payloads`() {
        val prog = """
            $shape
            let pick = { c } -> match (if c then Circle { r = 11 } else Circle { r = 22 }) is
              Circle { r } -> r
              Tri { b = _, h = _ } -> 0 - 1
              Dot -> 0 - 2
            pick { c = %s }
        """.trimIndent()
        assertEquals(11L, run(prog.format("True")))
        assertEquals(22L, run(prog.format("False")))
    }

    /**
     * ONE BRANCH IS NOT A CONSTRUCTION, so the rewrite must decline outright
     * rather than distribute and leave a duplicated match behind. The answer
     * has to survive the refusal.
     */
    @Test
    fun `a branch that is not a construction declines and stays correct`() {
        val prog = """
            $shape
            let other = { x } -> Tri { b = x, h = x }
            let pick = { c } -> match (if c then Circle { r = 9 } else other { x = 4 }) is
              Circle { r } -> r
              Tri { b, h } -> b + h
              Dot -> 0 - 1
            pick { c = %s }
        """.trimIndent()
        assertEquals(9L, run(prog.format("True")))
        assertEquals(8L, run(prog.format("False")))
    }

    /** A function returning the variant, matched at the call site, in a loop. */
    @Test
    fun `a function returning a variant folds at the call site`() {
        assertEquals(30L, run(
            """
            $shape
            let mk = { i } -> if i % 2 == 0 then Circle { r = i } else Dot
            let go = { i, acc } -> if i <= 0 then acc else do
              let v = match mk { i = i } is
                Circle { r } -> r
                Tri { b = _, h = _ } -> 0
                Dot -> 0
              go { i = i - 1, acc = acc + v }
            go { i = 10, acc = 0 }
            """
        ))
    }

    /**
     * The condition must be evaluated ONCE and its own branch taken. `i % 3`
     * gives a different constructor per residue, so a mis-taken branch shows
     * up as a different total: over 1..9, residue 0 contributes i, residue 1
     * contributes 100, residue 2 contributes 0 — 3+6+9 + 100*3 = 318.
     */
    @Test
    fun `the condition picks the branch for every residue`() {
        assertEquals(318L, run(
            """
            $shape
            let go = { i, acc } -> if i <= 0 then acc else do
              let v = match (if i % 3 == 0 then Circle { r = i }
                             else if i % 3 == 1 then Dot
                             else Tri { b = 0, h = 0 }) is
                Circle { r } -> r
                Dot -> 100
                Tri { b, h } -> b + h
              go { i = i - 1, acc = acc + v }
            go { i = 9, acc = 0 }
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
