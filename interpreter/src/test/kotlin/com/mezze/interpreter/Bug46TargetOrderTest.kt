package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BUG 46, characterised properly: inside a FUNCTION, `sorted_set::map` orders
 * its result by the SOURCE element's `Ord`, not the target's.
 *
 * THE FILED REPORT WAS WRONG, and this suite's sibling test pins the mistake.
 * It said a let-BOUND map keeps the target order and only argument position
 * loses it. Measured across four shapes:
 *
 *     top-level, bound          [5, 3, 1]  correct
 *     top-level, argument pos   [1, 3, 5]
 *     inside a function, bound  [1, 3, 5]
 *     inside a function, arg    [1, 3, 5]
 *
 * The saving condition is being a TOP-LEVEL EXPRESSION, not being bound.
 * Annotating the binding or the lambda's return does not help either. That
 * matters because a real program has everything inside `main`: a built binary
 * loses the order in EVERY shape, and the suite only ever saw the one that
 * works because its sources are top-level expressions.
 *
 * AND IT IS NOT "NATURAL ORDER". Reading `1, 3, 5` as natural was a
 * coincidence of the source being Int. With a DESCENDING source and an
 * ASCENDING target, and then the reverse, the result follows the SOURCE both
 * times — so `with Ord 'b` is being discharged with the `Ord 'a` dictionary.
 *
 * TWO MECHANISMS RULED OUT by experiment, so the next reader does not repeat
 * them:
 *  - NOT the D-1 collapse ("a top-level `let` collapses the variable onto the
 *    one type with a hand-written impl"). Adding a SECOND type with its own
 *    hand-written `Ord` leaves the top-level case still correct, so it is
 *    genuinely resolving the target there rather than picking the only
 *    candidate.
 *  - NOT the `VAR_SEP` prefix scan in `abilityOpDictSlot` handing back another
 *    variable's slot. Instrumented, it never fires for `lt`/`le`/`gt`/`ge`
 *    here — the comparator closure lives in the stdlib and was compiled with
 *    it, so what varies is the dictionary the CALLER passes.
 *
 * THESE TESTS ASSERT THE BROKEN BEHAVIOUR ON PURPOSE. A failure means someone
 * fixed the dictionary arrival and should fold them into the correct-order
 * tests.
 */
class Bug46TargetOrderTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun elemsOf(source: String): List<Any?> = when (val r = MezzeTestHelper.run(source.trimIndent())) {
        is Array<*> -> r.toList()
        is Iterable<*> -> r.toList()
        else -> error("not a seq: ${r.javaClass.name}")
    }

    /** `R` descends, `Q` ascends — two hand-written `Ord`s, so neither is "the
     *  only impl" and a collapse onto one cannot explain a right answer. */
    private val two = """
        use std::collections::sorted_set
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype R = Int
        let un: { v: R } -> Int = { v } -> match v is
          R.(n) -> n
        impl Hash for R where { hash = { it } -> un { v = it } }
        impl Eq for R where {
          eq = { it, r } -> un { v = it } == un { v = r },
          neq = { it, r } -> if un { v = it } == un { v = r } then False else True,
        }
        impl Ord for R where {
          lt = { it, r } -> un { v = it } > un { v = r },
          le = { it, r } -> un { v = it } >= un { v = r },
          gt = { it, r } -> un { v = it } < un { v = r },
          ge = { it, r } -> un { v = it } <= un { v = r },
        }
        newtype Q = Int
        let uq: { v: Q } -> Int = { v } -> match v is
          Q.(n) -> n
        impl Hash for Q where { hash = { it } -> uq { v = it } }
        impl Eq for Q where {
          eq = { it, r } -> uq { v = it } == uq { v = r },
          neq = { it, r } -> if uq { v = it } == uq { v = r } then False else True,
        }
        impl Ord for Q where {
          lt = { it, r } -> uq { v = it } < uq { v = r },
          le = { it, r } -> uq { v = it } <= uq { v = r },
          gt = { it, r } -> uq { v = it } > uq { v = r },
          ge = { it, r } -> uq { v = it } >= uq { v = r },
        }
    """

    /** THE CONTROL: a SortedSet R built by `from_seq` really is descending, so
     *  the cases below are reading a real source order and not an artefact. */
    @Test
    fun `a SortedSet R is descending to begin with`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            """
            $two
            let rs = { } -> sorted_set::from_seq { xs = [R.(1), R.(5), R.(3)] }
            let go = { } -> seq::map { xs = sorted_set::to_seq { s = rs {} }, f = { x } -> un { v = x } }
            go {}
            """,
        ),
    )

    /** DESCENDING source, ASCENDING target, inside a function. Correct is
     *  `1, 3, 5` — Q ascends. It comes out descending: the source's order. */
    @Test
    fun `inside a function the SOURCE order wins — descending to ascending`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            """
            $two
            let rs = { } -> sorted_set::from_seq { xs = [R.(1), R.(5), R.(3)] }
            let go = { } -> do
              let mapped = sorted_set::map { s = rs {}, f = { val } -> Q.(un { v = val }) }
              seq::map { xs = sorted_set::to_seq { s = mapped }, f = { x } -> uq { v = x } }
            go {}
            """,
        ),
        "1, 3, 5 would mean Q's ascending Ord was used and BUG 46 is fixed",
    )

    /** And the reverse, which is what shows it follows the SOURCE rather than
     *  defaulting to natural order: ASCENDING source, DESCENDING target,
     *  result ascending. */
    @Test
    fun `inside a function the SOURCE order wins — ascending to descending`() = assertEquals(
        listOf(1L, 3L, 5L),
        elemsOf(
            """
            $two
            let qs = { } -> sorted_set::from_seq { xs = [Q.(1), Q.(5), Q.(3)] }
            let go = { } -> do
              let mapped = sorted_set::map { s = qs {}, f = { val } -> R.(uq { v = val }) }
              seq::map { xs = sorted_set::to_seq { s = mapped }, f = { x } -> un { v = x } }
            go {}
            """,
        ),
        "5, 3, 1 would mean R's descending Ord was used and BUG 46 is fixed",
    )

    /** THE SAME EXPRESSION AT TOP LEVEL IS CORRECT, which is the whole shape of
     *  the bug: the target's `Ord` is reachable, just not from inside a
     *  function. */
    @Test
    fun `at top level the target order is used`() = assertEquals(
        listOf(5L, 3L, 1L),
        elemsOf(
            """
            $two
            do
              let qs = sorted_set::from_seq { xs = [Q.(1), Q.(5), Q.(3)] }
              let mapped = sorted_set::map { s = qs, f = { val } -> R.(uq { v = val }) }
              seq::map { xs = sorted_set::to_seq { s = mapped }, f = { x } -> un { v = x } }
            """,
        ),
        "if this breaks, the top-level path regressed — it is the one that works",
    )
}
