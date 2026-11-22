package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A name means the binding it resolves to, not the last local function that was
 * called that.
 *
 * Lowering keeps what it knows about local functions — an alias of a top-level
 * function, a recursive lambda, a constrained lambda — in tables keyed by NAME,
 * and reads them at a call through that name. Nothing ended an entry when the
 * name came to mean something else, and a lambda's hash did not say which entry
 * its body had read. Each test is a program that ran the wrong function.
 */
class LocalFunctionNameTest {
    private val picks = """
        use std::abilities::compare::{ Ord }
        let pick_hi: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
        let pick_lo: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a <= b then a else b
    """.trimIndent()

    @Test fun `two lambdas that read alike but call different aliases are two functions`() {
        // hi → 5, lo → 1. Both inner lambdas were one hash, so lo ran pick_hi: 55.
        val src = """
            $picks
            let hi: {} -> Int = {} -> do
              let pick = pick_hi
              let f = { x: Int } -> pick { a = x, b = 5 }
              f { x = 1 }
            let lo: {} -> Int = {} -> do
              let pick = pick_lo
              let f = { x: Int } -> pick { a = x, b = 5 }
              f { x = 1 }
            hi {} * 10 + lo {}
        """.trimIndent()
        assertEquals(51L, MezzeTestHelper.run(src))
    }

    @Test fun `a parameter that shadows an alias is the parameter`() {
        val src = """
            $picks
            let go: {} -> Int = {} -> do
              let pick = pick_hi
              let call_it = { pick: { a: Int, b: Int } -> Int } -> pick { a = 1, b = 2 }
              call_it { pick = { a, b } -> 222 }
            go {}
        """.trimIndent()
        assertEquals(222L, MezzeTestHelper.run(src))
    }

    @Test fun `a local lambda is not replaced by a top-level function of the same name`() {
        val src = """
            let helper = { y: Int } -> y + 1
            let run_it: {} -> Int = {} -> do
              let helper = { y: Int } -> y * 100
              helper { y = 2 }
            run_it {} * 10 + helper { y = 1 }
        """.trimIndent()
        assertEquals(2002L, MezzeTestHelper.run(src))
    }

    @Test fun `a parameter that shadows a recursive local lambda is the parameter`() {
        val src = """
            let main: {} -> Int = {} -> do
              let go = { n: Int } -> if n <= 0 then 111 else go { n = n - 1 }
              let apply = { go: { n: Int } -> Int } -> do
                let thunk = {} -> go { n = 3 }
                thunk {}
              apply { go = { n } -> 222 }
            main {}
        """.trimIndent()
        assertEquals(222L, MezzeTestHelper.run(src))
    }

    @Test fun `a match pattern that binds an alias's name is the pattern's binding`() {
        val src = """
            $picks
            let go: {} -> Int = {} -> do
              let pick = pick_hi
              let box = { pick = { a, b } -> 222, other = 0 }
              match box is
                { pick, other } -> pick { a = 1, b = 2 }
            go {}
        """.trimIndent()
        assertEquals(222L, MezzeTestHelper.run(src))
    }

    @Test fun `a match pattern that binds a recursive local lambda's name is the pattern's binding`() {
        val src = """
            let main: {} -> Int = {} -> do
              let go = { n: Int } -> if n <= 0 then 111 else go { n = n - 1 }
              let box = { go = { n } -> 333, other = 0 }
              match box is
                { go, other } -> do
                  let thunk = {} -> go { n = 3 }
                  thunk {}
            main {}
        """.trimIndent()
        assertEquals(333L, MezzeTestHelper.run(src))
    }

    @Test fun `a parameter named like a constrained top-level function is the parameter`() {
        val src = """
            use std::abilities::compare::{ Ord }
            let pick: { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a >= b then a else b
            let call_with = { f: { pick: { a: Int, b: Int } -> Int } -> Int } -> f { pick = { a, b } -> 222 }
            let r = call_with { f = { pick } -> pick { a = 1, b = 2 } }
            r
        """.trimIndent()
        assertEquals(222L, MezzeTestHelper.run(src))
    }
}
