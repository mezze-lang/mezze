package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression for: method call on the return value of an effect operation
 * crashed at runtime with `Dictionary slot 0 does not contain a function: null`.
 *
 * Root cause: TypedAstBuilder.buildExpr for `Effect.op {}` used `substitution
 * .apply(contextType)` for the TypedPerformOp.type. When the caller passed an
 * unconstrained stub VarType (e.g. `let_os` from a let binding without an
 * annotation), the substitution had nothing to resolve and the perform-op's
 * type stayed a VarType. Downstream method-call lowering then took the
 * VarType branch (dictionary passing) instead of resolving the concrete impl,
 * read frame slot 0 expecting a function, and crashed.
 *
 * Fix: in TypedAstBuilder.buildExpr for ParsedFunctionCall over an effect,
 * fall back to the operation's declared return type (read directly from the
 * effect decl) when contextType doesn't resolve.
 */
class EffectOpReturnTypeTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `let-binding of effect-op result keeps concrete return type for method calls`() {
        val code = """
            effect Greet where {
              greet : {} -> Str <Greet>,
            }
            impl Greet for HiHandler where {
              greet = {} -> "hello",
            }

            let main = {} ->
              perform HiHandler in do
                let g = Greet.greet {}
                g.size {}

            main {}
        """.trimIndent()
        val result = eval(code)
        assertEquals(5L, result, "size of \"hello\" should be 5")
    }

    @Test
    fun `effect-op returning Seq supports method calls`() {
        val code = """
            effect Items where {
              get_items : {} -> [Str] <Items>,
            }
            impl Items for ListHandler where {
              get_items = {} -> ["a", "b", "c"],
            }

            let main = {} ->
              perform ListHandler in do
                let xs = Items.get_items {}
                xs.size {}

            main {}
        """.trimIndent()
        val result = eval(code)
        assertEquals(3L, result, "size of [a, b, c] should be 3")
    }

    @Test
    fun `to_upper inherent method on Str returned from effect-op`() {
        val code = """
            effect Greet where {
              greet : {} -> Str <Greet>,
            }
            impl Greet for HiHandler where {
              greet = {} -> "hello",
            }

            let main = {} ->
              perform HiHandler in do
                let g = Greet.greet {}
                g.to_upper {}

            main {}
        """.trimIndent()
        val result = eval(code)
        assertEquals("HELLO", result.toString())
    }

    @Test
    fun `method call directly on effect-op call result works`() {
        // No intermediate let — `Greet.greet {}.size {}` directly.
        val code = """
            effect Greet where {
              greet : {} -> Str <Greet>,
            }
            impl Greet for HiHandler where {
              greet = {} -> "world",
            }

            let main = {} ->
              perform HiHandler in
                Greet.greet {} .size {}

            main {}
        """.trimIndent()
        val result = eval(code)
        assertEquals(5L, result, "size of \"world\" should be 5")
    }
}
