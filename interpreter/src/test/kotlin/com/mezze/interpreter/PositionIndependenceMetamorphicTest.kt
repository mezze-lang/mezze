package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Move-4 (#24) — L2 generative/metamorphic properties.
 *
 * Two properties, checked over a deterministic family of well-typed expressions
 * (no external property-test dependency — a fixed generator keeps it reproducible):
 *
 *  (1) POSITION-INDEPENDENCE (metamorphic): the SAME expression must evaluate to
 *      the SAME value whether it appears as a top-level expression, as the tail of
 *      a `do` block, or as a `match` arm result. The audit found several bugs
 *      (#332/#333/#215) where an expression's type/dispatch differed by syntactic
 *      position; this property would have caught them.
 *
 *  (2) WELL-TYPED ⇒ NO-STUCK: a program that type-checks must run to a value —
 *      never a ClassCastException / "Field not found" / stuck dispatch. The whole
 *      L-xx family (dict-through-HOF, subset-key dispatch, name-launder) manifested
 *      as exactly these runtime failures on type-checked code.
 *
 * The generator emits closed, effect-free expressions so each can be dropped into
 * any of the three positions unchanged.
 */
class PositionIndependenceMetamorphicTest {

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    /**
     * A deterministic corpus of well-typed, closed, effect-free expressions paired
     * with the value they must evaluate to. Kept small and varied — the point is
     * coverage of dispatch/record/variant/HOF shapes, not volume.
     */
    private val exprs: List<Pair<String, Any?>> = listOf(
        "1 + 2 * 3" to 7L,
        "10 - 4 - 3" to 3L,                                   // left-assoc arithmetic
        "(5 < 10) && (10 > 3)" to true,                       // ability ops (Ord) + &&
        "\"a\" == \"a\"" to true,                             // Eq on Str
        "5 == 5 && 6 != 7" to true,
        "{ x = 2, y = 3 }.x + { x = 2, y = 3 }.y" to 5L,      // record field access
        "({ n } -> n + 1) { n = 41 }" to 42L,                 // inline lambda application
        "({ f, x } -> f { x = x }) { f = { x } -> x * 10, x = 5 }" to 50L, // HOF (wrap-shape)
        "if 3 < 4 then 100 else 200" to 100L,
    )

    @Test
    fun `an expression yields the same value at top-level, in a do-block, and in a match-arm`() {
        for ((expr, expected) in exprs) {
            val topLevel = run(expr)
            assertEquals(expected, topLevel, "top-level value wrong for: $expr")

            // (b) same expression as the tail of a do-block, via a let binding of
            // an unrelated value first (forces the do-block binding machinery).
            val doBlock = run(
                """
                let main = {} -> do
                  let _ignored = 0
                  $expr
                main {}
                """.trimIndent()
            )
            assertEquals(expected, doBlock,
                "do-block value diverged from top-level for: $expr (top=$topLevel, do=$doBlock)")

            // (c) same expression as a match-arm result — the scrutinee is a fixed
            // bool so the arm always fires; the arm body is the expression.
            val matchArm = run(
                """
                match True is
                  True -> $expr
                  False -> $expr
                """.trimIndent()
            )
            assertEquals(expected, matchArm,
                "match-arm value diverged from top-level for: $expr (top=$topLevel, arm=$matchArm)")
        }
    }

    @Test
    fun `every well-typed expression runs to a value (no stuck dispatch)`() {
        // NO-STUCK oracle: if any of these threw ClassCast / FieldNotFound / stuck,
        // MezzeTestHelper.run would propagate it and fail the test with the source.
        for ((expr, _) in exprs) {
            val v = assertDoesNotThrow({ run(expr) }, "well-typed expression got stuck: $expr")
            assertNotNull(v, "well-typed expression produced null: $expr")
        }
    }
}
