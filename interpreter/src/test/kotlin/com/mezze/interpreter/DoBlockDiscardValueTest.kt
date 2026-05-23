package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Can a `do` block discard the value of an intermediate expression that
 * doesn't return `{}`? The pattern we want to support is:
 *
 *     do
 *       some_effectful_call_returning_int {}   ## value discarded
 *       42
 *
 * vs the alternative that requires explicit binding:
 *
 *     do
 *       let _ = some_effectful_call_returning_int {}
 *       42
 *
 * These tests pin down what the typechecker + interpreter currently
 * accept.
 */
class DoBlockDiscardValueTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `bare unit-returning call in do block followed by literal`() {
        val code = """
            let f = {} -> {}
            do
              f {}
              42
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `bare int-returning call in do block followed by literal`() {
        val code = """
            let f = {} -> 7
            do
              f {}
              42
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `let underscore discard then literal`() {
        val code = """
            let f = {} -> 7
            do
              let _ = f {}
              42
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `do block as closure body returning unit when last expression is unit`() {
        val code = """
            let make_unit_closure = {} -> { x: Int } -> do
              x
              {}
            let c = make_unit_closure {}
            c { x = 5 }
        """.trimIndent()
        // {} is MezzeUnit
        val result = eval(code)
        assertEquals("{}", result.toString())
    }

    @Test
    fun `do block as closure body discarding non-unit then returning unit`() {
        val code = """
            let returns_int = { x: Int } -> x + 1
            let closure = { x: Int } -> do
              returns_int { x = x }
              {}
            closure { x = 5 }
        """.trimIndent()
        val result = eval(code)
        assertEquals("{}", result.toString())
    }

    @Test
    fun `bare call with lambda-arg then match — same shape as race helper`() {
        // Reproduces the parse failure I hit in the race/with_timeout
        // helpers: a bare statement-position call whose argument record
        // contains a lambda, followed by a `match` expression as the
        // do-block result.
        val code = """
            let take_thunk = { f: {} -> Bool } -> f {}
            let xs = [1, 2, 3]
            do
              take_thunk { f = {} -> True }
              match xs is
                [a, b, c] -> a + b + c
                _         -> -1
        """.trimIndent()
        assertEquals(6L, eval(code))
    }

    @Test
    fun `let underscore wraps the same shape`() {
        // Same as above but with explicit let _ = — for comparison.
        val code = """
            let take_thunk = { f: {} -> Bool } -> f {}
            let xs = [1, 2, 3]
            do
              let _ = take_thunk { f = {} -> True }
              match xs is
                [a, b, c] -> a + b + c
                _         -> -1
        """.trimIndent()
        assertEquals(6L, eval(code))
    }

    @Test
    fun `bare effect-op call then match in do block`() {
        // Closer reproduction of the with_timeout failure: an effect-op
        // call (`Eff.op { ... }`) as a bare statement, followed by a
        // `match` as the result.
        val code = """
            effect Eff where {
              tick: { f: {} -> Bool } -> {} <Eff>,
            }
            impl Eff for MockEff where {
              tick = { f } -> {},
            }
            let xs = [1, 2, 3]
            perform MockEff in do
              Eff.tick { f = {} -> True }
              match xs is
                [a, b, c] -> a + b + c
                _         -> -1
        """.trimIndent()
        assertEquals(6L, eval(code))
    }
}
