package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A `let`'s right-hand side is evaluated before the name is bound (BUG 32).
 *
 * `let n = n` in a do-block, over a top-level `n`, used to read its own slot
 * before anything was stored there and die with
 * `FrameSlotTypeException … Frame slot kind Object expected, but got Illegal`.
 * Measured identically for a value, a plain function and a constrained one; a
 * RENAMED alias was always fine, which is what said the name was the problem
 * rather than the aliasing.
 *
 * The slot is still allocated before the RHS compiles — `StmtIR.Let` needs it —
 * but the NAME means whatever it meant until the let completes.
 *
 * A RECURSIVE let is the opposite and the last two tests hold that line: `let f
 * = { n } -> … f …` only works because `f` IS visible inside its own RHS.
 */
class LetShadowsOuterBindingTest {
    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    @Test fun `a value re-bound to its own name reads the outer one`() {
        assertEquals(
            7L,
            run(
                """
                let n: Int = 7
                let go: {} -> Int = {} -> do
                  let n = n
                  n
                go {}
                """
            ),
        )
    }

    @Test fun `a function re-bound to its own name reads the outer one`() {
        assertEquals(
            42L,
            run(
                """
                let twice: { n: Int } -> Int = { n } -> n * 2
                let go: {} -> Int = {} -> do
                  let twice = twice
                  twice { n = 21 }
                go {}
                """
            ),
        )
    }

    /** A CONSTRAINED function, which routes through the dictionary path. */
    @Test fun `a constrained function re-bound to its own name reads the outer one`() {
        assertEquals(
            2L,
            run(
                """
                use std::abilities::compare::{ Ord }
                let pick: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
                let go: {} -> Int = {} -> do
                  let pick = pick
                  pick { a = 1, b = 2 }
                go {}
                """
            ),
        )
    }

    /** The re-bound value is usable AFTER the let, as its own binding. */
    @Test fun `the new binding is in scope after the let`() {
        assertEquals(
            14L,
            run(
                """
                let n: Int = 7
                let go: {} -> Int = {} -> do
                  let n = n
                  let m = n + n
                  m
                go {}
                """
            ),
        )
    }

    /** A renamed alias — always worked, kept so the fix cannot trade one for the other. */
    @Test fun `a renamed alias still works`() {
        assertEquals(
            2L,
            run(
                """
                use std::abilities::compare::{ Ord }
                let pick: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
                let go: {} -> Int = {} -> do
                  let chooser = pick
                  chooser { a = 1, b = 2 }
                go {}
                """
            ),
        )
    }

    /** A recursive let still sees itself in its own RHS. */
    @Test fun `a recursive let still recurses`() {
        assertEquals(
            120L,
            run(
                """
                let go: {} -> Int = {} -> do
                  let fact = { n: Int } -> if n <= 1 then 1 else n * fact { n = n - 1 }
                  fact { n = 5 }
                go {}
                """
            ),
        )
    }

    /**
     * A self-referencing let whose name ALSO exists at top level refers OUTWARD.
     *
     * Pinned as MEASURED, and flagged rather than asserted as obviously right.
     * It used to CRASH, so either answer is an improvement, and this one follows
     * from the rule above: the RHS means whatever the name meant before the let,
     * and here that is the top-level `down`.
     *
     * Mezze infers recursion rather than marking it, and the inference only
     * fires when the name would otherwise be unbound — which is why the test
     * above, with no outer `fact`, recurses. Whether a user writing this MEANT
     * recursion is a language question, not a defect to patch; see BUG 32 in
     * planned_features/known-bugs.md. If the answer becomes "recursion wins",
     * this test flips deliberately.
     */
    @Test fun `a self-referencing let shadowing a top-level name refers outward`() {
        assertEquals(
            999L,
            run(
                """
                let down: { n: Int } -> Int = { n } -> 999
                let go: {} -> Int = {} -> do
                  let down = { n: Int } -> if n <= 0 then 0 else down { n = n - 1 }
                  down { n = 3 }
                go {}
                """
            ),
        )
    }
}
