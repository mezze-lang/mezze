package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std::frac module.
 * Migrated from V1 to V2 with unboxed values.
 *
 * Note: Frac values are returned as arrays [numerator, denominator] in V2.
 */
class FracModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test frac add`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::add { l = a, r = b }
            # 1/2 + 1/4 = 3/4
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=3, denominator=4, so result = 3 + 4*100 = 403
        assertEquals(403L, eval(source))
    }

    @Test
    fun `test frac subtract`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::subtract { l = a, r = b }
            # 3/4 - 1/4 = 1/2
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=1, denominator=2, so result = 1 + 2*100 = 201
        assertEquals(201L, eval(source))
    }

    @Test
    fun `test frac multiply`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 2, denominator = 3 }
            let b = frac::make { numerator = 3, denominator = 4 }
            let result = frac::multiply { l = a, r = b }
            # 2/3 * 3/4 = 6/12 = 1/2
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=1, denominator=2, so result = 1 + 2*100 = 201
        assertEquals(201L, eval(source))
    }

    @Test
    fun `test frac divide`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::divide { l = a, r = b }
            # (1/2) / (1/4) = 1/2 * 4/1 = 4/2 = 2/1
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=2, denominator=1, so result = 2 + 1*100 = 102
        assertEquals(102L, eval(source))
    }

    @Test
    fun `test frac equals - true`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 2, denominator = 4 }
            frac::equals { l = a, r = b }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test frac equals - false`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let c = frac::make { numerator = 1, denominator = 3 }
            frac::equals { l = a, r = c }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test frac less_than`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 4 }
            let b = frac::make { numerator = 1, denominator = 2 }
            frac::less_than { l = a, r = b }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test frac greater_than`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            frac::greater_than { l = a, r = b }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test frac to_float`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            frac::to_float { n = a }
        """.trimIndent()

        assertEquals(0.5, eval(source))
    }

    @Test
    fun `test frac from_float round-trips a nice fraction`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            match frac::from_float { f = frac::to_float { n = a } } is
              Some { val = back } -> frac::to_str { n = back }
              None -> "none"
        """.trimIndent()

        assertEquals("3/4", eval(source))
    }

    @Test
    fun `test frac from_float is None for a non-finite float`() {
        val source = """
            use std::frac
            match frac::from_float { f = 0.0 / 0.0 } is
              Some { val = _ } -> "some"
              None -> "none"
        """.trimIndent()

        assertEquals("none", eval(source))
    }

    @Test
    fun `test frac to_str`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            frac::to_str { n = a }
        """.trimIndent()

        assertEquals("3/4", eval(source))
    }

    @Test
    fun `test frac numerator`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            frac::numerator { n = a }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test frac denominator`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            frac::denominator { n = a }
        """.trimIndent()

        assertEquals(4L, eval(source))
    }

    @Test
    fun `test frac abs`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = -3, denominator = 4 }
            let result = frac::abs { n = a }
            frac::numerator { n = result }
        """.trimIndent()

        assertEquals(3L, eval(source))
    }

    @Test
    fun `test frac negate`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let result = frac::negate { n = a }
            frac::numerator { n = result }
        """.trimIndent()

        assertEquals(-3L, eval(source))
    }

    @Test
    fun `test frac from_int`() {
        val source = """
            use std::frac
            let result = frac::from_int { n = 5 }
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=5, denominator=1, so result = 5 + 1*100 = 105
        assertEquals(105L, eval(source))
    }

    @Test
    fun `test frac from_nat`() {
        val source = """
            use std::frac
            let result = frac::from_nat { n = 5n }
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=5, denominator=1, so result = 5 + 1*100 = 105
        assertEquals(105L, eval(source))
    }

    @Test
    fun `test frac reduce`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 6, denominator = 8 }
            let result = frac::reduce { n = a }
            # 6/8 reduces to 3/4
            frac::numerator { n = result } + frac::denominator { n = result } * 100
        """.trimIndent()

        // numerator=3, denominator=4, so result = 3 + 4*100 = 403
        assertEquals(403L, eval(source))
    }
}
