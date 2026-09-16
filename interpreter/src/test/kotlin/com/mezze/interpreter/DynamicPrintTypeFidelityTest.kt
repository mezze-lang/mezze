package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
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
 * | undeclared-variant payload binder                | WRONG   | WRONG     |
 *
 * The inline-annotation row is fixed here, and it was the worse of the two:
 * adding a type annotation turned a correct answer into a wrong one. Its cause is
 * in `TypedAstBuilder.buildLambdaParams` — an ANNOTATED param took its type from
 * a fresh conversion of the annotation, so its variable existed nowhere in the
 * binding's own type, while an UNANNOTATED param read the binding's solved type.
 * Monomorphization substitutes a binding's quantified params by name, so the
 * annotated form could never be specialized.
 *
 * The undeclared-variant row remains, and is what still holds `Dynamic` in place.
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

    // ── What still reaches Dynamic ────────────────────────────────────────
    //
    // The payload binder of an UNDECLARED variant. `show { a = Grams { n = … } }`
    // gives `n` no type of its own: it is not a quantified parameter of `show`, so
    // monomorphization has nothing to substitute and the printed position stays a
    // variable. Measured: mono visits that call and derives `args=null`, where the
    // same program over a plain parameter derives `[Money]`.

    private fun throughUndeclaredVariant(value: String, prelude: String = money) =
        MezzeTestHelper.run(
            """
            $prelude
            let show = { a } -> match a is
              Grams { n } -> n.to_dbg {}
            show { a = Grams { n = $value } }
            """.trimIndent(),
        )

    @Test
    @Disabled(
        "D.1 — renders `4`: a newtype erased to its payload. The payload binder " +
            "of an undeclared variant is not a specialization parameter, so " +
            "PrintTypeBuilder sees a VarType and Dynamic renders from the value.",
    )
    fun `a newtype through an undeclared-variant payload keeps its wrapper`() {
        assertEquals("Money.(4)", throughUndeclaredVariant("Money.(4)"))
    }

    @Test
    @Disabled("D.1 — renders `4`: Nat and Int are both Long at runtime, so the value cannot say which.")
    fun `a Nat through an undeclared-variant payload stays a Nat`() {
        assertEquals("Nat.(4)", throughUndeclaredVariant("4n", prelude = ""))
    }

    @Test
    @Disabled("D.1 — renders `{ numerator = 1, denominator = 2 }`: Frac is an ordinary two-field record at runtime.")
    fun `a Frac through an undeclared-variant payload is not printed as a record`() {
        assertEquals(
            "1//2",
            throughUndeclaredVariant(
                "frac::make { numerator = 1, denominator = 2 }",
                prelude = "use std::frac",
            ),
        )
    }
}
