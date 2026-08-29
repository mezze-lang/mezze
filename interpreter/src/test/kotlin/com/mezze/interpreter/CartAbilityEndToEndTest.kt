package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * End-to-end check for the three typechecker fixes landed together AND the
 * real user program: an ability method (`x.total {}`) called inside both a
 * `map` callback (interpolated, chained into `.to_str {}`) and a `fold`
 * callback (`acc + x.total {}`), with the constraint discharged against a
 * concrete `Item`. Must type-check AND run, printing per-item and total costs.
 */
class CartAbilityEndToEndTest {

    @Test
    fun `cart with a user ability typechecks and runs`() {
        val source = """
            use std::effects::log::{ Console, println }

            type Item = { name: Str, price: Int, qty: Int }

            ability Priced for 'a where {
              total: { it: 'a } -> Int
            }

            impl Priced for Item where {
              total = { it } -> it.price * it.qty
            }

            let main = {} -> perform Console in do
              let cart = [
                { name = "olive oil", price = 899, qty = 2 },
                { name = "pita", price = 199, qty = 3 },
              ]

              cart.map { f = { x } ->
                println { msg = "${'$'}(x.name): ${'$'}(x.total {}.to_str {})" } }

              let sum = cart.fold {
                init = 0,
                f = { acc, x } -> acc + x.total {},
              }
              println { msg = "total: ${'$'}(sum.to_str {}) cents" }

            main {}
        """.trimIndent()

        val out = MezzeTestHelper.runWithOutput(source).output
        assertTrue(out.contains("olive oil: 1798"), "expected 'olive oil: 1798' in output, got: <$out>")
        assertTrue(out.contains("pita: 597"), "expected 'pita: 597' in output, got: <$out>")
        // The fold total: 1798 + 597 = 2395.
        assertTrue(out.contains("total: 2395 cents"), "expected 'total: 2395 cents' in output, got: <$out>")
    }
}
