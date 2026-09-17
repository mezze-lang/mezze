package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * D.1 — does `PrintType.Dynamic` render faithfully?
 *
 * `Dynamic` is the one position in `PrintType` with no static type: the printer
 * reads the shape from the runtime VALUE alone. `PrintTypeBuilder` emits it from
 * exactly one arm, `is MezzeType.VarType`, whose doc argues that is harmless — a
 * variable in a printed position "is NOT a concrete type with a lost shape".
 *
 * BUG 12 already falsified that argument once, for the recursion cut. Value-only
 * rendering cannot recover the types whose printed form is not a function of
 * their runtime representation: a newtype is erased to its payload, `Nat` and
 * `Int` are both Long, `Frac` is an ordinary two-field record, and a
 * one-character `Str` is indistinguishable from a `Char`. Those are precisely
 * what `PrintType` exists to disambiguate.
 *
 * So the question was never whether the arm is reachable — measured across the
 * interpreter gate it fires 34 times from 17 distinct sites, every one a
 * `to_dbg`/`to_str` on a receiver that stayed a type variable. The question is
 * whether a value of one of those types can reach it. It can, and the answer it
 * gives is silently wrong.
 *
 * ## What that measurement turned up
 *
 * Writing the same function several ways showed the arm is reached for some of
 * them and not others, which made the cause visible:
 *
 * | form                                            | before  | now       |
 * |-------------------------------------------------|---------|-----------|
 * | `let show: { x: 'a } -> Str = { x } -> …`        | correct | correct   |
 * | `let show = { x: 'a } -> …` (inline annotation)  | WRONG   | **fixed** |
 * | `let show = { x } -> …`     (no annotation)      | correct | correct   |
 * | match-arm binder, DECLARED payload, in a lambda   | WRONG   | **fixed** |
 * | match-arm binder, UNDECLARED payload              | WRONG   | **fixed** |
 *
 * Two independent causes, each of which made a correct program print the wrong
 * thing:
 *
 *  - The inline annotation. `TypedAstBuilder.buildLambdaParams` took an ANNOTATED
 *    param's type from a fresh conversion of the annotation, so its variable
 *    existed nowhere in the binding's own type, while an UNANNOTATED param read
 *    the binding's solved type. Monomorphization substitutes a binding's
 *    quantified params BY NAME, so the annotated form could never be specialized —
 *    writing a type annotation turned a correct answer into a wrong one.
 *
 *  - The match-arm binder. Its type came from the solver, and the solver leaves it
 *    an unresolved variable inside a lambda body. That hole was preferred over the
 *    scrutinee's own declared shape, which — the scrutinee being concrete —
 *    DETERMINES the binder's type. An absent answer was outranking a real one.
 *
 *  - The UNDECLARED variant's payload. With no declaration to read a payload
 *    type from, the solver invents one and relies on unification to fill it —
 *    which did not reach the emitted type, leaving `VariantType([Grams])` whose
 *    payload was `{ n: 'y }` while the payload EXPRESSION beside it was already
 *    typed `{ n: Money }`. Monomorphization then derived `args=null` and never
 *    specialized. The construction is what gives the constructor its payload, so
 *    the payload's own type IS the shape; it is now written in where the solved
 *    shape left a hole
 */
class DynamicPrintTypeFidelityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val money = "newtype Money = Int"

    // ── An annotation must not change what a program means ────────────────

    @Test
    fun `an annotated param renders a newtype the same as an unannotated one`() {
        val annotated = MezzeTestHelper.run(
            "$money\nlet show = { x: 'a } -> x.to_dbg {}\nshow { x = Money.(4) }",
        )
        val bare = MezzeTestHelper.run(
            "$money\nlet show = { x } -> x.to_dbg {}\nshow { x = Money.(4) }",
        )
        assertEquals("Money.(4)", bare)
        assertEquals(bare, annotated, "writing `{ x: 'a }` instead of `{ x }` changed the answer")
    }

    @Test
    fun `an annotated param specializes per call site`() {
        // Two calls of different types through one annotated generic. If the
        // param's variable were disconnected from the binding's own, neither call
        // would specialize and both would print through the value-only path.
        assertEquals(
            "Money.(4)7",
            MezzeTestHelper.run(
                """
                $money
                let show = { x: 'a } -> x.to_dbg {}
                show { x = Money.(4) }.concat { r = show { x = 7 } }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a full-arrow annotation agrees with both`() {
        // The form that always worked, pinned so the three stay tied together.
        assertEquals(
            "Money.(4)",
            MezzeTestHelper.run(
                "$money\nlet show: { x: 'a } -> Str = { x } -> x.to_dbg {}\nshow { x = Money.(4) }",
            ),
        )
    }

    @Test
    fun `the concrete path renders all three types correctly - the control`() {
        // If these disagreed with the expectations below, the bug would be in the
        // renderer rather than in a missing static type.
        assertEquals("Money.(4)", MezzeTestHelper.run("$money\nMoney.(4).to_dbg {}"))
        assertEquals("Nat.(4)", MezzeTestHelper.run("(4n).to_dbg {}"))
        assertEquals(
            "1//2",
            MezzeTestHelper.run(
                "use std::frac\nfrac::make { numerator = 1, denominator = 2 }.to_dbg {}",
            ),
        )
    }

    // ── Through a match-arm payload binder, inside a lambda ───────────────

    private fun throughDeclaredVariant(payloadType: String, value: String, prelude: String = "") =
        MezzeTestHelper.run(
            """
            $prelude
            type Amt = | Grams { n: $payloadType } |
            let show = { a: Amt } -> match a is
              Grams { n } -> n.to_dbg {}
            show { a = Grams { n = $value } }
            """.trimIndent(),
        )

    @Test
    fun `a newtype through a match-arm binder inside a lambda keeps its wrapper`() {
        // Was `4`. The solver leaves this binder an unresolved variable, and that
        // was preferred over the scrutinee's own declared shape — which, `Amt`
        // being concrete, DETERMINES that `n` is a `Money`. A hole was winning
        // over an answer. The identical match at top level was always correct,
        // because there the solver does resolve it.
        assertEquals("Money.(4)", throughDeclaredVariant("Money", "Money.(4)", money))
    }

    @Test
    fun `a Nat through a match-arm binder inside a lambda stays a Nat`() {
        assertEquals("Nat.(4)", throughDeclaredVariant("Nat", "4n"))
    }

    @Test
    fun `a Frac through a match-arm binder inside a lambda is not printed as a record`() {
        assertEquals(
            "1//2",
            throughDeclaredVariant(
                "Frac",
                "frac::make { numerator = 1, denominator = 2 }",
                prelude = "use std::frac",
            ),
        )
    }

    // ── Through an UNDECLARED variant's payload ───────────────────────────

    @Test
        fun `a newtype through an UNDECLARED-variant payload keeps its wrapper`() {
        assertEquals(
            "Money.(4)",
            MezzeTestHelper.run(
                """
                $money
                let show = { a } -> match a is
                  Grams { n } -> n.to_dbg {}
                show { a = Grams { n = Money.(4) } }
                """.trimIndent(),
            ),
        )
    }
}
