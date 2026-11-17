package com.mezze.interpreter.typing

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BUG 56: a method called on the parameter of a lambda passed STRAIGHT to a call
 * — `[1, 2].map { f = { x } -> x.to_str {} }` — was refused with T0205 ("field
 * access on Int"), because every lambda parameter was treated as unpinnable. The
 * call pins it, so the impl pick may wait for the solved receiver.
 *
 * Asserted on VALUES: a type-only check cannot see the wrong impl being picked,
 * which is exactly how the old fallback (first candidate) failed.
 */
class CallbackParamMethodInferenceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @Test
    fun `a map callback calls an inherent method on its Int element`() {
        val code = """
            use std::collections::seq::{ map, join }
            let render: {} -> Str = {} -> [1, 2].map { f = { x } -> x.to_str {} }.join { sep = "," }
            render {}
        """.trimIndent()
        assertEquals("1,2", MezzeTestHelper.run(code))
    }

    @Test
    fun `the same callback on Float elements picks the Float impl`() {
        val code = """
            use std::collections::seq::{ map, join }
            let render: {} -> Str = {} -> [1.5, 2.0].map { f = { x } -> x.to_str {} }.join { sep = "," }
            render {}
        """.trimIndent()
        assertEquals("1.5,2.0", MezzeTestHelper.run(code))
    }

    @Test
    fun `a method whose return varies by receiver resolves too`() {
        val code = """
            use std::collections::seq::{ map, join }
            let render: {} -> Str = {} -> [0 - 3, 2].map { f = { x } -> x.abs {}.to_str {} }.join { sep = "," }
            render {}
        """.trimIndent()
        assertEquals("3,2", MezzeTestHelper.run(code))
    }

    @Test
    fun `an unannotated HOF pins its callback's parameter from a sibling argument`() {
        val code = """
            let apply = { f, x } -> f { v = x }
            let render: {} -> Str = {} -> apply { f = { v } -> v.to_str {}, x = 5 }
            render {}
        """.trimIndent()
        assertEquals("5", MezzeTestHelper.run(code))
    }

    @Test
    fun `a parameter nothing pins is refused, not guessed`() {
        val code = """
            let g = { h } -> 1
            let n: {} -> Int = {} -> g { h = { x } -> x.to_str {} }
            n {}
        """.trimIndent()
        val err = assertThrows(Throwable::class.java) { MezzeTestHelper.run(code) }
        assertTrue(
            err.message?.contains("receiver's type could not be determined") == true ||
                err.message?.contains("T0206") == true,
            "expected the undetermined-receiver refusal, got: ${err.message}",
        )
    }
}
