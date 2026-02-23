package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the std::int module functions.
 * Tests cover arithmetic, utility functions, and conversions.
 */
class IntInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val parser = com.mezze.language.parser.MezzeParser()
        val typeChecker = com.mezze.language.typechecker.TypeChecker()
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // ========================================
    // Basic Arithmetic
    // ========================================

    @Test
    fun `add sums two integers`() {
        val code = """
            use std::int
            int::add { l = 3, r = 4 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(7), result)
    }

    @Test
    fun `subtract subtracts two integers`() {
        val code = """
            use std::int
            int::subtract { l = 10, r = 3 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(7), result)
    }

    @Test
    fun `multiply multiplies two integers`() {
        val code = """
            use std::int
            int::multiply { l = 3, r = 4 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(12), result)
    }

    @Test
    fun `divide divides two integers`() {
        val code = """
            use std::int
            int::divide { l = 10, r = 3 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `modulo returns remainder`() {
        val code = """
            use std::int
            int::modulo { l = 10, r = 3 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    // ========================================
    // Utility Functions
    // ========================================

    @Test
    fun `abs returns absolute value of positive`() {
        val code = """
            use std::int
            int::abs { n = 5 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `abs returns absolute value of negative`() {
        val code = """
            use std::int
            int::abs { n = -5 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `max returns larger value`() {
        val code = """
            use std::int
            int::max { l = 3, r = 7 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(7), result)
    }

    @Test
    fun `min returns smaller value`() {
        val code = """
            use std::int
            int::min { l = 3, r = 7 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `sign returns 1 for positive`() {
        val code = """
            use std::int
            int::sign { n = 42 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `sign returns -1 for negative`() {
        val code = """
            use std::int
            int::sign { n = -42 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `sign returns 0 for zero`() {
        val code = """
            use std::int
            int::sign { n = 0 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `is_even returns true for even number`() {
        val code = """
            use std::int
            int::is_even { n = 4 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `is_even returns false for odd number`() {
        val code = """
            use std::int
            int::is_even { n = 5 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `is_odd returns true for odd number`() {
        val code = """
            use std::int
            int::is_odd { n = 5 }
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    // ========================================
    // Math Functions
    // ========================================

    @Test
    fun `pow computes power`() {
        val code = """
            use std::int
            int::pow { base = 2, exp = 10 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(1024), result)
    }

    @Test
    fun `pow with zero exponent returns 1`() {
        val code = """
            use std::int
            int::pow { base = 5, exp = 0 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `gcd computes greatest common divisor`() {
        val code = """
            use std::int
            int::gcd { l = 48, r = 18 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(6), result)
    }

    @Test
    fun `lcm computes least common multiple`() {
        val code = """
            use std::int
            int::lcm { l = 4, r = 6 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(12), result)
    }

    @Test
    fun `clamp constrains value to range`() {
        val code = """
            use std::int
            int::clamp { n = 15, min = 0, max = 10 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `clamp returns value when in range`() {
        val code = """
            use std::int
            int::clamp { n = 5, min = 0, max = 10 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `clamp constrains to minimum`() {
        val code = """
            use std::int
            int::clamp { n = -5, min = 0, max = 10 }
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    // ========================================
    // Conversion Functions
    // ========================================

    @Test
    fun `to_string converts to string`() {
        val code = """
            use std::int
            int::to_string { n = 42 }
        """
        val result = eval(code)
        assertEquals(MezzeString("42"), result)
    }

    @Test
    fun `from_string parses valid integer`() {
        val code = """
            use std::int
            match int::from_string { s = "42" } is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `from_string returns None for invalid`() {
        val code = """
            use std::int
            match int::from_string { s = "abc" } is
                Some { val } -> val
                None -> -999
        """
        val result = eval(code)
        assertEquals(MezzeInt(-999), result)
    }

    @Test
    fun `to_nat converts positive to Nat`() {
        val code = """
            use std::int
            match int::to_nat { n = 5 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `to_nat returns None for negative`() {
        val code = """
            use std::int
            match int::to_nat { n = -5 } is
                Some { val } -> 1
                None -> 0
        """
        val result = eval(code)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `from_nat converts Nat to Int`() {
        val code = """
            use std::int
            int::from_nat { n = 42n }
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }
}
