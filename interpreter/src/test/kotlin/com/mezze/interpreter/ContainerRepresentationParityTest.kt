package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * B.1 — the same container type, written two ways, must behave identically.
 *
 * A builtin container has TWO internal representations: the dedicated node
 * (`TypeApplication("Seq", listOf(P))`) and the name-carried application
 * (`TypeApplication("Seq", [P])`). B.2 measured the split across the typed
 * stdlib at 5722 name-carried against 1546 dedicated, so the name-carried form
 * is the DOMINANT one — and every consumer that knows only one of them
 * disagrees with every consumer that knows the other.
 *
 * Two such consumers were live when this was written, and the surface symptom is
 * the same program spelled `Seq P` instead of `[P]`:
 *
 *  1. `DerivabilityRules` had `is SeqType` / `is ListType` arms and no
 *     TypeApplication arm, so `let a: Seq P = …; a == b` was REJECTED outright —
 *     "Cannot use '==' with Seq P. To enable this, implement: impl Eq for Seq P".
 *  2. With that fixed it compiled but answered FALSE, because
 *     `typeMatchesTarget` aliased a container only in the direction
 *     dedicated-receiver → name-carried-target. A name-carried RECEIVER meeting
 *     an impl written `for ['a]` had no arm and missed, so the comparison fell to
 *     structural equality and ignored the element's own `Eq`.
 *
 * The second is the worse failure of the two: it is silent. Fixing only the
 * first would have converted a loud rejection into a wrong answer.
 *
 * `P` compares MOD 10, so `P.(1) == P.(11)` is true by its instance and false
 * structurally — which is what makes "did the element's instance actually get
 * used?" observable rather than a coin flip.
 */
class ContainerRepresentationParityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val modTenEq = """
        use std::collections::seq
        use std::abilities::compare::{ Eq, Hash }
        newtype P = Int
        impl Hash for P where {
          hash = { it } -> match it is P.(a) -> a % 10
        }
        impl Eq for P where {
          eq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 == b % 10,
          neq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 != b % 10
        }
    """.trimIndent()

    private fun run(tail: String) = MezzeTestHelper.run("$modTenEq\n$tail")

    @Test
    fun `a seq written with brackets uses the element's Eq`() {
        assertEquals(true, run("let a: [P] = [P.(1)]\nlet b: [P] = [P.(11)]\na == b"))
    }

    @Test
    fun `a seq written by NAME uses the element's Eq the same way`() {
        assertEquals(true, run("let a: Seq P = [P.(1)]\nlet b: Seq P = [P.(11)]\na == b"))
    }

    @Test
    fun `a nested seq written with brackets uses the element's Eq`() {
        assertEquals(true, run("let a: [[P]] = [[P.(1)]]\nlet b: [[P]] = [[P.(11)]]\na == b"))
    }

    @Test
    fun `a nested seq written by NAME uses the element's Eq the same way`() {
        assertEquals(true, run("let a: Seq (Seq P) = [[P.(1)]]\nlet b: Seq (Seq P) = [[P.(11)]]\na == b"))
    }

    @Test
    fun `a list written by name uses the element's Eq`() {
        // List already worked before the fix — kept so the pair is symmetric and
        // a future change cannot regress it while Seq stays green.
        assertEquals(
            true,
            run(
                """
                use std::collections::list
                let a: List P = list::from_seq { xs = [P.(1)] }
                let b: List P = list::from_seq { xs = [P.(11)] }
                a == b
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the negative case still answers false in both spellings`() {
        // Guards against a fix that makes everything compare equal: 1 and 12 are
        // NOT equal mod 10, in either representation.
        assertEquals(false, run("let a: [P] = [P.(1)]\nlet b: [P] = [P.(12)]\na == b"))
        assertEquals(false, run("let a: Seq P = [P.(1)]\nlet b: Seq P = [P.(12)]\na == b"))
    }
}
